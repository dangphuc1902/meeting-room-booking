package com.company.meetingroom.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.common.error.InvalidRequestException;
import com.company.meetingroom.room.Equipment;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.room.dto.RoomResponse;

/** Read side: which rooms are free, and who holds which room when. */
@Service
public class ScheduleService {

    private final RoomRepository rooms;
    private final BookingProperties props;

    public ScheduleService(RoomRepository rooms, BookingProperties props) {
        this.rooms = rooms;
        this.props = props;
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
