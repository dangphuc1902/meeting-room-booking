package com.company.meetingroom.booking.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.company.meetingroom.booking.Booking;

/**
 * One booking as shown on a schedule. Everyone sees time, title and who booked (so they know whom
 * to ask about the room); {@code description} is only filled for the owner and admins.
 */
public record ScheduleEntry(
        Long bookingId,
        String title,
        String description,
        BookedBy bookedBy,
        int attendeesCount,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {

    public record BookedBy(Long id, String fullName) {
    }

    public static ScheduleEntry of(Booking b, ZoneId zone, boolean showDescription) {
        return new ScheduleEntry(b.getId(), b.getTitle(), showDescription ? b.getDescription() : null,
                new BookedBy(b.getOwner().getId(), b.getOwner().getFullName()), b.getAttendeesCount(),
                b.getStartTime().atZone(zone).toOffsetDateTime(), b.getEndTime().atZone(zone).toOffsetDateTime());
    }
}
