package com.seatlock.domain;

import jakarta.persistence.*;

/** Logo bytes when no object storage is configured. Kept out of Organizer so loading profiles never loads images. */
@Entity
@Table(name = "organizer_logos")
public class OrganizerLogo {
    @Id
    private Long organizerId;
    @Column(nullable = false, length = 20)
    private String contentType;
    @Column(nullable = false)
    private byte[] data;

    protected OrganizerLogo() {}

    public OrganizerLogo(Long organizerId, String contentType, byte[] data) {
        this.organizerId = organizerId;
        this.contentType = contentType;
        this.data = data;
    }

    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
