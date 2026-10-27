package com.company.meetingroom.auth.dto;

import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;

public record UserSummary(Long id, String email, String fullName, Role role) {

    public static UserSummary of(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
