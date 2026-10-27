package com.company.meetingroom.room.dto;

import jakarta.validation.constraints.NotNull;

public record RoomStatusRequest(@NotNull Boolean active) {
}
