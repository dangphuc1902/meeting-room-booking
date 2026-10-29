package com.company.meetingroom.user;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.company.meetingroom.auth.AdminOnly;
import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.common.web.PageResponse;
import com.company.meetingroom.user.dto.ChangePasswordRequest;
import com.company.meetingroom.user.dto.CreateUserRequest;
import com.company.meetingroom.user.dto.ResetPasswordRequest;
import com.company.meetingroom.user.dto.UpdateUserRequest;
import com.company.meetingroom.user.dto.UserResponse;
import com.company.meetingroom.user.dto.UserStatusRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @AdminOnly
    @Operation(summary = "Search users (admin): q matches part of email or name")
    public PageResponse<UserResponse> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return userService.search(query, role, active, page, size);
    }

    @GetMapping("/{id}")
    @AdminOnly
    @Operation(summary = "User details (admin)")
    public UserResponse get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    @AdminOnly
    @Operation(summary = "Create an account with an initial password (admin)")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @AdminOnly
    @Operation(summary = "Change name and role (admin); the last active admin cannot be demoted")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(AuthUser.from(jwt), id, request);
    }

    @PatchMapping("/{id}/status")
    @AdminOnly
    @Operation(summary = "Activate or deactivate an account (admin); deactivated users cannot log in")
    public UserResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody UserStatusRequest request) {
        return userService.changeStatus(AuthUser.from(jwt), id, request.active());
    }

    @PutMapping("/{id}/password")
    @AdminOnly
    @Operation(summary = "Set a new password for a user who forgot theirs (admin)")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/password")
    @Operation(summary = "Change my own password")
    public ResponseEntity<Void> changeOwnPassword(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(AuthUser.from(jwt), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
