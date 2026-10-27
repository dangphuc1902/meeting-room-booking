package com.company.meetingroom.booking;

public enum BookingStatus {
    CONFIRMED,
    /** Soft cancel: the row is kept for history and no longer blocks the slot. */
    CANCELLED
}
