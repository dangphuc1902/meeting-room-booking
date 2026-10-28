package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.company.meetingroom.booking.DemoBookingSeeder.Planned;
import com.company.meetingroom.room.Room;

/**
 * The demo plan must stay a set of bookings the API itself would accept: every entry passes
 * {@link BookingPolicy} against the rooms seeded by {@code R__demo_rooms.sql}, and none overlap.
 * Room capacities are read from that SQL file, so changing the demo rooms cannot silently break the plan.
 */
class DemoBookingSeederTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** A Friday, so the next working days are Monday to Wednesday. */
    private static final LocalDate FRIDAY = LocalDate.of(2030, 1, 11);

    private final BookingProperties props = new BookingProperties("07:00", "20:00",
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 15,
            Duration.ofMinutes(15), Duration.ofHours(8), Duration.ofDays(90), Duration.ofDays(31));
    private final Clock clock = Clock.fixed(FRIDAY.atTime(18, 0).atZone(ZONE).toInstant(), ZONE);
    private final DemoBookingSeeder seeder =
            new DemoBookingSeeder(null, null, null, null, null, props, clock, ZONE);

    @Test
    void nextWorkingDays_startTomorrow_andSkipTheWeekend() {
        assertThat(seeder.nextWorkingDays(FRIDAY, 3))
                .containsExactly(FRIDAY.plusDays(3), FRIDAY.plusDays(4), FRIDAY.plusDays(5));
    }

    @Test
    void everyPlannedBooking_passesTheBookingRules() throws IOException {
        Map<String, Room> rooms = demoRooms();
        BookingPolicy policy = new BookingPolicy(props, clock, ZONE);
        List<LocalDate> days = seeder.nextWorkingDays(FRIDAY, 3);

        for (Planned p : DemoBookingSeeder.PLAN) {
            assertThat(rooms).as("room '%s' exists in R__demo_rooms.sql", p.room()).containsKey(p.room());
            LocalDate day = days.get(p.workingDay());
            assertThatCode(() -> policy.check(rooms.get(p.room()), at(day, p.start()), at(day, p.end()), p.attendees()))
                    .as(p.title())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void plannedBookings_neverOverlapInTheSameRoom() {
        List<Planned> plan = DemoBookingSeeder.PLAN;
        for (int i = 0; i < plan.size(); i++) {
            for (int j = i + 1; j < plan.size(); j++) {
                Planned a = plan.get(i);
                Planned b = plan.get(j);
                boolean sameSlot = a.room().equals(b.room()) && a.workingDay() == b.workingDay();
                boolean overlap = LocalTime.parse(a.start()).isBefore(LocalTime.parse(b.end()))
                        && LocalTime.parse(b.start()).isBefore(LocalTime.parse(a.end()));
                assertThat(sameSlot && overlap).as("%s / %s", a.title(), b.title()).isFalse();
            }
        }
    }

    private static Map<String, Room> demoRooms() throws IOException {
        String sql;
        try (InputStream in = DemoBookingSeederTest.class.getResourceAsStream("/db/demo/R__demo_rooms.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Room> rooms = new HashMap<>();
        Matcher m = Pattern.compile("\\('([^']+)',\\s*'[^']*',\\s*(\\d+)\\)").matcher(sql);
        while (m.find()) {
            rooms.put(m.group(1), new Room(m.group(1), null, Integer.parseInt(m.group(2)), Set.of()));
        }
        assertThat(rooms).hasSize(10);
        return rooms;
    }

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }
}
