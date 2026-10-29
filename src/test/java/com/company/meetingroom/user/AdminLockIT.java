package com.company.meetingroom.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.meetingroom.support.AbstractIntegrationTest;

/**
 * The "keep at least one active admin" check (U5) relies on {@code lockActiveByRole} really taking
 * row locks in PostgreSQL. While one transaction holds them, another transaction cannot lock the
 * same admin row; once it ends, the row is free again.
 */
class AdminLockIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void lockActiveByRole_blocksOtherTransactionsUntilCommit() throws Exception {
        long adminId = users.save(new User("lock-it-" + UUID.randomUUID() + "@test.local", "x", "Lock", Role.ADMIN))
                .getId();
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(users.lockActiveByRole(Role.ADMIN)).extracting(User::getId).contains(adminId);
            locked.complete(null);
            release.join();
        }));
        locked.get(10, TimeUnit.SECONDS);

        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", adminId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("could not obtain lock");
        } finally {
            release.complete(null);
            holder.get(10, TimeUnit.SECONDS);
        }

        tx.executeWithoutResult(status ->
                assertThat(jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE NOWAIT", adminId)).hasSize(1));
    }
}
