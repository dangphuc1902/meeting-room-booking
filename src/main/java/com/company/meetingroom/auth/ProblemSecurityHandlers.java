package com.company.meetingroom.auth;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.company.meetingroom.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401/403 raised inside the security filter chain never reach @RestControllerAdvice,
 * so they are rendered here in the same RFC 7807 format as every other error.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException ex) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                "Missing, invalid or expired access token", request);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException ex) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You do not have permission to perform this action", request);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String title,
            String detail, HttpServletRequest request) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(ApiException.TYPE_BASE + code));
        pd.setTitle(title);
        pd.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
