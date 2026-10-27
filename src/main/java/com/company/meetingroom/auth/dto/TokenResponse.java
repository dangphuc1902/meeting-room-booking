package com.company.meetingroom.auth.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserSummary user) {
}
