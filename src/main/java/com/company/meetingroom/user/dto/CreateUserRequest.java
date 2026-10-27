package com.company.meetingroom.user.dto;

import com.company.meetingroom.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** An admin creates the account and hands the initial password to the employee, who then changes it. */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String fullName,
        @NotNull Role role,
        @NotBlank @Size(min = 8, max = 128) String password) {
}
