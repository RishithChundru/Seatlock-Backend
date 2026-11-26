package com.seatlock.domain;

import jakarta.persistence.*;

/** Cover image bytes, kept out of Event so listing events never loads image data. */
@Entity
@Table(name = "event_images")
public class EventImage {
    @Id
    private Long eventId;
    @Column(nullable = false, length = 20)
    private String contentType;
    @Column(nullable = false)
    private byte[] data;

    protected EventImage() {}

    public EventImage(Long eventId, String contentType, byte[] data) {
        this.eventId = eventId;
        this.contentType = contentType;
        this.data = data;
    }

    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
