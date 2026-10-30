package com.example.equipmentmanagement.controller;

import com.example.equipmentmanagement.common.PageResult;
import com.example.equipmentmanagement.dto.CreateReservationRequest;
import com.example.equipmentmanagement.entity.Reservation;
import com.example.equipmentmanagement.service.ReservationService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
public class ReservationController {
    private final ReservationService reservationService;
    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<Reservation> createReservation(@RequestBody CreateReservationRequest request,
            @AuthenticationPrincipal Integer currentUserId) {
        if (request == null) throw new IllegalArgumentException("预约信息不能为空");
        Reservation reservation = new Reservation();
        reservation.setDeviceId(request.deviceId());
        reservation.setStartTime(request.startTime());
        reservation.setEndTime(request.endTime());
        reservation.setUserId(currentUserId);
        Reservation saved = reservationService.createReservationRecord(reservation);
        return ResponseEntity.created(URI.create("/reservations/" + saved.getId())).body(saved);
    }

    @GetMapping({"", "/page"})
    public PageResult<Reservation> list(@AuthenticationPrincipal Integer currentUserId,
            Authentication authentication, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer deviceId) {
        return reservationService.getReservations(currentUserId, isAdmin(authentication), page, pageSize, status, deviceId);
    }

    @GetMapping("/{id}")
    public Reservation detail(@PathVariable int id, @AuthenticationPrincipal Integer currentUserId,
            Authentication authentication) {
        return reservationService.getReservation(id, currentUserId, isAdmin(authentication));
    }

    @PutMapping("/{id}/approve")
    public int approveReservation(@PathVariable int id) { return reservationService.approveReservation(id); }

    @PutMapping("/{id}/reject")
    public int rejectReservation(@PathVariable int id) { return reservationService.rejectReservation(id); }

    @PutMapping("/{id}/cancel")
    public int cancelReservation(@PathVariable int id, @AuthenticationPrincipal Integer currentUserId,
            Authentication authentication) {
        return reservationService.cancelReservation(id, currentUserId, isAdmin(authentication));
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
