package com.company.meetingroom.booking;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.booking.dto.BookingResponse;
import com.company.meetingroom.booking.dto.BookingUpdateRequest;
import com.company.meetingroom.booking.dto.CancelBookingRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Book a room; 409 if the slot is taken, 422 if a booking rule is broken")
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BookingRequest request) {
        BookingResponse created = bookingService.create(AuthUser.from(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Booking details (owner or admin)")
    public BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return bookingService.get(AuthUser.from(jwt), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a booking that has not started (owner or admin); send the version you last read")
    public BookingResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody BookingUpdateRequest request) {
        return bookingService.update(AuthUser.from(jwt), id, request);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking that has not ended (owner or admin); the slot is freed immediately")
    public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelBookingRequest request) {
        return bookingService.cancel(AuthUser.from(jwt), id, request == null ? null : request.reason());
    }
}
