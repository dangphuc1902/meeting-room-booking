package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.room.Room;

/**
 * R2-R8 (+ R11) with a fixed clock: "now" is Monday 2030-01-07 08:00 in Asia/Ho_Chi_Minh (01:00 UTC).
 * Times in the tests are written in business-zone local time, as a user would think of them.
 */
class BookingPolicyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);
    private static final Instant NOW = MONDAY.atTime(8, 0).atZone(ZONE).toInstant();

    private final BookingProperties props = new BookingProperties("07:00", "20:00",
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 15,
            Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofDays(90), Duration.ofDays(31));
    private final BookingPolicy policy = new BookingPolicy(props, Clock.fixed(NOW, ZONE), ZONE);
    private final Room room = new Room("Sao Mai", "Floor 3", 6, Set.of());

    @Test
    void validSlot_passes() {
        assertThatCode(() -> policy.check(room, at(MONDAY, "09:00"), at(MONDAY, "10:00"), 6))
                .doesNotThrowAnyException();
    }

    @Nested
    class R3_TimeRange {

        @Test
        void endBeforeStart_isRejected() {
            assertRule("R3", "invalid-time-range", at(MONDAY, "10:00"), at(MONDAY, "09:00"));
        }

        @Test
        void zeroLength_isRejected() {
            assertRule("R3", "invalid-time-range", at(MONDAY, "10:00"), at(MONDAY, "10:00"));
        }
    }

    @Nested
    class R2_Past {

        @Test
        void startInThePast_isRejected() {
            assertRule("R2", "booking-in-past", at(MONDAY, "07:45"), at(MONDAY, "08:30"));
        }

        @Test
        void startingExactlyNow_isAllowed() {
            assertPasses(at(MONDAY, "08:00"), at(MONDAY, "08:30"));
        }
    }

    @Nested
    class R4_Duration {

        @Test
        void exactlyMinAndMax_areAllowed() {
            assertPasses(at(MONDAY, "09:00"), at(MONDAY, "09:15"));
            assertPasses(at(MONDAY, "09:00"), at(MONDAY, "17:00"));
        }

        @Test
        void longerThanMax_isRejected() {
            assertRule("R4", "invalid-duration", at(MONDAY, "09:00"), at(MONDAY, "17:15"));
        }

        @Test
        void shorterThanMin_isRejected_evenIfOtherwiseValid() {
            BookingPolicy halfHourMin = new BookingPolicy(new BookingProperties("07:00", "20:00",
                    EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 15,
                    Duration.ofMinutes(30), Duration.ofHours(8), Duration.ofDays(90), Duration.ofDays(31)),
                    Clock.fixed(NOW, ZONE), ZONE);

            assertThatThrownBy(() -> halfHourMin.check(room, at(MONDAY, "09:00"), at(MONDAY, "09:15"), 1))
                    .satisfies(ex -> assertViolation(ex, "R4", "invalid-duration"));
        }
    }

    @Nested
    class R5_SlotBoundary {

        @ParameterizedTest
        @CsvSource({ "09:10, 10:00", "09:00, 10:05", "09:00:30, 10:00" })
        void offBoundaryTimes_areRejected(String start, String end) {
            assertRule("R5", "not-on-slot-boundary", at(MONDAY, start), at(MONDAY, end));
        }

        @Test
        void quarterHours_areAllowed() {
            assertPasses(at(MONDAY, "09:15"), at(MONDAY, "10:45"));
        }
    }

    @Nested
    class R6_WorkingHours {

        @Test
        void wholeWorkingDayEdges_areAllowed() {
            LocalDate tuesday = MONDAY.plusDays(1);
            assertPasses(at(tuesday, "07:00"), at(tuesday, "08:00"));
            assertPasses(at(tuesday, "19:00"), at(tuesday, "20:00"));
        }

        @ParameterizedTest
        @CsvSource({ "06:45, 07:30", "19:30, 20:15" })
        void spillingOutsideWorkingHours_isRejected(String start, String end) {
            LocalDate tuesday = MONDAY.plusDays(1);
            assertRule("R6", "outside-working-hours", at(tuesday, start), at(tuesday, end));
        }

        @Test
        void weekend_isRejected() {
            LocalDate saturday = MONDAY.plusDays(5);
            assertRule("R6", "outside-working-hours", at(saturday, "09:00"), at(saturday, "10:00"));
        }

        @Test
        void hoursAreEvaluatedInBusinessZone_notUtc() {
            // 09:00 Hanoi = 02:00 UTC: fine. 22:00 UTC Monday = 05:00 Hanoi Tuesday: before opening.
            assertPasses(Instant.parse("2030-01-08T02:00:00Z"), Instant.parse("2030-01-08T03:00:00Z"));
            assertRule("R6", "outside-working-hours",
                    Instant.parse("2030-01-07T22:00:00Z"), Instant.parse("2030-01-07T23:00:00Z"));
        }
    }

    @Nested
    class R7_MaxAdvance {

        // From a Wednesday, +90 days is a Tuesday and +91 a Wednesday, so R6 never interferes.
        private final LocalDate wednesday = MONDAY.plusDays(2);
        private final BookingPolicy fromWednesday = new BookingPolicy(props,
                Clock.fixed(at(wednesday, "08:00"), ZONE), ZONE);

        @Test
        void exactly90DaysAhead_isAllowed() {
            LocalDate in90Days = wednesday.plusDays(90);
            assertThatCode(() -> fromWednesday.check(room, at(in90Days, "08:00"), at(in90Days, "09:00"), 1))
                    .doesNotThrowAnyException();
        }

        @Test
        void moreThan90DaysAhead_isRejected() {
            LocalDate in90Days = wednesday.plusDays(90);
            assertThatThrownBy(() -> fromWednesday.check(room, at(in90Days, "08:15"), at(in90Days, "09:00"), 1))
                    .satisfies(ex -> assertViolation(ex, "R7", "too-far-in-advance"));
        }
    }

    @Nested
    class R8_Capacity {

        @Test
        void overCapacity_isRejected() {
            assertThatThrownBy(() -> policy.check(room, at(MONDAY, "09:00"), at(MONDAY, "10:00"), 7))
                    .satisfies(ex -> assertViolation(ex, "R8", "over-capacity"));
        }
    }

    @Nested
    class R11_InactiveRoom {

        @Test
        void inactiveRoom_isRejected() {
            room.deactivate();
            assertRule("R11", "room-inactive", at(MONDAY, "09:00"), at(MONDAY, "10:00"));
        }
    }

    private void assertPasses(Instant start, Instant end) {
        assertThatCode(() -> policy.check(room, start, end, 1)).doesNotThrowAnyException();
    }

    private void assertRule(String rule, String code, Instant start, Instant end) {
        assertThatThrownBy(() -> policy.check(room, start, end, 1))
                .satisfies(ex -> assertViolation(ex, rule, code));
    }

    private static void assertViolation(Throwable ex, String rule, String code) {
        assertThat(ex).isInstanceOf(BusinessRuleException.class);
        BusinessRuleException bre = (BusinessRuleException) ex;
        assertThat(bre.errorCode()).isEqualTo(code);
        assertThat(bre.properties()).containsEntry("rule", rule);
    }

    private static Instant at(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time.strip()), ZONE).toInstant();
    }
}
