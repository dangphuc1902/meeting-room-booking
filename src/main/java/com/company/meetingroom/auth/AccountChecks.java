package com.company.meetingroom.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.user.UserRepository;

/** Database checks on the caller, used from {@code @PreAuthorize} expressions (see {@link AdminOnly}). */
@Component("accountChecks")
public class AccountChecks {

    private final UserRepository users;

    public AccountChecks(UserRepository users) {
        this.users = users;
    }

    /** The caller still exists, is active and still has the ADMIN role, whatever the token says. */
    @Transactional(readOnly = true)
    public boolean isActiveAdmin(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return users.findById(AuthUser.from(jwt).id())
                .map(user -> user.isActive() && user.isAdmin())
                .orElse(false);
    }
}
