package com.company.meetingroom.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.DaySchedule;
import com.company.meetingroom.booking.dto.RoomSchedule;
import com.company.meetingroom.booking.dto.RoomSchedule.RoomSummary;
import com.company.meetingroom.booking.dto.ScheduleEntry;
import com.company.meetingroom.common.error.InvalidRequestException;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.room.Equipment;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.room.dto.RoomResponse;

/** Read side: which rooms are free, and who holds which room when. */
@Service
public class ScheduleService {

    /** Default length of a room schedule when {@code to} is omitted: one week. */
    private static final int DEFAULT_DAYS = 7;

    private final RoomRepository rooms;
    private final BookingRepository bookings;
    private final BookingProperties props;
    private final Clock clock;
    private final ZoneId zone;

    public ScheduleService(RoomRepository rooms, BookingRepository bookings, BookingProperties props, Clock clock,
            ZoneId businessZone) {
        this.rooms = rooms;
        this.bookings = bookings;
        this.props = props;
        this.clock = clock;
        this.zone = businessZone;
    }

    /**
     * Rooms that could take a booking for [start, end). Booking rules such as working hours are
     * not applied here; they are checked, with a precise message, when the booking is made.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> findAvailable(Instant start, Instant end, int minCapacity, Set<Equipment> equipment) {
        requireRange(start, end);
        // At most ~10 rooms, so the equipment filter runs in memory instead of in SQL.
        return rooms.findAvailable(Math.max(minCapacity, 1), start, end).stream()
                .filter(room -> room.hasAllEquipment(equipment))
                .map(RoomResponse::of)
                .toList();
    }

    /** Days are business-zone dates, both inclusive; defaults to the week starting today. */
    @Transactional(readOnly = true)
    public RoomSchedule roomSchedule(AuthUser caller, Long roomId, LocalDate from, LocalDate to) {
        Room room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("Room", roomId));
        LocalDate first = from != null ? from : today();
        LocalDate last = to != null ? to : first.plusDays(DEFAULT_DAYS - 1);
        Instant start = startOf(first);
        Instant end = startOf(last.plusDays(1));
        requireRange(start, end);

        List<ScheduleEntry> entries = bookings.findOverlapping(roomId, start, end).stream()
                .map(b -> entry(caller, b))
                .toList();
        return new RoomSchedule(RoomSummary.of(room), first, last, zone.getId(), entries);
    }

    /** Every active room for one day, so clients can draw a full grid; inactive rooms only if still booked. */
    @Transactional(readOnly = true)
    public DaySchedule daySchedule(AuthUser caller, LocalDate date) {
        LocalDate day = date != null ? date : today();
        Map<Long, List<ScheduleEntry>> byRoom = bookings.findAllOverlapping(startOf(day), startOf(day.plusDays(1)))
                .stream()
                .collect(Collectors.groupingBy(b -> b.getRoom().getId(),
                        Collectors.mapping(b -> entry(caller, b), Collectors.toList())));

        List<DaySchedule.RoomDay> roomDays = rooms.findAllByOrderByNameAsc().stream()
                .filter(room -> room.isActive() || byRoom.containsKey(room.getId()))
                .map(room -> new DaySchedule.RoomDay(RoomSummary.of(room), byRoom.getOrDefault(room.getId(), List.of())))
                .toList();
        return new DaySchedule(day, zone.getId(), roomDays);
    }

    private ScheduleEntry entry(AuthUser caller, Booking booking) {
        return ScheduleEntry.of(booking, zone, caller.isAdmin() || booking.isOwnedBy(caller.id()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    private void requireRange(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new InvalidRequestException("End must be after start");
        }
        if (Duration.between(from, to).compareTo(props.maxScheduleRange()) > 0) {
            throw new InvalidRequestException("The range can span at most %d days"
                    .formatted(props.maxScheduleRange().toDays()));
        }
    }
}
