package com.company.meetingroom.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.booking.dto.BookingResponse;
import com.company.meetingroom.booking.dto.BookingUpdateRequest;
import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;

/**
 * Create, edit and cancel bookings.
 * <p>
 * Double booking: the overlap query below only produces a friendly 409 that names who holds
 * the slot. Correctness comes from the {@code no_overlapping_bookings} EXCLUDE constraint:
 * when two requests pass the pre-check at the same time, the loser's insert fails with
 * SQLSTATE 23P01 and is turned into the same 409.
 */
@Service
public class BookingService {

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingPolicy policy;
    private final Clock clock;
    private final ZoneId zone;

    public BookingService(BookingRepository bookings, RoomRepository rooms, UserRepository users,
            BookingPolicy policy, Clock clock, ZoneId businessZone) {
        this.bookings = bookings;
        this.rooms = rooms;
        this.users = users;
        this.policy = policy;
        this.clock = clock;
        this.zone = businessZone;
    }

    @Transactional
    public BookingResponse create(AuthUser caller, BookingRequest request) {
        Room room = findRoom(request.roomId());
        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        policy.check(room, start, end, request.attendeesCount());
        rejectOverlaps(room, start, end, null);

        User owner = users.findById(caller.id()).orElseThrow(() -> new NotFoundException("User", caller.id()));
        Booking booking = new Booking(room, owner, request.title().strip(), request.description(),
                request.attendeesCount(), start, end);
        return BookingResponse.of(saveAndFlush(booking), zone);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(AuthUser caller, Long id) {
        Booking booking = findAccessible(caller, id);
        return BookingResponse.of(booking, zone);
    }

    /** R10: only a CONFIRMED booking that has not started; the new slot must pass every rule again. */
    @Transactional
    public BookingResponse update(AuthUser caller, Long id, BookingUpdateRequest request) {
        Booking booking = findAccessible(caller, id);
        if (booking.getVersion() != request.version()) {
            throw new ConflictException("concurrent-modification", "Concurrent modification",
                    "The booking was modified by someone else. Reload it and try again.");
        }
        requireConfirmed(booking);
        if (booking.hasStarted(clock.instant())) {
            throw new BusinessRuleException("booking-started", "A booking that has already started cannot be edited")
                    .with("rule", "R10");
        }

        Room room = findRoom(request.roomId());
        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        policy.check(room, start, end, request.attendeesCount());
        rejectOverlaps(room, start, end, booking.getId());

        booking.reschedule(room, request.title().strip(), request.description(), request.attendeesCount(), start, end);
        return BookingResponse.of(saveAndFlush(booking), zone);
    }

    /** R9: owner or admin; soft cancel frees the slot immediately (the constraint only covers CONFIRMED). */
    @Transactional
    public BookingResponse cancel(AuthUser caller, Long id, String reason) {
        Booking booking = findAccessible(caller, id);
        requireConfirmed(booking);
        Instant now = clock.instant();
        if (booking.hasEnded(now)) {
            throw new BusinessRuleException("booking-ended", "A booking that has already ended cannot be cancelled")
                    .with("rule", "R9");
        }
        booking.cancel(caller.id(), reason == null || reason.isBlank() ? null : reason.strip(), now);
        return BookingResponse.of(bookings.saveAndFlush(booking), zone);
    }

    private Room findRoom(Long roomId) {
        return rooms.findById(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
    }

    /** Owner or admin, otherwise 403. */
    private Booking findAccessible(AuthUser caller, Long id) {
        Booking booking = bookings.findById(id).orElseThrow(() -> new NotFoundException("Booking", id));
        if (!caller.isAdmin() && !booking.isOwnedBy(caller.id())) {
            throw new AccessDeniedException("Not the owner of booking " + id);
        }
        return booking;
    }

    private static void requireConfirmed(Booking booking) {
        if (!booking.isConfirmed()) {
            throw new BusinessRuleException("booking-cancelled", "The booking has been cancelled");
        }
    }

    private void rejectOverlaps(Room room, Instant start, Instant end, Long ignoreId) {
        List<Conflict> conflicts = bookings.findOverlapping(room.getId(), start, end).stream()
                .filter(b -> !b.getId().equals(ignoreId))
                .map(b -> new Conflict(b.getId(), b.getOwner().getFullName(),
                        b.getStartTime().atZone(zone).toOffsetDateTime(),
                        b.getEndTime().atZone(zone).toOffsetDateTime()))
                .toList();
        if (!conflicts.isEmpty()) {
            Conflict first = conflicts.get(0);
            throw overlap("Room '%s' is already booked by %s from %s to %s".formatted(room.getName(),
                    first.bookedBy(), first.startTime().toLocalTime(), first.endTime().toLocalTime()))
                    .with("conflicts", conflicts);
        }
    }

    private Booking saveAndFlush(Booking booking) {
        try {
            return bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            if (PgErrors.isExclusionViolation(ex)) {
                throw overlap("The room was booked by someone else for this time just now");
            }
            throw ex;
        }
    }

    private static ConflictException overlap(String detail) {
        ConflictException ex = new ConflictException("booking-overlap", "Room already booked", detail);
        ex.with("rule", "R1");
        return ex;
    }

    /** One clashing booking, as shown to the user in the 409 body. */
    public record Conflict(Long bookingId, String bookedBy, OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
