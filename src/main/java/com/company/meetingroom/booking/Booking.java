package com.company.meetingroom.booking;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.company.meetingroom.room.Room;
import com.company.meetingroom.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A reservation of one room for the half-open interval [startTime, endTime).
 * Non-overlap of CONFIRMED bookings is guaranteed by the database (EXCLUDE constraint),
 * not by this class.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "attendees_count", nullable = false)
    private int attendeesCount;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Room room, User owner, String title, String description, int attendeesCount,
            Instant startTime, Instant endTime) {
        this.room = room;
        this.owner = owner;
        this.title = title;
        this.description = description;
        this.attendeesCount = attendeesCount;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void reschedule(Room room, String title, String description, int attendeesCount,
            Instant startTime, Instant endTime) {
        this.room = room;
        this.title = title;
        this.description = description;
        this.attendeesCount = attendeesCount;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void cancel(Long byUserId, String reason, Instant at) {
        this.status = BookingStatus.CANCELLED;
        this.cancelledBy = byUserId;
        this.cancelReason = reason;
        this.cancelledAt = at;
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }

    public boolean isConfirmed() {
        return status == BookingStatus.CONFIRMED;
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startTime);
    }

    public boolean hasEnded(Instant now) {
        return !now.isBefore(endTime);
    }

    public Long getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public User getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getAttendeesCount() {
        return attendeesCount;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
