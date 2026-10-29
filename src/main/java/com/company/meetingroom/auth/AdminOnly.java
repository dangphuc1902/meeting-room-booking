package com.company.meetingroom.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Admin-only endpoint. The {@code ADMIN} role in the token is not enough on its own: a token stays
 * valid until it expires, so the caller is also re-checked in the database ({@link AccountChecks}).
 * An admin who was deactivated or demoted gets 403 straight away instead of keeping admin rights
 * (and, for example, re-activating their own account) for the rest of the token's lifetime.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasRole('ADMIN') and @accountChecks.isActiveAdmin(authentication)")
public @interface AdminOnly {
}
