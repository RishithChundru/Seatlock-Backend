package com.seatlock.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "row_label", "seat_number"}))
public class Seat {
    public enum Status { AVAILABLE, BOOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id")
    private Event event;
    @Column(name = "row_label", nullable = false)
    private String rowLabel;
    @Column(name = "seat_number", nullable = false)
    private int seatNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.AVAILABLE;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "booking_id")
    private Booking booking;

    // Optimistic lock: UPDATE ... WHERE version = ? fails for the second of two concurrent bookers.
    @Version
    private long version;

    protected Seat() {}

    public Seat(Event event, String rowLabel, int seatNumber) {
        this.event = event;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }

    public void book(Booking booking) {
        this.status = Status.BOOKED;
        this.booking = booking;
        booking.getSeats().add(this);
    }

    /** Back on sale; @Version still guards against a concurrent booking of the same row. */
    public void release() {
        this.status = Status.AVAILABLE;
        this.booking = null;
    }

    public String label() { return rowLabel + seatNumber; }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getRowLabel() { return rowLabel; }
    public int getSeatNumber() { return seatNumber; }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
}
