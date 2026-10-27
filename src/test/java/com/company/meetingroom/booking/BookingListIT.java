package com.company.meetingroom.booking;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/** GET /bookings/me and the admin search GET /bookings. Each test gets fresh users and a fresh room. */
@AutoConfigureMockMvc
class BookingListIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(2031, 3, 5);

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
    User bob;
    String aliceToken;
    String adminToken;
    Room room;

    @BeforeEach
    void setUp() {
        alice = user(Role.USER, "Alice");
        bob = user(Role.USER, "Bob");
        aliceToken = tokenService.issue(alice).value();
        adminToken = tokenService.issue(user(Role.ADMIN, "Admin")).value();
        room = rooms.save(new Room("List-IT-" + UUID.randomUUID().toString().substring(0, 8), "Floor 1", 6, Set.of()));

        Instant yesterday = Instant.now(clock).minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        Instant now = Instant.now(clock);
        insert(alice, "Past", yesterday, yesterday.plus(Duration.ofHours(1)), "CONFIRMED");
        insert(alice, "Running", now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(50)), "CONFIRMED");
        insert(alice, "Later", at(DAY.plusDays(1), "09:00"), at(DAY.plusDays(1), "10:00"), "CONFIRMED");
        insert(alice, "Soon", at(DAY, "09:00"), at(DAY, "10:00"), "CONFIRMED");
        insert(alice, "Dropped", at(DAY, "11:00"), at(DAY, "12:00"), "CANCELLED");
        insert(bob, "Bobs", at(DAY, "13:00"), at(DAY, "14:00"), "CONFIRMED");
    }

    @Test
    void mine_defaultsToUpcomingConfirmed_soonestFirst() throws Exception {
        mine("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", contains("Running", "Soon", "Later")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].room.name").value(room.getName()));
    }

    @Test
    void mine_withIncludePast_isMostRecentFirst_andStatusFilters() throws Exception {
        mine("?includePast=true")
                .andExpect(jsonPath("$.content[*].title", contains("Later", "Soon", "Running", "Past")));
        mine("?status=CANCELLED")
                .andExpect(jsonPath("$.content[*].title", contains("Dropped")));
    }

    @Test
    void mine_isPaged() throws Exception {
        mine("?size=2&page=1")
                .andExpect(jsonPath("$.content[*].title", contains("Later")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void invalidPaging_returns400Problem() throws Exception {
        mine("?size=0").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mine("?size=101").andExpect(status().isBadRequest());
        mine("?page=-1").andExpect(status().isBadRequest());
        mine("?status=MAYBE").andExpect(status().isBadRequest());
    }

    @Test
    void adminSearch_filtersByRoomUserStatusAndDays() throws Exception {
        String byRoom = "?roomId=" + room.getId();
        search(adminToken, byRoom + "&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title",
                        contains("Past", "Running", "Soon", "Dropped", "Bobs", "Later")));
        search(adminToken, byRoom + "&userId=" + bob.getId())
                .andExpect(jsonPath("$.content[*].title", contains("Bobs")));
        search(adminToken, byRoom + "&status=CANCELLED")
                .andExpect(jsonPath("$.content[*].title", contains("Dropped")));
        search(adminToken, byRoom + "&from=" + DAY + "&to=" + DAY)
                .andExpect(jsonPath("$.content[*].title", contains("Soon", "Dropped", "Bobs")));
        search(adminToken, byRoom + "&from=" + DAY.plusDays(1))
                .andExpect(jsonPath("$.content[*].title", contains("Later")));
    }

    @Test
    void adminSearch_rejectsReversedDays_andNonAdmins() throws Exception {
        search(adminToken, "?from=" + DAY + "&to=" + DAY.minusDays(1)).andExpect(status().isBadRequest());
        search(aliceToken, "").andExpect(status().isForbidden());
    }

    private ResultActions mine(String query) throws Exception {
        return mvc.perform(get("/api/v1/bookings/me" + query).header("Authorization", "Bearer " + aliceToken));
    }

    private ResultActions search(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/bookings" + query).header("Authorization", "Bearer " + token));
    }

    private void insert(User owner, String title, Instant start, Instant end, String status) {
        jdbc.update("""
                INSERT INTO bookings (room_id, user_id, title, start_time, end_time, status, cancelled_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'CANCELLED' THEN now() END)
                """, room.getId(), owner.getId(), title, Timestamp.from(start), Timestamp.from(end), status, status);
    }

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    private User user(Role role, String name) {
        return users.save(new User("list-it-" + UUID.randomUUID() + "@test.local", "x", name, role));
    }
}
