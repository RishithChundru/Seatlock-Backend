package com.seatlock.domain;

import jakarta.persistence.*;

/** Profile photo bytes. Kept out of AppUser so loading an account never loads the image. */
@Entity
@Table(name = "user_avatars")
public class UserAvatar {
    @Id
    private Long userId;
    @Column(nullable = false, length = 20)
    private String contentType;
    @Column(nullable = false)
    private byte[] data;

    protected UserAvatar() {}

    public UserAvatar(Long userId, String contentType, byte[] data) {
        this.userId = userId;
        this.contentType = contentType;
        this.data = data;
    }

    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
