package com.company.meetingroom.room;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.company.meetingroom.auth.TokenService;
import com.company.meetingroom.support.AbstractIntegrationTest;
import com.company.meetingroom.user.Role;
import com.company.meetingroom.user.User;
import com.company.meetingroom.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Room CRUD through the HTTP layer: permissions, validation, duplicate names, deactivation.
 * The database is shared with other test classes, so every room gets a unique name
 * and assertions never rely on the total number of rooms.
 */
@AutoConfigureMockMvc
class RoomApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokenService;

    String adminToken;
    String userToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenFor(Role.ADMIN);
        userToken = tokenFor(Role.USER);
    }

    @Test
    void admin_createsRoom_returns201WithLocation() throws Exception {
        String name = uniqueName("Create");

        as(adminToken, post("/api/v1/rooms"), """
                {"name":"  %s ","location":"Floor 7","capacity":8,"equipment":["PROJECTOR","WHITEBOARD"]}
                """.formatted(name))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex(".*/api/v1/rooms/\\d+")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.location").value("Floor 7"))
                .andExpect(jsonPath("$.capacity").value(8))
                .andExpect(jsonPath("$.equipment", containsInAnyOrder("PROJECTOR", "WHITEBOARD")))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void user_canReadRooms_butNotCreate() throws Exception {
        long id = createRoom(uniqueName("Read"), 4);

        mvc.perform(get("/api/v1/rooms/{id}", id).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.equipment").isArray());

        as(userToken, post("/api/v1/rooms"), """
                {"name":"%s","capacity":4}
                """.formatted(uniqueName("Forbidden")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateName_ignoringCase_returns409() throws Exception {
        String name = uniqueName("Dup");
        createRoom(name, 4);

        as(adminToken, post("/api/v1/rooms"), """
                {"name":"%s","capacity":6}
                """.formatted(name.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", endsWith("/room-name-taken")));
    }

    @Test
    void invalidBody_returns400WithFieldErrors() throws Exception {
        as(adminToken, post("/api/v1/rooms"), """
                {"name":" ","capacity":0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "capacity")));
    }

    @Test
    void unknownEquipment_returns400() throws Exception {
        as(adminToken, post("/api/v1/rooms"), """
                {"name":"%s","capacity":4,"equipment":["COFFEE_MACHINE"]}
                """.formatted(uniqueName("Equip")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void admin_updatesRoom_replacingAllFields() throws Exception {
        long id = createRoom(uniqueName("Before"), 4);
        String newName = uniqueName("After");

        as(adminToken, put("/api/v1/rooms/{id}", id), """
                {"name":"%s","capacity":12,"equipment":["TV"]}
                """.formatted(newName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.capacity").value(12))
                .andExpect(jsonPath("$.equipment", containsInAnyOrder("TV")));
    }

    @Test
    void update_keepingOwnName_isAllowed_butTakingAnothersName_returns409() throws Exception {
        String taken = uniqueName("Taken");
        createRoom(taken, 4);
        String own = uniqueName("Own");
        long id = createRoom(own, 4);

        as(adminToken, put("/api/v1/rooms/{id}", id), """
                {"name":"%s","capacity":10}
                """.formatted(own))
                .andExpect(status().isOk());

        as(adminToken, put("/api/v1/rooms/{id}", id), """
                {"name":"%s","capacity":10}
                """.formatted(taken))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownRoom_returns404() throws Exception {
        mvc.perform(get("/api/v1/rooms/{id}", Long.MAX_VALUE).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        as(adminToken, put("/api/v1/rooms/{id}", Long.MAX_VALUE), """
                {"name":"%s","capacity":4}
                """.formatted(uniqueName("Ghost")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivatedRoom_isHiddenFromList_exceptForAdminWithIncludeInactive() throws Exception {
        String name = uniqueName("Inactive");
        long id = createRoom(name, 4);

        as(adminToken, patch("/api/v1/rooms/{id}/status", id), """
                {"active":false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", not(hasItem(name))));
        // A regular user cannot widen the list with the flag.
        mvc.perform(get("/api/v1/rooms?includeInactive=true").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$[*].name", not(hasItem(name))));
        mvc.perform(get("/api/v1/rooms?includeInactive=true").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[*].name", hasItem(name)));

        as(adminToken, patch("/api/v1/rooms/{id}/status", id), """
                {"active":true}
                """)
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$[*].name", hasItem(name)));
    }

    @Test
    void user_cannotChangeStatus() throws Exception {
        long id = createRoom(uniqueName("Status"), 4);

        as(userToken, patch("/api/v1/rooms/{id}/status", id), """
                {"active":false}
                """)
                .andExpect(status().isForbidden());
    }

    private long createRoom(String name, int capacity) throws Exception {
        String body = as(adminToken, post("/api/v1/rooms"), """
                {"name":"%s","capacity":%d}
                """.formatted(name, capacity))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String tokenFor(Role role) {
        User user = users.save(new User("room-it-" + UUID.randomUUID() + "@test.local", "x", "Room IT", role));
        return tokenService.issue(user).value();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
