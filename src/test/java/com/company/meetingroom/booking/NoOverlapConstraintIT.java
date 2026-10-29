package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.support.AbstractIntegrationTest;

/**
 * Proves the core invariant at the database level, independent of any service code:
 * two CONFIRMED bookings of the same room can never overlap, even under concurrency.
 */
class NoOverlapConstraintIT extends AbstractIntegrationTest {

    private static final OffsetDateTime DAY = OffsetDateTime.of(2030, 1, 7, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    JdbcTemplate jdbc;

    long roomId;
    long otherRoomId;
    long userId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE bookings");
        roomId = insertRoom();
        otherRoomId = insertRoom();
        userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role)
                VALUES (?, 'x', 'Test User', 'USER') RETURNING id
                """, Long.class, "u-" + UUID.randomUUID() + "@test.local");
    }

    @Test
    void overlappingBookingInSameRoom_isRejectedWithExclusionViolation() {
        insertBooking(roomId, at(9, 0), at(10, 0));

        assertThatThrownBy(() -> insertBooking(roomId, at(9, 30), at(10, 30)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(PgErrors.isExclusionViolation(ex)).isTrue());
    }

    @Test
    void containedAndEnclosingRanges_areRejected() {
        insertBooking(roomId, at(9, 0), at(11, 0));

        assertThatThrownBy(() -> insertBooking(roomId, at(9, 30), at(10, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBooking(roomId, at(8, 0), at(12, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void backToBackBookings_areAllowed_becauseRangesAreHalfOpen() {
        insertBooking(roomId, at(9, 0), at(10, 0));

        assertThatCode(() -> insertBooking(roomId, at(10, 0), at(11, 0))).doesNotThrowAnyException();
        assertThatCode(() -> insertBooking(roomId, at(8, 0), at(9, 0))).doesNotThrowAnyException();
    }

    @Test
    void sameTimeInDifferentRoom_isAllowed() {
        insertBooking(roomId, at(9, 0), at(10, 0));

        assertThatCode(() -> insertBooking(otherRoomId, at(9, 0), at(10, 0))).doesNotThrowAnyException();
    }

    @Test
    void cancelledBooking_releasesTheSlot() {
        long id = insertBooking(roomId, at(9, 0), at(10, 0));
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", id);

        assertThatCode(() -> insertBooking(roomId, at(9, 0), at(10, 0))).doesNotThrowAnyException();
    }

    @Test
    void endBeforeStart_isRejected() {
        assertThatThrownBy(() -> insertBooking(roomId, at(10, 0), at(9, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentInsertsForSameSlot_exactlyOneSucceeds() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    insertBooking(roomId, at(14, 0), at(15, 0));
                    succeeded.incrementAndGet();
                } catch (DataAccessException ex) {
                    // Losers get 23P01, or 40P01 when they deadlock on each other inside the constraint check.
                    if (PgErrors.isExclusionViolation(ex) || PgErrors.isDeadlock(ex)) {
                        rejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE room_id = ? AND status = 'CONFIRMED'", Long.class, roomId))
                .isEqualTo(1L);
    }

    private long insertRoom() {
        return jdbc.queryForObject(
                "INSERT INTO rooms (name, capacity) VALUES (?, 8) RETURNING id",
                Long.class, "Room-" + UUID.randomUUID());
    }

    private long insertBooking(long room, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.queryForObject("""
                INSERT INTO bookings (room_id, user_id, title, start_time, end_time)
                VALUES (?, ?, 'Test meeting', ?, ?) RETURNING id
                """, Long.class, room, userId, start, end);
    }

    private static OffsetDateTime at(int hour, int minute) {
        return DAY.withHour(hour).withMinute(minute);
    }
}
