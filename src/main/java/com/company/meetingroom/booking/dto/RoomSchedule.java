package com.company.meetingroom.booking.dto;

import java.time.LocalDate;
import java.util.List;

import com.company.meetingroom.room.Room;

/** CONFIRMED bookings of one room for the business-zone days [from, to], both inclusive. */
public record RoomSchedule(RoomSummary room, LocalDate from, LocalDate to, String zone, List<ScheduleEntry> bookings) {

    public record RoomSummary(Long id, String name, String location, int capacity, boolean active) {

        public static RoomSummary of(Room room) {
            return new RoomSummary(room.getId(), room.getName(), room.getLocation(), room.getCapacity(),
                    room.isActive());
        }
    }
}
