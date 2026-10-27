package com.company.meetingroom.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret, @NotBlank String issuer, @NotNull Duration ttl) {

    public JwtProperties {
        // HS256 requires a key of at least 256 bits; fail fast at startup instead of at first login.
        if (secret != null && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.jwt.secret must be at least 32 bytes");
        }
    }
}
