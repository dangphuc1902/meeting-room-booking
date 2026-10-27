package com.company.meetingroom.room.dto;

import java.time.Instant;
import java.util.Set;

import com.company.meetingroom.room.Equipment;
import com.company.meetingroom.room.Room;

public record RoomResponse(
        Long id,
        String name,
        String location,
        int capacity,
        Set<Equipment> equipment,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static RoomResponse of(Room room) {
        return new RoomResponse(room.getId(), room.getName(), room.getLocation(), room.getCapacity(),
                room.getEquipment(), room.isActive(), room.getCreatedAt(), room.getUpdatedAt());
    }
}
