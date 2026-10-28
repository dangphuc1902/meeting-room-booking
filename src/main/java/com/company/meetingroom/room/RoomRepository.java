package com.company.meetingroom.room;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {

    boolean existsByNameIgnoreCase(String name);

    Optional<Room> findByName(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @EntityGraph(attributePaths = "equipment")
    List<Room> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "equipment")
    List<Room> findByActiveTrueOrderByNameAsc();

    /**
     * Active rooms with enough seats and no CONFIRMED booking overlapping [start, end),
     * smallest first so a team of 3 is not offered the 30-seat hall before a 4-seat room.
     */
    @EntityGraph(attributePaths = "equipment")
    @Query("""
            select r from Room r
            where r.active = true
              and r.capacity >= :minCapacity
              and not exists (
                  select b.id from Booking b
                  where b.room = r
                    and b.status = com.company.meetingroom.booking.BookingStatus.CONFIRMED
                    and b.startTime < :end
                    and b.endTime > :start)
            order by r.capacity, r.name
            """)
    List<Room> findAvailable(@Param("minCapacity") int minCapacity,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
