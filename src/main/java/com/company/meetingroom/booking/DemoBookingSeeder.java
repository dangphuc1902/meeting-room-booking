package com.company.meetingroom.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.user.BootstrapProperties;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;

/**
 * "demo" profile only: books a few meetings on the next working days so the schedule is not
 * empty when a reviewer first opens Swagger. Runs after {@code AccountBootstrap} because the
 * bookings need owners, which is also why this is Java and not a Flyway migration (Flyway runs
 * before any account exists). Dates are relative to today, and every booking goes through
 * {@link BookingPolicy} like a real one. Seeds only into an empty bookings table, so restarts
 * and data created by hand are left alone.
 */
@Component
@Profile("demo")
@Order(2)
public class DemoBookingSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBookingSeeder.class);

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BootstrapProperties accounts;
    private final BookingPolicy policy;
    private final BookingProperties props;
    private final Clock clock;
    private final ZoneId zone;

    public DemoBookingSeeder(BookingRepository bookings, RoomRepository rooms, UserRepository users,
            BootstrapProperties accounts, BookingPolicy policy, BookingProperties props, Clock clock,
            ZoneId businessZone) {
        this.bookings = bookings;
        this.rooms = rooms;
        this.users = users;
        this.accounts = accounts;
        this.policy = policy;
        this.props = props;
        this.clock = clock;
        this.zone = businessZone;
    }

    /** Who books what, on the n-th working day from tomorrow (0, 1, 2). */
    enum Owner { ADMIN, DEMO_USER }

    record Planned(int workingDay, String room, Owner owner, String title, String start, String end, int attendees) {
    }

    static final List<Planned> PLAN = List.of(
            new Planned(0, "Ha Long", Owner.DEMO_USER, "Daily stand-up", "09:00", "09:15", 8),
            new Planned(0, "Ha Long", Owner.ADMIN, "Sprint planning", "09:30", "11:00", 10),
            new Planned(0, "Binh Minh", Owner.DEMO_USER, "1:1 with manager", "10:00", "10:30", 2),
            new Planned(0, "Sa Pa", Owner.ADMIN, "Client demo", "14:00", "15:30", 12),
            new Planned(1, "Ha Long", Owner.DEMO_USER, "Daily stand-up", "09:00", "09:15", 8),
            new Planned(1, "Mekong", Owner.ADMIN, "All-hands meeting", "15:00", "16:30", 30),
            new Planned(1, "Sao Mai", Owner.DEMO_USER, "Interview: backend developer", "10:00", "11:00", 3),
            new Planned(2, "Ha Long", Owner.DEMO_USER, "Daily stand-up", "09:00", "09:15", 8),
            new Planned(2, "Hoi An", Owner.DEMO_USER, "Sprint retrospective", "16:00", "17:00", 7));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bookings.count() > 0) {
            return;
        }
        Optional<User> admin = users.findByEmail(User.normalizeEmail(accounts.admin().email()));
        Optional<User> demoUser = Optional.ofNullable(accounts.demoUser())
                .flatMap(account -> users.findByEmail(User.normalizeEmail(account.email())));
        if (admin.isEmpty() || demoUser.isEmpty()) {
            log.warn("Demo bookings skipped: bootstrap admin or demo user not found");
            return;
        }

        List<LocalDate> days = nextWorkingDays(LocalDate.now(clock.withZone(zone)), 3);
        int created = 0;
        for (Planned p : PLAN) {
            Optional<Room> room = rooms.findByName(p.room());
            if (room.isEmpty()) {
                continue;
            }
            Instant start = at(days.get(p.workingDay()), p.start());
            Instant end = at(days.get(p.workingDay()), p.end());
            try {
                policy.check(room.get(), start, end, p.attendees());
            } catch (BusinessRuleException ex) {
                // e.g. working hours changed in the config: skip that meeting rather than fail startup
                log.warn("Demo booking '{}' skipped: {}", p.title(), ex.getMessage());
                continue;
            }
            User owner = p.owner() == Owner.ADMIN ? admin.get() : demoUser.get();
            bookings.save(new Booking(room.get(), owner, p.title(), "Demo data", p.attendees(), start, end));
            created++;
        }
        log.info("Demo: created {} bookings on {}", created, days);
    }

    /** The next {@code count} working days strictly after {@code today}, so no demo slot is already past. */
    List<LocalDate> nextWorkingDays(LocalDate today, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today.plusDays(1); days.size() < count; d = d.plusDays(1)) {
            if (props.workingDays().contains(d.getDayOfWeek())) {
                days.add(d);
            }
        }
        return days;
    }

    private Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(zone).toInstant();
    }
}
