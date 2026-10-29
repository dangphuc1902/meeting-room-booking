package com.company.meetingroom.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.common.web.PageResponse;
import com.company.meetingroom.user.dto.CreateUserRequest;
import com.company.meetingroom.user.dto.UpdateUserRequest;
import com.company.meetingroom.user.dto.UserResponse;

/**
 * Account management. There is no self sign-up: admins create accounts (internal system).
 * Accounts are deactivated, never deleted, because bookings reference them.
 * <p>
 * Lock-out guards: an admin cannot demote or deactivate themselves, and the last active admin
 * can never be demoted or deactivated.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** {@code query} matches part of the email or the name, ignoring case; every filter is optional. */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String query, Role role, Boolean active, int page, int size) {
        List<Specification<User>> filters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String pattern = "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
            filters.add((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("fullName")), pattern, '\\')));
        }
        if (role != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("role"), role));
        }
        if (active != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("active"), active));
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by("fullName", "id"));
        return PageResponse.of(users.findAll(Specification.allOf(filters), pageable), UserResponse::of);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.of(find(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw emailTaken(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.fullName().strip(),
                request.role());
        try {
            return UserResponse.of(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            // Two admins creating the same account at once: the unique constraint decides.
            if (PgErrors.isUniqueViolation(ex)) {
                throw emailTaken(email);
            }
            throw ex;
        }
    }

    @Transactional
    public UserResponse update(AuthUser caller, Long id, UpdateUserRequest request) {
        User user = find(id);
        if (user.isAdmin() && request.role() != Role.ADMIN) {
            requireNotSelf(caller, user, "remove your own admin role");
            requireAnotherActiveAdmin(user);
        }
        user.rename(request.fullName().strip());
        user.changeRole(request.role());
        return UserResponse.of(users.saveAndFlush(user));
    }

    /**
     * A deactivated user can no longer log in. A token issued before stays valid until it expires
     * (stateless JWT, at most one working day); their bookings are kept.
     */
    @Transactional
    public UserResponse changeStatus(AuthUser caller, Long id, boolean active) {
        User user = find(id);
        if (active) {
            user.activate();
        } else if (user.isActive()) {
            requireNotSelf(caller, user, "deactivate your own account");
            if (user.isAdmin()) {
                requireAnotherActiveAdmin(user);
            }
            user.deactivate();
        }
        return UserResponse.of(users.saveAndFlush(user));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        User user = find(id);
        user.changePassword(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public void changeOwnPassword(AuthUser caller, String currentPassword, String newPassword) {
        User user = find(caller.id());
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("wrong-current-password", "The current password is not correct");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
    }

    private User find(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    private static void requireNotSelf(AuthUser caller, User target, String action) {
        if (target.getId().equals(caller.id())) {
            throw new BusinessRuleException("self-lockout", "You cannot " + action + "; ask another admin");
        }
    }

    /**
     * Locks the active admin rows so two admins demoting or deactivating each other at the same
     * moment cannot both pass the check: the second one waits and then sees only one admin left.
     */
    private void requireAnotherActiveAdmin(User admin) {
        int activeAdmins = users.lockActiveByRole(Role.ADMIN).size();
        if (admin.isActive() && activeAdmins <= 1) {
            throw new BusinessRuleException("last-admin", "The system must keep at least one active admin");
        }
    }

    private static ConflictException emailTaken(String email) {
        return new ConflictException("email-taken", "Email already in use",
                "An account with email '%s' already exists".formatted(email));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
