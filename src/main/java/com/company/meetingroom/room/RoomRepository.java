package com.company.meetingroom.room;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    boolean existsByNameIgnoreCase(String name);
}
