package com.seatlock.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SecurityConfig {
    public static final String ACCESS_COOKIE = "sl_access";
    public static final String REFRESH_COOKIE = "sl_refresh";
    public static final String CSRF_HEADER = "X-Requested-With";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @Value("${app.metrics.token}") String metricsToken) throws Exception {
        // Spring's token CSRF is off; cookie sessions are protected by SameSite=Lax cookies plus CookieCsrfGuard
        // (a custom header no other site can send without passing CORS). Bearer-header clients aren't CSRF-able.
        http.csrf(c -> c.disable())
            .addFilterBefore(new CookieCsrfGuard(), BearerTokenAuthenticationFilter.class)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                // /error is public so a server error is reported as itself, not masked as a 401
                .requestMatchers("/api/auth/**", "/actuator/health", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll() // verified by HMAC signature
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Prometheus scrapes with HTTP Basic (user "prometheus", password METRICS_TOKEN); closed if unset.
                .requestMatchers("/actuator/prometheus").access((auth, ctx) ->
                        new AuthorizationDecision(metricsAllowed(ctx.getRequest().getHeader(HttpHeaders.AUTHORIZATION), metricsToken)))
                .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/events", "/api/events/*/image").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/events/*", "/api/events/*/image").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.bearerTokenResolver(cookieOrHeaderResolver())
                    .jwt(j -> j.jwtAuthenticationConverter(rolesConverter())));
        return http.build();
    }

    /**
     * Authorization: Bearer header first (Swagger, k6, scripts), else the httpOnly sl_access cookie (the browser app).
     * The cookie is ignored on /api/auth/** so an expired one can't block login or refresh with a 401.
     */
    static BearerTokenResolver cookieOrHeaderResolver() {
        var header = new DefaultBearerTokenResolver();
        return req -> {
            String token = header.resolve(req);
            if (token != null || req.getRequestURI().startsWith("/api/auth/")) return token;
            var c = WebUtils.getCookie(req, ACCESS_COOKIE);
            return c == null || c.getValue().isBlank() ? null : c.getValue();
        };
    }

    /** Unsafe requests that ride on cookies (no Authorization header) must carry X-Requested-With, else 403. */
    static class CookieCsrfGuard extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            boolean unsafe = !List.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
            boolean cookieAuth = req.getHeader(HttpHeaders.AUTHORIZATION) == null
                    && (WebUtils.getCookie(req, ACCESS_COOKIE) != null || WebUtils.getCookie(req, REFRESH_COOKIE) != null);
            if (unsafe && cookieAuth && req.getHeader(CSRF_HEADER) == null) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing " + CSRF_HEADER + " header");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    /** Constant-time check of "Basic base64(prometheus:<token>)". */
    static boolean metricsAllowed(String header, String token) {
        if (token == null || token.isBlank() || header == null || !header.startsWith("Basic ")) return false;
        byte[] expected = ("prometheus:" + token).getBytes(StandardCharsets.UTF_8);
        byte[] given;
        try {
            given = java.util.Base64.getDecoder().decode(header.substring(6).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return java.security.MessageDigest.isEqual(expected, given);
    }

    private JwtAuthenticationConverter rolesConverter() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    SecretKey jwtKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) { return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origins}") List<String> origins) {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins.stream().map(String::trim).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
