package com.company.meetingroom.booking;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Booking rules, configurable per deployment (prefix {@code app.booking}).
 * Times of day are strings ("07:00") to avoid locale-dependent LocalTime parsing.
 */
@Validated
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        @NotNull String workingDayStart,
        @NotNull String workingDayEnd,
        @NotEmpty Set<DayOfWeek> workingDays,
        @Min(1) int slotMinutes,
        @NotNull Duration minDuration,
        @NotNull Duration maxDuration,
        @NotNull Duration maxAdvance,
        @NotNull Duration maxScheduleRange) {

    public BookingProperties {
        if (!LocalTime.parse(workingDayEnd).isAfter(LocalTime.parse(workingDayStart))) {
            throw new IllegalArgumentException("app.booking.working-day-end must be after working-day-start");
        }
        if (minDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("app.booking.min-duration must not exceed max-duration");
        }
    }

    public LocalTime dayStart() {
        return LocalTime.parse(workingDayStart);
    }

    public LocalTime dayEnd() {
        return LocalTime.parse(workingDayEnd);
    }
}
