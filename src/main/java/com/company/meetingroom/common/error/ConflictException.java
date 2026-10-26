package com.company.meetingroom.common.error;

import org.springframework.http.HttpStatus;

/** 409: the request is valid but clashes with current state (double booking, duplicate name...). */
public class ConflictException extends ApiException {

    public ConflictException(String errorCode, String title, String detail) {
        super(HttpStatus.CONFLICT, errorCode, title, detail);
    }
}
