-- Needed so a scalar column (room_id) can take part in a GiST exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_users_email_lower CHECK (email = lower(email))
);

-- ---------------------------------------------------------------------------
-- Rooms
-- ---------------------------------------------------------------------------
CREATE TABLE rooms (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    location    VARCHAR(255),
    capacity    INT          NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_rooms_name UNIQUE (name),
    CONSTRAINT chk_rooms_capacity CHECK (capacity > 0)
);

CREATE TABLE room_equipment (
    room_id   BIGINT      NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    equipment VARCHAR(30) NOT NULL,
    PRIMARY KEY (room_id, equipment),
    CONSTRAINT chk_room_equipment CHECK (equipment IN ('PROJECTOR', 'TV', 'WHITEBOARD', 'VIDEO_CONFERENCE'))
);

-- ---------------------------------------------------------------------------
-- Bookings
-- ---------------------------------------------------------------------------
CREATE TABLE bookings (
    id              BIGSERIAL    PRIMARY KEY,
    room_id         BIGINT       NOT NULL REFERENCES rooms (id),
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    attendees_count INT          NOT NULL DEFAULT 1,
    start_time      TIMESTAMPTZ  NOT NULL,
    end_time        TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMED',
    cancelled_by    BIGINT       REFERENCES users (id),
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   VARCHAR(500),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_bookings_time      CHECK (end_time > start_time),
    CONSTRAINT chk_bookings_attendees CHECK (attendees_count > 0),
    CONSTRAINT chk_bookings_status    CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_bookings_cancel    CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status = 'CONFIRMED' AND cancelled_at IS NULL)
    ),

    -- The core invariant: two CONFIRMED bookings of the same room never overlap.
    -- Half-open range [start, end) so back-to-back meetings (09:00-10:00, 10:00-11:00) are allowed.
    -- Enforced by the database, so it also holds under concurrent requests and multiple app instances.
    CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);

-- The GiST index behind the constraint already serves "bookings of room X overlapping [a, b)".
CREATE INDEX idx_bookings_user_start ON bookings (user_id, start_time);
CREATE INDEX idx_bookings_start      ON bookings (start_time);
