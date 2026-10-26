package com.company.meetingroom.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, client-facing errors. Each one maps to an RFC 7807 problem:
 * {@code type} = {@value ApiException#TYPE_BASE} + {@link #errorCode()}.
 */
public abstract class ApiException extends RuntimeException {

    public static final String TYPE_BASE = "https://meeting-room.company.local/errors/";

    private final HttpStatus status;
    private final String errorCode;
    private final String title;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    protected ApiException(HttpStatus status, String errorCode, String title, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
    }

    /** Adds an extra field to the problem body, e.g. the conflicting bookings. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}
