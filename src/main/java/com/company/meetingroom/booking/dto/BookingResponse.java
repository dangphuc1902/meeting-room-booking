package com.company.meetingroom.booking.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.company.meetingroom.booking.Booking;
import com.company.meetingroom.booking.BookingStatus;

/** Times are returned in the business time zone (e.g. +07:00) so clients can show them as is. */
public record BookingResponse(
        Long id,
        RoomRef room,
        OwnerRef owner,
        String title,
        String description,
        int attendeesCount,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        BookingStatus status,
        OffsetDateTime cancelledAt,
        String cancelReason,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record RoomRef(Long id, String name) {
    }

    public record OwnerRef(Long id, String fullName, String email) {
    }

    public static BookingResponse of(Booking b, ZoneId zone) {
        return new BookingResponse(b.getId(),
                new RoomRef(b.getRoom().getId(), b.getRoom().getName()),
                new OwnerRef(b.getOwner().getId(), b.getOwner().getFullName(), b.getOwner().getEmail()),
                b.getTitle(), b.getDescription(), b.getAttendeesCount(),
                at(b.getStartTime(), zone), at(b.getEndTime(), zone),
                b.getStatus(), at(b.getCancelledAt(), zone), b.getCancelReason(),
                b.getVersion(), b.getCreatedAt(), b.getUpdatedAt());
    }

    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
