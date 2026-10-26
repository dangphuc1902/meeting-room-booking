package com.company.meetingroom.common.error;

import org.springframework.http.HttpStatus;

/**
 * 422: well-formed request that breaks a business rule
 * (outside working hours, in the past, over capacity, room inactive...).
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String errorCode, String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, "Business rule violated", detail);
    }
}
