package com.company.meetingroom.room.dto;

import java.util.Set;

import com.company.meetingroom.room.Equipment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of create and full update (PUT): every field is replaced, a missing {@code equipment} means none. */
public record RoomRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String location,
        @NotNull @Min(1) @Max(1000) Integer capacity,
        Set<@NotNull Equipment> equipment) {
}
