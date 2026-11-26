package com.seatlock.service;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.RefreshToken;
import com.seatlock.domain.Repositories.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions = short-lived JWT access token + long-lived opaque refresh token (httpOnly cookie).
 * Refresh tokens rotate on every use and are stored only as SHA-256 hashes. Presenting a token that was
 * already rotated means it was copied: the whole login (token family) is revoked, so a thief and the
 * real user both have to sign in again. A short grace window covers two tabs refreshing at once.
 */
@Service
public class AuthService {
    public static final Duration REUSE_GRACE = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Session(String accessToken, String refreshToken, AppUser user) {}

    public static class InvalidRefreshException extends RuntimeException {
        public InvalidRefreshException(String message) { super(message); }
    }

    private final RefreshTokenRepository tokens;
    private final JwtEncoder jwt;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public AuthService(RefreshTokenRepository tokens, JwtEncoder jwt,
                       @Value("${app.auth.access-ttl}") Duration accessTtl, @Value("${app.auth.refresh-ttl}") Duration refreshTtl) {
        this.tokens = tokens;
        this.jwt = jwt;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public Duration refreshTtl() { return refreshTtl; }
    public Duration accessTtl() { return accessTtl; }

    /** New login: a fresh token family. */
    @Transactional
    public Session start(AppUser user) {
        return issue(user, UUID.randomUUID());
    }

    /** Swap a refresh token for a new pair. The old one stops working (rotation). */
    @Transactional(noRollbackFor = InvalidRefreshException.class) // keep the family revocation on reuse
    public Session rotate(String rawRefresh) {
        RefreshToken current = find(rawRefresh);
        if (current.isRevoked()) {
            if (current.getRevokedAt().plus(REUSE_GRACE).isAfter(Instant.now()))
                throw new InvalidRefreshException("Session was just refreshed in another tab"); // benign race
            tokens.revokeFamily(current.getFamilyId(), Instant.now());
            throw new InvalidRefreshException("Session ended for security reasons. Please sign in again.");
        }
        if (current.isExpired()) throw new InvalidRefreshException("Session expired. Please sign in again.");
        current.revoke();
        return issue(current.getUser(), current.getFamilyId());
    }

    /** Who owns this refresh token, without rotating it (used by the frontend's route guard). */
    @Transactional(readOnly = true)
    public Optional<AppUser> peek(String rawRefresh) {
        try {
            RefreshToken t = find(rawRefresh);
            return t.isRevoked() || t.isExpired() ? Optional.empty() : Optional.of(t.getUser());
        } catch (InvalidRefreshException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) return;
        tokens.findByTokenHash(hash(rawRefresh)).ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /** After a password change: sign out every other device, keep this one. */
    @Transactional
    public void endOtherSessions(Long userId, String currentRawRefresh) {
        UUID keep = currentRawRefresh == null ? UUID.randomUUID()
                : tokens.findByTokenHash(hash(currentRawRefresh)).map(RefreshToken::getFamilyId).orElse(UUID.randomUUID());
        tokens.revokeOtherFamilies(userId, keep, Instant.now());
    }

    public String accessToken(AppUser u) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("seatlock").subject(u.getId().toString())
                .claim("email", u.getEmail()).claim("name", u.getName()).claim("roles", List.of(u.getRole().name()))
                .issuedAt(now).expiresAt(now.plus(accessTtl)).build();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private Session issue(AppUser user, UUID family) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(user, hash(raw), family, Instant.now().plus(refreshTtl)));
        return new Session(accessToken(user), raw, user);
    }

    private RefreshToken find(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidRefreshException("Not signed in");
        return tokens.findByTokenHash(hash(raw)).orElseThrow(() -> new InvalidRefreshException("Not signed in"));
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
