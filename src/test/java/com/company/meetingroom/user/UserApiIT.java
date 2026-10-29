package com.company.meetingroom.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesRegex;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Account management through HTTP, checked against the real login endpoint:
 * a created account can log in, a deactivated one cannot, password changes take effect.
 */
@AutoConfigureMockMvc
class UserApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokenService;

    User admin;
    String adminToken;
    /** Unique per test, put in names and emails so searches only see this test's accounts. */
    String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        admin = users.save(new User("admin-" + tag + "@test.local", "x", "Admin " + tag, Role.ADMIN));
        adminToken = tokenService.issue(admin).value();
    }

    @Test
    void admin_createsAccount_thatCanLogIn() throws Exception {
        send(adminToken, post("/api/v1/users"), """
                {"email":"New.Hire-%s@Company.Local","fullName":"New Hire","role":"USER","password":"Welcome@123"}
                """.formatted(tag))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex(".*/api/v1/users/\\d+")))
                .andExpect(jsonPath("$.email").value("new.hire-" + tag + "@company.local"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        login("new.hire-" + tag + "@company.local", "Welcome@123").andExpect(status().isOk());
    }

    @Test
    void duplicateEmail_ignoringCase_returns409() throws Exception {
        create("dup", "Welcome@123");

        send(adminToken, post("/api/v1/users"), """
                {"email":"DUP-%s@TEST.LOCAL","fullName":"Again","role":"USER","password":"Welcome@123"}
                """.formatted(tag))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/email-taken")));
    }

    @Test
    void invalidBody_returns400WithFieldErrors() throws Exception {
        send(adminToken, post("/api/v1/users"), """
                {"email":"not-an-email","fullName":"","role":"USER","password":"short"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        org.hamcrest.Matchers.containsInAnyOrder("email", "fullName", "password")));
    }

    @Test
    void regularUser_cannotManageAccounts_butCanChangeOwnPassword() throws Exception {
        long id = create("plain", "Welcome@123");
        String userToken = tokenService.issue(users.findById(id).orElseThrow()).value();

        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        send(userToken, post("/api/v1/users"), """
                {"email":"x-%s@test.local","fullName":"X","role":"ADMIN","password":"Welcome@123"}
                """.formatted(tag))
                .andExpect(status().isForbidden());

        send(userToken, put("/api/v1/users/me/password"), """
                {"currentPassword":"wrong-one","newPassword":"Changed@456"}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/wrong-current-password")));
        send(userToken, put("/api/v1/users/me/password"), """
                {"currentPassword":"Welcome@123","newPassword":"Changed@456"}
                """)
                .andExpect(status().isNoContent());

        login(email("plain"), "Welcome@123").andExpect(status().isUnauthorized());
        login(email("plain"), "Changed@456").andExpect(status().isOk());
    }

    @Test
    void search_filtersByTextRoleAndStatus_andIsPaged() throws Exception {
        create("anna", "Welcome@123");
        long bob = create("bob", "Welcome@123");
        send(adminToken, patch("/api/v1/users/{id}/status", bob), """
                {"active":false}
                """).andExpect(status().isOk());

        search("?q=" + tag.toUpperCase() + "&role=USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName", contains("anna " + tag, "bob " + tag)))
                .andExpect(jsonPath("$.totalElements").value(2));
        search("?q=" + tag + "&active=false")
                .andExpect(jsonPath("$.content[*].fullName", contains("bob " + tag)));
        search("?q=" + tag + "&size=1&page=1")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(3));
        search("?q=%25").andExpect(status().isOk());
    }

    @Test
    void update_changesNameAndRole() throws Exception {
        long id = create("promo", "Welcome@123");

        send(adminToken, put("/api/v1/users/{id}", id), """
                {"fullName":"  Promoted Person ","role":"ADMIN"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Promoted Person"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.email").value(email("promo")));
    }

    @Test
    void deactivatedUser_cannotLogIn_untilReactivated() throws Exception {
        long id = create("leaver", "Welcome@123");

        send(adminToken, patch("/api/v1/users/{id}/status", id), """
                {"active":false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        login(email("leaver"), "Welcome@123").andExpect(status().isUnauthorized());

        send(adminToken, patch("/api/v1/users/{id}/status", id), """
                {"active":true}
                """).andExpect(status().isOk());
        login(email("leaver"), "Welcome@123").andExpect(status().isOk());
    }

    @Test
    void admin_cannotLockThemselvesOut() throws Exception {
        send(adminToken, patch("/api/v1/users/{id}/status", admin.getId()), """
                {"active":false}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("/self-lockout")));
        send(adminToken, put("/api/v1/users/{id}", admin.getId()), """
                {"fullName":"Me","role":"USER"}
                """)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deactivatedAdmin_withStillValidToken_losesAdminRights_andCannotReactivateThemselves() throws Exception {
        User other = users.save(new User("other-admin-" + tag + "@test.local", "x", "Other " + tag, Role.ADMIN));
        String otherToken = tokenService.issue(other).value();
        long victim = create("victim", "Welcome@123");
        send(adminToken, patch("/api/v1/users/{id}/status", other.getId()), """
                {"active":false}
                """).andExpect(status().isOk());

        // The token still carries ADMIN and has not expired, but the account behind it is deactivated.
        send(otherToken, patch("/api/v1/users/{id}/status", other.getId()), """
                {"active":true}
                """)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        send(otherToken, put("/api/v1/users/{id}/password", victim), """
                {"newPassword":"Hijack@123"}
                """).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        assertThat(users.findById(other.getId()).orElseThrow().isActive()).isFalse();
        login(email("victim"), "Welcome@123").andExpect(status().isOk());
    }

    @Test
    void demotedAdmin_withStillValidToken_losesAdminRights() throws Exception {
        User other = users.save(new User("demoted-" + tag + "@test.local", "x", "Demoted " + tag, Role.ADMIN));
        String otherToken = tokenService.issue(other).value();
        send(adminToken, put("/api/v1/users/{id}", other.getId()), """
                {"fullName":"Demoted %s","role":"USER"}
                """.formatted(tag)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
        send(otherToken, put("/api/v1/users/{id}", other.getId()), """
                {"fullName":"Demoted %s","role":"ADMIN"}
                """.formatted(tag)).andExpect(status().isForbidden());
        send(otherToken, post("/api/v1/rooms"), """
                {"name":"Rogue-%s","location":"B1","capacity":4,"equipment":[]}
                """.formatted(tag)).andExpect(status().isForbidden());

        assertThat(users.findById(other.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void adminResetPassword_replacesTheOldOne() throws Exception {
        long id = create("forgot", "Welcome@123");

        send(adminToken, put("/api/v1/users/{id}/password", id), """
                {"newPassword":"Reset@7890"}
                """)
                .andExpect(status().isNoContent());

        login(email("forgot"), "Welcome@123").andExpect(status().isUnauthorized());
        login(email("forgot"), "Reset@7890").andExpect(status().isOk());
    }

    @Test
    void unknownUser_returns404() throws Exception {
        mvc.perform(get("/api/v1/users/{id}", Long.MAX_VALUE).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private long create(String name, String password) throws Exception {
        String body = send(adminToken, post("/api/v1/users"), """
                {"email":"%s","fullName":"%s %s","role":"USER","password":"%s"}
                """.formatted(email(name), name, tag, password))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private String email(String name) {
        return name + "-" + tag + "@test.local";
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password)));
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/v1/users" + query).header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
