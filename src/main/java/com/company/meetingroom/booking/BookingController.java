package com.company.meetingroom.booking;

import java.net.URI;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.company.meetingroom.auth.AdminOnly;
import com.company.meetingroom.auth.AuthUser;
import com.company.meetingroom.booking.dto.BookingRequest;
import com.company.meetingroom.booking.dto.BookingResponse;
import com.company.meetingroom.booking.dto.BookingUpdateRequest;
import com.company.meetingroom.booking.dto.CancelBookingRequest;
import com.company.meetingroom.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
public class BookingController {

    private static final int MAX_PAGE_SIZE = 100;

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

    @GetMapping("/me")
    @Operation(summary = "My bookings: upcoming and running, soonest first (default CONFIRMED only); "
            + "includePast=true for the full history, most recent first")
    public PageResponse<BookingResponse> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean includePast,
            @RequestParam(defaultValue = "CONFIRMED") BookingStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return bookingService.listMine(AuthUser.from(jwt), includePast, status, page, size);
    }

    @GetMapping
    @AdminOnly
    @Operation(summary = "Search all bookings (admin); filters are optional, from/to are days in the business zone")
    public PageResponse<BookingResponse> search(
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return bookingService.search(roomId, userId, status, from, to, page, size);
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
