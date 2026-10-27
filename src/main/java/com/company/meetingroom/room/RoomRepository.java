package com.company.meetingroom.room;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @EntityGraph(attributePaths = "equipment")
    List<Room> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "equipment")
    List<Room> findByActiveTrueOrderByNameAsc();
}
