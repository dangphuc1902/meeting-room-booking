package com.company.meetingroom.booking;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * CONFIRMED bookings of a room that overlap [from, to). Used to return a friendly
     * "who holds this slot" message before insert; the EXCLUDE constraint stays the real guard.
     */
    @Query("""
            select b from Booking b
            join fetch b.owner
            where b.room.id = :roomId
              and b.status = com.company.meetingroom.booking.BookingStatus.CONFIRMED
              and b.startTime < :to
              and b.endTime > :from
            order by b.startTime
            """)
    List<Booking> findOverlapping(@Param("roomId") Long roomId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
