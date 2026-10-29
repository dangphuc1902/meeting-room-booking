package com.company.meetingroom.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.support.AbstractIntegrationTest;

/**
 * Bypasses the service pre-check: the database itself must reject a room name that differs
 * only in case, with SQLSTATE 23505 so RoomService still maps it to 409.
 */
class RoomNameConstraintIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void nameDifferingOnlyInCase_isRejectedByTheDatabase() {
        String name = "Ci-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        jdbc.update("INSERT INTO rooms (name, capacity) VALUES (?, 4)", name);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO rooms (name, capacity) VALUES (?, 4)", name.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(PgErrors.isUniqueViolation(ex)).isTrue());
    }
}
