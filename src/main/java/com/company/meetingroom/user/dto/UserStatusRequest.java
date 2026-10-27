package com.company.meetingroom.user.dto;

import jakarta.validation.constraints.NotNull;

public record UserStatusRequest(@NotNull Boolean active) {
}
