package com.company.meetingroom.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.company.meetingroom.common.error.ConflictException;
import com.company.meetingroom.common.error.PgErrors;
import com.company.meetingroom.room.dto.RoomRequest;

/** Paths that are hard to reach through HTTP: losing the race on a unique room name. */
class RoomServiceTest {

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final RoomService service = new RoomService(rooms);

    @Test
    void concurrentCreateWithSameName_mapsUniqueViolationTo409() {
        when(rooms.existsByNameIgnoreCase("Sao Mai")).thenReturn(false);
        when(rooms.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new SQLException("duplicate key", PgErrors.UNIQUE_VIOLATION)));

        assertThatThrownBy(() -> service.create(new RoomRequest("Sao Mai", null, 4, null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo("room-name-taken"));
    }

    @Test
    void otherIntegrityViolations_areNotDisguisedAsNameConflicts() {
        when(rooms.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check",
                new SQLException("check violation", "23514")));

        assertThatThrownBy(() -> service.create(new RoomRequest("Sao Mai", null, 4, Set.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
