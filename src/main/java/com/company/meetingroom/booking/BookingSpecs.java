package com.company.meetingroom.booking;

import java.time.Instant;

import org.springframework.data.jpa.domain.Specification;

/** Composable filters for booking lists; combined with {@link Specification#allOf}. */
final class BookingSpecs {

    private BookingSpecs() {
    }

    static Specification<Booking> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("owner").get("id"), userId);
    }

    static Specification<Booking> inRoom(Long roomId) {
        return (root, query, cb) -> cb.equal(root.get("room").get("id"), roomId);
    }

    static Specification<Booking> withStatus(BookingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Still running or upcoming at {@code instant}. */
    static Specification<Booking> endsAfter(Instant instant) {
        return (root, query, cb) -> cb.greaterThan(root.get("endTime"), instant);
    }

    static Specification<Booking> startsBefore(Instant instant) {
        return (root, query, cb) -> cb.lessThan(root.get("startTime"), instant);
    }
}
