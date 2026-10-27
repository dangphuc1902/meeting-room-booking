package com.company.meetingroom.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

import com.company.meetingroom.common.error.BusinessRuleException;
import com.company.meetingroom.room.Room;

/**
 * Business rules a booking slot must satisfy before it is written (R2-R8, plus R11 for the room).
 * Applies to both create and update (R10). Overlap (R1) is not checked here: it needs the
 * database and is ultimately enforced by the EXCLUDE constraint.
 * <p>
 * Fails fast on the first broken rule with a 422 whose {@code type} names the rule
 * and whose {@code rule} property gives its number from the README.
 */
@Component
public class BookingPolicy {

    private final BookingProperties props;
    private final Clock clock;
    private final ZoneId zone;

    public BookingPolicy(BookingProperties props, Clock clock, ZoneId businessZone) {
        this.props = props;
        this.clock = clock;
        this.zone = businessZone;
    }

    public void check(Room room, Instant start, Instant end, int attendeesCount) {
        Instant now = clock.instant();

        if (!end.isAfter(start)) {
            throw violation("R3", "invalid-time-range", "End time must be after start time");
        }
        if (start.isBefore(now)) {
            throw violation("R2", "booking-in-past", "Cannot book a slot that starts in the past");
        }
        if (start.isAfter(now.plus(props.maxAdvance()))) {
            throw violation("R7", "too-far-in-advance",
                    "Bookings can be made at most %d days in advance".formatted(props.maxAdvance().toDays()));
        }
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(props.minDuration()) < 0 || duration.compareTo(props.maxDuration()) > 0) {
            throw violation("R4", "invalid-duration", "Duration must be between %d and %d minutes"
                    .formatted(props.minDuration().toMinutes(), props.maxDuration().toMinutes()));
        }

        ZonedDateTime localStart = start.atZone(zone);
        ZonedDateTime localEnd = end.atZone(zone);
        if (!onSlotBoundary(localStart) || !onSlotBoundary(localEnd)) {
            throw violation("R5", "not-on-slot-boundary",
                    "Start and end must be on a %d-minute boundary".formatted(props.slotMinutes()));
        }
        if (!withinWorkingHours(localStart, localEnd)) {
            throw violation("R6", "outside-working-hours", "Bookings must be on %s between %s and %s (%s)"
                    .formatted(props.workingDays(), props.dayStart(), props.dayEnd(), zone));
        }

        if (!room.isActive()) {
            throw violation("R11", "room-inactive", "Room '%s' is not accepting bookings".formatted(room.getName()));
        }
        if (attendeesCount > room.getCapacity()) {
            throw violation("R8", "over-capacity", "Room '%s' holds %d people, %d requested"
                    .formatted(room.getName(), room.getCapacity(), attendeesCount));
        }
    }

    private boolean onSlotBoundary(ZonedDateTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % props.slotMinutes() == 0;
    }

    /** Same local day, a working day, and fully inside [dayStart, dayEnd]. */
    private boolean withinWorkingHours(ZonedDateTime start, ZonedDateTime end) {
        return start.toLocalDate().equals(end.toLocalDate())
                && props.workingDays().contains(start.getDayOfWeek())
                && !start.toLocalTime().isBefore(props.dayStart())
                && !end.toLocalTime().isAfter(props.dayEnd());
    }

    private static BusinessRuleException violation(String rule, String code, String detail) {
        BusinessRuleException ex = new BusinessRuleException(code, detail);
        ex.with("rule", rule);
        return ex;
    }
}
