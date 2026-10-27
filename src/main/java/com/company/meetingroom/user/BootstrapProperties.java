package com.company.meetingroom.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Accounts created on startup. The system is internal, so there is no self sign-up:
 * the first admin comes from configuration and creates everyone else.
 * {@code demoUser} is only set by the "demo" profile.
 */
@Validated
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(@Valid @NotNull Account admin, @Valid Account demoUser) {

    public record Account(@NotBlank String email, @NotBlank String password, @NotBlank String fullName) {
    }
}
