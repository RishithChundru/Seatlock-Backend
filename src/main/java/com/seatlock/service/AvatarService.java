package com.seatlock.service;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserAvatarRepository;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.domain.UserAvatar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

/** Profile photos for every role. shortcut: stored in Postgres only; move to object storage like covers if they grow. */
@Service
public class AvatarService {
    static final int MAX_BYTES = 1024 * 1024;

    private final UserRepository users;
    private final UserAvatarRepository avatars;

    public AvatarService(UserRepository users, UserAvatarRepository avatars) {
        this.users = users;
        this.avatars = avatars;
    }

    @Transactional
    public AppUser set(Long userId, byte[] data) {
        if (data.length == 0 || data.length > MAX_BYTES) throw new IllegalArgumentException("Photo must be under 1 MB");
        String type = EventService.sniffImageType(data); // magic bytes, never the client's Content-Type
        if (type == null) throw new IllegalArgumentException("Only JPEG, PNG or WebP images are allowed");
        AppUser u = users.findById(userId).orElseThrow();
        avatars.save(new UserAvatar(userId, type, data));
        u.setAvatarUpdatedAt(Instant.now());
        return u;
    }

    @Transactional
    public AppUser remove(Long userId) {
        AppUser u = users.findById(userId).orElseThrow();
        if (avatars.existsById(userId)) avatars.deleteById(userId);
        u.setAvatarUpdatedAt(null);
        return u;
    }

    @Transactional(readOnly = true)
    public UserAvatar get(Long userId) {
        return avatars.findById(userId).orElseThrow(() -> new NoSuchElementException("No photo"));
    }
}
