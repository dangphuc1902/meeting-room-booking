package com.company.meetingroom.booking.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /bookings/{id}: replaces every editable field. {@code version} is the one the client
 * last read; if someone changed the booking since, the update is refused with 409 (R10).
 */
public record BookingUpdateRequest(
        @NotNull Long roomId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @NotNull @Min(1) Integer attendeesCount,
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime,
        @NotNull Long version) {
}
