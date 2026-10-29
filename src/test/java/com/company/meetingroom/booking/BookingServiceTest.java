package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;

/**
 * Paths that are hard to reach deterministically through HTTP: how a lost race on the
 * no-overlap constraint is reported. {@code BookingApiIT} covers the real concurrent case.
 */
class BookingServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 7, 9, 0, 0, 0, ZoneOffset.ofHours(7));

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BookingPolicy policy = mock(BookingPolicy.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final BookingService service = new BookingService(bookings, rooms, users, policy, clock, ZONE);

    private final AuthUser caller = new AuthUser(1L, "alice@test.local", Role.USER);
    private final BookingRequest request = new BookingRequest(10L, "Team sync", null, 2, START, START.plusHours(1));

    @BeforeEach
    void setUp() {
        when(users.findById(1L)).thenReturn(Optional.of(new User("alice@test.local", "x", "Alice", Role.USER)));
        when(rooms.findById(10L)).thenReturn(Optional.of(new Room("Sao Mai", "Floor 1", 6, Set.of())));
        when(bookings.findOverlapping(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void exclusionViolationOnInsert_becomes409() {
        when(bookings.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("overlap",
                new SQLException("conflicting key value violates exclusion constraint", PgErrors.EXCLUSION_VIOLATION)));

        assertOverlap409(() -> service.create(caller, request));
    }

    @Test
    void deadlockWhileCheckingTheConstraint_becomes409_not500() {
        // What Spring throws when PostgreSQL aborts one of several inserts waiting on each other.
        when(bookings.saveAndFlush(any())).thenThrow(new CannotAcquireLockException("deadlock",
                new SQLException("deadlock detected", PgErrors.DEADLOCK_DETECTED)));

        assertOverlap409(() -> service.create(caller, request));
    }

    @Test
    void otherLockFailures_areNotDisguisedAsOverlaps() {
        when(bookings.saveAndFlush(any())).thenThrow(new CannotAcquireLockException("lock timeout",
                new SQLException("canceling statement due to lock timeout", "55P03")));

        assertThatThrownBy(() -> service.create(caller, request)).isInstanceOf(CannotAcquireLockException.class);
    }

    private static void assertOverlap409(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo("booking-overlap"));
    }
}
