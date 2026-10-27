package com.company.meetingroom.booking.dto;

import java.time.LocalDate;
import java.util.List;

import com.company.meetingroom.booking.dto.RoomSchedule.RoomSummary;

/** Company-wide view of one business-zone day: every active room, with its bookings (possibly none). */
public record DaySchedule(LocalDate date, String zone, List<RoomDay> rooms) {

    public record RoomDay(RoomSummary room, List<ScheduleEntry> bookings) {
    }
}
