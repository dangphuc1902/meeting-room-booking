package com.company.meetingroom.booking;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.support.AbstractIntegrationTest;
import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;

/**
 * GET /rooms/{id}/schedule and GET /schedule. Bookings are inserted directly so the test controls
 * edge cases the API would refuse (around midnight). Days are Asia/Ho_Chi_Minh dates.
 */
@AutoConfigureMockMvc
class ScheduleIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(2031, 3, 4);

    @Autowired
    MockMvc mvc;

    @Autowired
    RoomRepository rooms;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    User alice;
    String aliceToken;
    String bobToken;
    String adminToken;
    Room room;

    @BeforeEach
    void setUp() {
        alice = user(Role.USER, "Alice");
        aliceToken = tokenService.issue(alice).value();
        bobToken = tokenService.issue(user(Role.USER, "Bob")).value();
        adminToken = tokenService.issue(user(Role.ADMIN, "Admin")).value();
        room = room(true);

        insert(room, DAY, "09:00", DAY, "10:00", "Planning", "CONFIRMED");
        insert(room, DAY, "11:00", DAY, "12:00", "Called off", "CANCELLED");
        insert(room, DAY.plusDays(1), "14:00", DAY.plusDays(1), "15:00", "Retro", "CONFIRMED");
    }

    @Test
    void roomSchedule_listsConfirmedBookingsInOrder_inBusinessZone() throws Exception {
        roomSchedule(aliceToken, "?from=" + DAY + "&to=" + DAY.plusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.name").value(room.getName()))
                .andExpect(jsonPath("$.zone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.bookings[*].title", contains("Planning", "Retro")))
                .andExpect(jsonPath("$.bookings[0].startTime").value(DAY + "T09:00:00+07:00"))
                .andExpect(jsonPath("$.bookings[0].bookedBy.fullName").value("Alice"));
    }

    @Test
    void description_isVisibleToOwnerAndAdmin_only() throws Exception {
        String range = "?from=" + DAY + "&to=" + DAY;
        roomSchedule(aliceToken, range).andExpect(jsonPath("$.bookings[0].description").value("Secret agenda"));
        roomSchedule(adminToken, range).andExpect(jsonPath("$.bookings[0].description").value("Secret agenda"));
        roomSchedule(bobToken, range)
                .andExpect(jsonPath("$.bookings[0].title").value("Planning"))
                .andExpect(jsonPath("$.bookings[0].bookedBy.fullName").value("Alice"))
                .andExpect(jsonPath("$.bookings[0].description").doesNotExist());
    }

    @Test
    void roomSchedule_defaultsToAWeekFromToday() throws Exception {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        roomSchedule(bobToken, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.toString()))
                .andExpect(jsonPath("$.to").value(today.plusDays(6).toString()));
    }

    @Test
    void daySchedule_groupsByRoom_andShowsEmptyActiveRooms() throws Exception {
        Room empty = room(true);
        Room inactiveFree = room(false);
        Room inactiveBooked = room(false);
        insert(inactiveBooked, DAY, "15:00", DAY, "16:00", "Leftover", "CONFIRMED");

        String path = "$.rooms[?(@.room.name == '%s')]";
        mvc.perform(get("/api/v1/schedule?date=" + DAY).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(DAY.toString()))
                .andExpect(jsonPath(path.formatted(room.getName()) + ".bookings[*].title", contains("Planning")))
                .andExpect(jsonPath(path.formatted(empty.getName()) + ".bookings[*]", empty()))
                .andExpect(jsonPath("$.rooms[*].room.name", hasItem(empty.getName())))
                .andExpect(jsonPath("$.rooms[*].room.name", not(hasItem(inactiveFree.getName()))))
                .andExpect(jsonPath(path.formatted(inactiveBooked.getName()) + ".bookings[*].title",
                        contains("Leftover")));
    }

    @Test
    void dayBoundary_isMidnightInBusinessZone_notUtc() throws Exception {
        Room night = room(true);
        // 00:00-01:00 on DAY+1 in Hanoi is 17:00-18:00 UTC on DAY: it belongs to DAY+1.
        insert(night, DAY.plusDays(1), "00:00", DAY.plusDays(1), "01:00", "Night shift", "CONFIRMED");
        // 23:30 on DAY until exactly midnight: half-open, so it belongs to DAY only.
        insert(night, DAY, "23:30", DAY.plusDays(1), "00:00", "Late call", "CONFIRMED");

        String path = "$.rooms[?(@.room.name == '%s')].bookings[*].title".formatted(night.getName());
        mvc.perform(get("/api/v1/schedule?date=" + DAY).header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath(path, contains("Late call")));
        mvc.perform(get("/api/v1/schedule?date=" + DAY.plusDays(1)).header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath(path, contains("Night shift")));
    }

    @Test
    void invalidRanges_return400_andUnknownRoom_returns404() throws Exception {
        roomSchedule(bobToken, "?from=" + DAY + "&to=" + DAY.minusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", endsWith("/invalid-parameter")));
        roomSchedule(bobToken, "?from=" + DAY + "&to=" + DAY.plusDays(31)).andExpect(status().isBadRequest());
        roomSchedule(bobToken, "?from=" + DAY + "&to=" + DAY.plusDays(30)).andExpect(status().isOk());
        roomSchedule(bobToken, "?from=not-a-date").andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/rooms/{id}/schedule", Long.MAX_VALUE).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    private ResultActions roomSchedule(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/rooms/" + room.getId() + "/schedule" + query)
                .header("Authorization", "Bearer " + token));
    }

    private void insert(Room target, LocalDate startDay, String start, LocalDate endDay, String end, String title,
            String status) {
        jdbc.update("""
                INSERT INTO bookings (room_id, user_id, title, description, start_time, end_time, status, cancelled_at)
                VALUES (?, ?, ?, 'Secret agenda', ?, ?, ?, CASE WHEN ? = 'CANCELLED' THEN now() END)
                """, target.getId(), alice.getId(), title,
                Timestamp.from(startDay.atTime(LocalTime.parse(start)).atZone(ZONE).toInstant()),
                Timestamp.from(endDay.atTime(LocalTime.parse(end)).atZone(ZONE).toInstant()),
                status, status);
    }

    private Room room(boolean active) {
        Room r = new Room("Sched-IT-" + UUID.randomUUID().toString().substring(0, 8), "Floor 1", 6, Set.of());
        if (!active) {
            r.deactivate();
        }
        return rooms.save(r);
    }

    private User user(Role role, String name) {
        return users.save(new User("sched-it-" + UUID.randomUUID() + "@test.local", "x", name, role));
    }
}
