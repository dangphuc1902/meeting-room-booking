package com.company.meetingroom.user.dto;

import com.company.meetingroom.user.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The email is the login and stays fixed; create a new account if it really changes. */
public record UpdateUserRequest(@NotBlank @Size(max = 255) String fullName, @NotNull Role role) {
}
