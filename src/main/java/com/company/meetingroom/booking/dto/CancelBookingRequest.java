package com.company.meetingroom.booking.dto;

import jakarta.validation.constraints.Size;

/** Optional body of POST /bookings/{id}/cancel. */
public record CancelBookingRequest(@Size(max = 500) String reason) {
}
