package com.company.meetingroom.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.booking.dto.BookingResponse;
import com.company.meetingroom.booking.dto.BookingUpdateRequest;
import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.InvalidRequestException;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.common.web.PageResponse;
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
 * SQLSTATE 23P01 (or 40P01, see {@link #saveAndFlush}) and is turned into the same 409.
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
        User owner = requireActiveUser(caller);
        Room room = findRoom(request.roomId());
        Instant start = request.startTime().toInstant();
        Instant end = request.endTime().toInstant();
        policy.check(room, start, end, request.attendeesCount());
        rejectOverlaps(room, start, end, null);

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
        User actor = requireActiveUser(caller);
        Booking booking = findAccessible(actor.getId(), actor.isAdmin(), id);
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
        User actor = requireActiveUser(caller);
        Booking booking = findAccessible(actor.getId(), actor.isAdmin(), id);
        requireConfirmed(booking);
        Instant now = clock.instant();
        if (booking.hasEnded(now)) {
            throw new BusinessRuleException("booking-ended", "A booking that has already ended cannot be cancelled")
                    .with("rule", "R9");
        }
        booking.cancel(caller.id(), reason == null || reason.isBlank() ? null : reason.strip(), now);
        return BookingResponse.of(bookings.saveAndFlush(booking), zone);
    }

    /**
     * The caller's bookings. By default only upcoming or running ones, soonest first;
     * with {@code includePast} the whole history, most recent first.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listMine(AuthUser caller, boolean includePast, BookingStatus status,
            int page, int size) {
        List<Specification<Booking>> filters = new ArrayList<>();
        filters.add(BookingSpecs.ownedBy(caller.id()));
        if (status != null) {
            filters.add(BookingSpecs.withStatus(status));
        }
        if (!includePast) {
            filters.add(BookingSpecs.endsAfter(clock.instant()));
        }
        Sort sort = includePast ? Sort.by("startTime").descending() : Sort.by("startTime").ascending();
        return PageResponse.of(bookings.findAll(Specification.allOf(filters), PageRequest.of(page, size, sort)),
                b -> BookingResponse.of(b, zone));
    }

    /** Admin search; every filter is optional, {@code from}/{@code to} are business-zone days, inclusive. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> search(Long roomId, Long userId, BookingStatus status, LocalDate from,
            LocalDate to, int page, int size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidRequestException("'to' must not be before 'from'");
        }
        List<Specification<Booking>> filters = new ArrayList<>();
        if (roomId != null) {
            filters.add(BookingSpecs.inRoom(roomId));
        }
        if (userId != null) {
            filters.add(BookingSpecs.ownedBy(userId));
        }
        if (status != null) {
            filters.add(BookingSpecs.withStatus(status));
        }
        if (from != null) {
            filters.add(BookingSpecs.endsAfter(from.atStartOfDay(zone).toInstant()));
        }
        if (to != null) {
            filters.add(BookingSpecs.startsBefore(to.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("startTime", "id"));
        return PageResponse.of(bookings.findAll(Specification.allOf(filters), pageable),
                b -> BookingResponse.of(b, zone));
    }

    private Room findRoom(Long roomId) {
        return rooms.findById(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
    }

    /** JWTs stay valid until they expire, so a deactivated account is re-checked here before any write. */
    private User requireActiveUser(AuthUser caller) {
        User user = users.findById(caller.id()).orElseThrow(() -> new NotFoundException("User", caller.id()));
        if (!user.isActive()) {
            throw new AccessDeniedException("Account is deactivated");
        }
        return user;
    }

    /** Owner or admin, otherwise 403. Reads trust the token's role. */
    private Booking findAccessible(AuthUser caller, Long id) {
        return findAccessible(caller.id(), caller.isAdmin(), id);
    }

    /** Writes pass the role read from the database, so a demoted admin cannot edit other people's bookings. */
    private Booking findAccessible(Long callerId, boolean admin, Long id) {
        Booking booking = bookings.findById(id).orElseThrow(() -> new NotFoundException("Booking", id));
        if (!admin && !booking.isOwnedBy(callerId)) {
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

    /**
     * A losing concurrent write surfaces either as an exclusion violation (23P01) or, when several
     * requests for the same slot wait on each other inside the constraint check, as a deadlock (40P01)
     * that PostgreSQL resolves by aborting one of them. The only locks this transaction waits for are
     * those of the constraint check, so both mean "someone else took the slot" and become the same 409.
     */
    private Booking saveAndFlush(Booking booking) {
        try {
            return bookings.saveAndFlush(booking);
        } catch (DataAccessException ex) {
            if (PgErrors.isExclusionViolation(ex) || PgErrors.isDeadlock(ex)) {
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
