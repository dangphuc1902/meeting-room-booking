package com.company.meetingroom.common.error;

import org.springframework.http.HttpStatus;

/** 400: parameters that are well-typed but make no sense together (end before start, range too long...). */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid request", detail);
    }
}
