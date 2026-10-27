package com.company.meetingroom.user.dto;

import java.time.Instant;

import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;

public record UserResponse(Long id, String email, String fullName, Role role, boolean active, Instant createdAt,
        Instant updatedAt) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.isActive(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
