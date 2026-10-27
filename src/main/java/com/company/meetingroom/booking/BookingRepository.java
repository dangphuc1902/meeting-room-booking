package com.company.meetingroom.booking;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    /** Booking lists: room and owner are always shown, so load them in the same query. */
    @Override
    @EntityGraph(attributePaths = { "room", "owner" })
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

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

    /** CONFIRMED bookings of every room overlapping [from, to), for the daily schedule. */
    @Query("""
            select b from Booking b
            join fetch b.room
            join fetch b.owner
            where b.status = com.company.meetingroom.booking.BookingStatus.CONFIRMED
              and b.startTime < :to
              and b.endTime > :from
            order by b.startTime
            """)
    List<Booking> findAllOverlapping(@Param("from") Instant from, @Param("to") Instant to);
}
