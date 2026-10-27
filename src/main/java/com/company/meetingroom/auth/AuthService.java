package com.company.meetingroom.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.auth.dto.LoginRequest;
import com.company.meetingroom.auth.dto.TokenResponse;
import com.company.meetingroom.auth.dto.UserSummary;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    /** Compared against when the email is unknown, so response time doesn't reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(User.normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()) || !user.isActive()) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        TokenService.IssuedToken token = tokenService.issue(user);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), UserSummary.of(user));
    }

    @Transactional(readOnly = true)
    public UserSummary me(AuthUser caller) {
        return users.findById(caller.id())
                .map(UserSummary::of)
                .orElseThrow(() -> new NotFoundException("User", caller.id()));
    }
}
