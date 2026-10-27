package com.company.meetingroom.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.auth.TokenService;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.room.Room;
import com.company.meetingroom.room.RoomRepository;
import com.company.meetingroom.support.AbstractIntegrationTest;
import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Booking lifecycle through HTTP against real PostgreSQL. Uses the real clock, so slots are
 * computed relative to today: {@link #day} is the next working day at least two days ahead.
 * Every test books its own fresh room, so tests never interfere with each other.
 */
@AutoConfigureMockMvc
class BookingApiIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    RoomRepository rooms;

    @Autowired
    TokenService tokenService;

    @Autowired
    BookingService bookingService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    LocalDate day;
    long roomId;
    User alice;
    String aliceToken;
    String bobToken;
    String adminToken;

    @BeforeEach
    void setUp() {
        day = LocalDate.now(clock.withZone(ZONE)).plusDays(2);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        roomId = rooms.save(new Room("Booking-IT-" + UUID.randomUUID().toString().substring(0, 8),
                "Floor 9", 6, Set.of())).getId();
        alice = newUser(Role.USER, "Alice");
        aliceToken = token(alice);
        bobToken = token(newUser(Role.USER, "Bob"));
        adminToken = token(newUser(Role.ADMIN, "Admin"));
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_returns201_withTimesInBusinessZone() throws Exception {
        create(aliceToken, "09:00", "10:00", 4)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex(".*/api/v1/bookings/\\d+")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.room.id").value(roomId))
                .andExpect(jsonPath("$.owner.fullName").value("Alice"))
                .andExpect(jsonPath("$.startTime", startsWith(day + "T09:00")))
                .andExpect(jsonPath("$.startTime", endsWith("+07:00")))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void overlappingBooking_returns409_namingWhoHoldsTheSlot() throws Exception {
        create(aliceToken, "09:00", "10:00", 2).andExpect(status().isCreated());

        create(bobToken, "09:30", "10:30", 2)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", endsWith("/booking-overlap")))
                .andExpect(jsonPath("$.conflicts[0].bookedBy").value("Alice"))
                .andExpect(jsonPath("$.conflicts[0].startTime", startsWith(day + "T09:00")));
    }

    @Test
    void backToBackBookings_areAllowed() throws Exception {
        create(aliceToken, "09:00", "10:00", 2).andExpect(status().isCreated());
        create(bobToken, "10:00", "11:00", 2).andExpect(status().isCreated());
        create(bobToken, "08:00", "09:00", 2).andExpect(status().isCreated());
    }

    @Test
    void brokenBusinessRule_returns422_withRuleNumber() throws Exception {
        create(aliceToken, "09:00", "10:00", 7)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/over-capacity")))
                .andExpect(jsonPath("$.rule").value("R8"));

        create(aliceToken, "19:30", "20:30", 2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rule").value("R6"));
    }

    @Test
    void timeWithoutOffset_returns400() throws Exception {
        send(aliceToken, post("/api/v1/bookings"), """
                {"roomId":%d,"title":"Sync","attendeesCount":2,
                 "startTime":"%sT09:00:00","endTime":"%sT10:00:00"}
                """.formatted(roomId, day, day))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void missingFields_return400WithFieldErrors() throws Exception {
        send(aliceToken, post("/api/v1/bookings"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unknownRoom_returns404() throws Exception {
        send(aliceToken, post("/api/v1/bookings"), body(Long.MAX_VALUE, "09:00", "10:00", 2, null))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentRequestsForSameSlot_exactlyOneWins() throws Exception {
        int threads = 10;
        AuthUser caller = new AuthUser(alice.getId(), alice.getEmail(), Role.USER);
        BookingRequest request = new BookingRequest(roomId, "Race", null, 2, at("14:00"), at("15:00"));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        bookingService.create(caller, request);
                        return true;
                    } catch (ConflictException ex) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : results) {
                wins += f.get() ? 1 : 0;
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE room_id = ? AND status = 'CONFIRMED'",
                Integer.class, roomId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- read / permissions

    @Test
    void onlyOwnerAndAdmin_canReadABooking() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");

        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/bookings/{id}", Long.MAX_VALUE).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancel_freesTheSlotImmediately_andCannotBeRepeated() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");

        send(aliceToken, post("/api/v1/bookings/{id}/cancel", id), """
                {"reason":"  Moved online "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Moved online"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        create(bobToken, "09:00", "10:00", 2).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/booking-cancelled")));
    }

    @Test
    void cancel_byOtherUser_is403_byAdmin_isAllowed_withoutBody() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_endedBooking_returns422() throws Exception {
        Instant yesterday = Instant.now(clock).minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        long id = insertDirectly(yesterday, yesterday.plus(Duration.ofHours(1)));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/booking-ended")))
                .andExpect(jsonPath("$.rule").value("R9"));
    }

    // ---------------------------------------------------------------- update

    @Test
    void update_movesBooking_andBumpsVersion() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");

        // Overlapping its own old slot is fine: the booking is not compared with itself.
        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("09:30", "10:30", 3, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime", startsWith(day + "T09:30")))
                .andExpect(jsonPath("$.attendeesCount").value(3))
                .andExpect(jsonPath("$.version").value(1));

        // The old 09:00-09:30 part is free again.
        create(bobToken, "09:00", "09:30", 2).andExpect(status().isCreated());
    }

    @Test
    void update_withStaleVersion_returns409() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");
        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("11:00", "12:00", 2, 0))
                .andExpect(status().isOk());

        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("13:00", "14:00", 2, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/concurrent-modification")));
    }

    @Test
    void update_ontoAnotherBooking_returns409_andBreakingARule_returns422() throws Exception {
        createdId(bobToken, "11:00", "12:00");
        long id = createdId(aliceToken, "09:00", "10:00");

        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("11:30", "12:30", 2, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/booking-overlap")));
        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("09:10", "10:00", 2, 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rule").value("R5"));
    }

    @Test
    void update_byOtherUser_is403() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");

        send(bobToken, put("/api/v1/bookings/{id}", id), updateBody("11:00", "12:00", 2, 0))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_startedBooking_returns422() throws Exception {
        Instant now = Instant.now(clock);
        long id = insertDirectly(now.minus(Duration.ofMinutes(30)), now.plus(Duration.ofMinutes(30)));

        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("11:00", "12:00", 2, 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/booking-started")));
    }

    @Test
    void update_cancelledBooking_returns422() throws Exception {
        long id = createdId(aliceToken, "09:00", "10:00");
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        send(aliceToken, put("/api/v1/bookings/{id}", id), updateBody("11:00", "12:00", 2, 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/booking-cancelled")));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions create(String token, String start, String end, int attendees) throws Exception {
        return send(token, post("/api/v1/bookings"), body(roomId, start, end, attendees, null));
    }

    private long createdId(String token, String start, String end) throws Exception {
        String response = create(token, start, end, 2)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("id").asLong();
    }

    private String body(long room, String start, String end, int attendees, Integer version) {
        return """
                {"roomId":%d,"title":"Team sync","attendeesCount":%d,"startTime":"%s","endTime":"%s"%s}
                """.formatted(room, attendees, at(start), at(end), version == null ? "" : ",\"version\":" + version);
    }

    private String updateBody(String start, String end, int attendees, int version) {
        return body(roomId, start, end, attendees, version);
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private OffsetDateTime at(String time) {
        return day.atTime(LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime();
    }

    /** Bypasses the API (and its rules) to create bookings in the past or already running. */
    private long insertDirectly(Instant start, Instant end) {
        return jdbc.queryForObject("""
                INSERT INTO bookings (room_id, user_id, title, attendees_count, start_time, end_time)
                VALUES (?, ?, 'Legacy', 2, ?, ?) RETURNING id
                """, Long.class, roomId, alice.getId(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
    }

    private User newUser(Role role, String name) {
        return users.save(new User("booking-it-" + UUID.randomUUID() + "@test.local", "x", name, role));
    }

    private String token(User user) {
        return tokenService.issue(user).value();
    }
}
