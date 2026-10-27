package com.company.meetingroom.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.company.meetingroom.user.User;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim(SecurityConfig.ROLES_CLAIM, List.of(user.getRole().name()))
                .build();
        // The header must name HS256 explicitly; the encoder would otherwise default to RS256.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, props.ttl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
