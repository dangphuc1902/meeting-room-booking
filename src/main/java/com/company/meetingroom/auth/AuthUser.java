package com.company.meetingroom.auth;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

import com.company.meetingroom.user.Role;

/**
 * The caller, as read from the validated access token.
 * Use in controllers: {@code @AuthenticationPrincipal Jwt jwt} then {@code AuthUser.from(jwt)}.
 */
public record AuthUser(Long id, String email, Role role) {

    public static AuthUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(SecurityConfig.ROLES_CLAIM);
        Role role = roles != null && roles.contains(Role.ADMIN.name()) ? Role.ADMIN : Role.USER;
        return new AuthUser(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("email"), role);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
