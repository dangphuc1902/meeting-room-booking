package com.company.meetingroom.booking.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /bookings. Times are ISO-8601 with an explicit offset (R12), e.g. 2030-01-07T09:00:00+07:00. */
public record BookingRequest(
        @NotNull Long roomId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @NotNull @Min(1) Integer attendeesCount,
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime) {
}
