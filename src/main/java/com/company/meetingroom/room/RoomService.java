package com.company.meetingroom.room;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.NotFoundException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.room.dto.RoomRequest;
import com.company.meetingroom.room.dto.RoomResponse;

/**
 * Room management. Rooms are never hard-deleted (bookings reference them):
 * an admin deactivates a room instead, after which it takes no new bookings (R11).
 */
@Service
public class RoomService {

    private final RoomRepository rooms;

    public RoomService(RoomRepository rooms) {
        this.rooms = rooms;
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> list(boolean includeInactive) {
        List<Room> result = includeInactive ? rooms.findAllByOrderByNameAsc() : rooms.findByActiveTrueOrderByNameAsc();
        return result.stream().map(RoomResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public RoomResponse get(Long id) {
        return RoomResponse.of(find(id));
    }

    @Transactional
    public RoomResponse create(RoomRequest request) {
        String name = request.name().strip();
        if (rooms.existsByNameIgnoreCase(name)) {
            throw nameTaken(name);
        }
        Room room = new Room(name, normalizeLocation(request.location()), request.capacity(), request.equipment());
        return RoomResponse.of(saveAndFlush(room, name));
    }

    @Transactional
    public RoomResponse update(Long id, RoomRequest request) {
        Room room = find(id);
        String name = request.name().strip();
        if (rooms.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameTaken(name);
        }
        room.update(name, normalizeLocation(request.location()), request.capacity(), request.equipment());
        return RoomResponse.of(saveAndFlush(room, name));
    }

    @Transactional
    public RoomResponse changeStatus(Long id, boolean active) {
        Room room = find(id);
        if (active) {
            room.activate();
        } else {
            room.deactivate();
        }
        return RoomResponse.of(rooms.saveAndFlush(room));
    }

    private Room find(Long id) {
        return rooms.findById(id).orElseThrow(() -> new NotFoundException("Room", id));
    }

    /** The pre-check gives a friendly error; the unique constraint still catches two concurrent requests. */
    private Room saveAndFlush(Room room, String name) {
        try {
            return rooms.saveAndFlush(room);
        } catch (DataIntegrityViolationException ex) {
            if (PgErrors.isUniqueViolation(ex)) {
                throw nameTaken(name);
            }
            throw ex;
        }
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException("room-name-taken", "Room name already in use",
                "A room named '%s' already exists".formatted(name));
    }

    private static String normalizeLocation(String location) {
        return location == null || location.isBlank() ? null : location.strip();
    }
}
