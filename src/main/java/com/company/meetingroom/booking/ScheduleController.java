package com.company.meetingroom.booking;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.DaySchedule;
import com.company.meetingroom.booking.dto.RoomSchedule;
import com.company.meetingroom.room.Equipment;
import com.company.meetingroom.room.dto.RoomResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Availability and schedules. Lives in the booking module because it reads bookings;
 * {@code /rooms/availability} wins over {@code /rooms/{id}} since literal paths are more specific.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/rooms/availability")
    @Operation(summary = "Active rooms free for the whole of [start, end), smallest fitting room first. "
            + "Times need an offset; in a URL '+' must be encoded as %2B (e.g. 09:00:00%2B07:00)")
    public List<RoomResponse> availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @RequestParam(defaultValue = "1") int minCapacity,
            @RequestParam(required = false) Set<Equipment> equipment) {
        return scheduleService.findAvailable(start.toInstant(), end.toInstant(), minCapacity, equipment);
    }

    @GetMapping("/rooms/{id}/schedule")
    @Operation(summary = "Bookings of one room for business-zone days [from, to] (default: 7 days from today, max 31)")
    public RoomSchedule roomSchedule(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return scheduleService.roomSchedule(AuthUser.from(jwt), id, from, to);
    }

    @GetMapping("/schedule")
    @Operation(summary = "Company-wide schedule of one day (default: today), grouped by room")
    public DaySchedule daySchedule(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scheduleService.daySchedule(AuthUser.from(jwt), date);
    }
}
