package com.company.meetingroom.common.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "not-found", "Resource not found",
                "%s with id %s was not found".formatted(resource, id));
    }
}
