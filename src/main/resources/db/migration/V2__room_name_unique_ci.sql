-- Room names are unique ignoring case ("Sao Mai" and "SAO MAI" are the same room).
-- The service already checks this; the index makes it hold under concurrent requests too.
ALTER TABLE rooms DROP CONSTRAINT uq_rooms_name;
CREATE UNIQUE INDEX uq_rooms_name_lower ON rooms (lower(name));
