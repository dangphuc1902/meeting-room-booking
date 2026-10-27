package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.company.meetingroom.auth.TokenService;
import com.company.meetingroom.room.Equipment;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.support.AbstractIntegrationTest;
import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GET /rooms/availability. Other test classes share the database and leave free rooms behind,
 * so assertions only look at the rooms created here (by unique name), never at the total.
 */
@AutoConfigureMockMvc
class AvailabilityIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(2031, 3, 3);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    RoomRepository rooms;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    String token;
    long userId;
    String small;
    String large;
    String booked;
    String inactive;

    @BeforeEach
    void setUp() {
        User user = users.save(new User("avail-it-" + UUID.randomUUID() + "@test.local", "x", "Avail", Role.USER));
        userId = user.getId();
        token = tokenService.issue(user).value();

        small = room(4, Set.of(Equipment.PROJECTOR), true);
        large = room(8, Set.of(Equipment.PROJECTOR, Equipment.VIDEO_CONFERENCE), true);
        booked = room(6, Set.of(Equipment.PROJECTOR), true);
        inactive = room(10, Set.of(Equipment.PROJECTOR), false);
        insertBooking(booked, "09:00", "10:00", "CONFIRMED");
    }

    @Test
    void returnsActiveFreeRooms_only() throws Exception {
        search("09:00", "10:00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems(small, large)))
                .andExpect(jsonPath("$[*].name", not(hasItem(booked))))
                .andExpect(jsonPath("$[*].name", not(hasItem(inactive))));
    }

    @Test
    void partialOverlap_blocks_butBackToBack_doesNot() throws Exception {
        search("09:45", "10:15", null).andExpect(jsonPath("$[*].name", not(hasItem(booked))));
        search("10:00", "11:00", null).andExpect(jsonPath("$[*].name", hasItem(booked)));
        search("08:00", "09:00", null).andExpect(jsonPath("$[*].name", hasItem(booked)));
    }

    @Test
    void cancelledBooking_doesNotBlock() throws Exception {
        String freed = room(6, Set.of(), true);
        insertBooking(freed, "09:00", "10:00", "CANCELLED");

        search("09:00", "10:00", null).andExpect(jsonPath("$[*].name", hasItem(freed)));
    }

    @Test
    void filtersByCapacityAndEquipment() throws Exception {
        search("09:00", "10:00", "&minCapacity=5")
                .andExpect(jsonPath("$[*].name", hasItem(large)))
                .andExpect(jsonPath("$[*].name", not(hasItem(small))));

        search("09:00", "10:00", "&equipment=PROJECTOR&equipment=VIDEO_CONFERENCE")
                .andExpect(jsonPath("$[*].name", hasItem(large)))
                .andExpect(jsonPath("$[*].name", not(hasItem(small))));
    }

    @Test
    void smallestFittingRoomComesFirst() throws Exception {
        String body = search("09:00", "10:00", null).andReturn().getResponse().getContentAsString();
        List<String> names = new ArrayList<>();
        for (JsonNode room : json.readTree(body)) {
            names.add(room.get("name").asText());
        }
        assertThat(names.indexOf(small)).isNotNegative().isLessThan(names.indexOf(large));
    }

    @Test
    void invalidParameters_return400Problem() throws Exception {
        search("10:00", "09:00", null)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", endsWith("/invalid-parameter")));

        mvc.perform(get(URI.create("/api/v1/rooms/availability?start=%s&end=%s".formatted(
                        encoded(DAY, "09:00"), encoded(DAY.plusDays(40), "09:00"))))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        search("09:00", "10:00", "&equipment=COFFEE_MACHINE").andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/rooms/availability").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/rooms/availability")).andExpect(status().isUnauthorized());
    }

    /** Builds the URL by hand, the way a client would: '+' of the offset sent as %2B. */
    private ResultActions search(String start, String end, String extra) throws Exception {
        String url = "/api/v1/rooms/availability?start=%s&end=%s%s"
                .formatted(encoded(DAY, start), encoded(DAY, end), extra == null ? "" : extra);
        return mvc.perform(get(URI.create(url)).header("Authorization", "Bearer " + token));
    }

    private static String encoded(LocalDate day, String time) {
        return at(day, time).toString().replace("+", "%2B");
    }

    private static OffsetDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime();
    }

    private String room(int capacity, Set<Equipment> equipment, boolean active) {
        Room room = new Room("Avail-IT-" + UUID.randomUUID().toString().substring(0, 8), "Floor 2", capacity,
                equipment);
        if (!active) {
            room.deactivate();
        }
        return rooms.save(room).getName();
    }

    private void insertBooking(String roomName, String start, String end, String status) {
        jdbc.update("""
                INSERT INTO bookings (room_id, user_id, title, start_time, end_time, status, cancelled_at)
                SELECT id, ?, 'Existing', ?, ?, ?, CASE WHEN ? = 'CANCELLED' THEN now() END
                FROM rooms WHERE name = ?
                """, userId, Timestamp.from(at(DAY, start).toInstant()), Timestamp.from(at(DAY, end).toInstant()),
                status, status, roomName);
    }
}
