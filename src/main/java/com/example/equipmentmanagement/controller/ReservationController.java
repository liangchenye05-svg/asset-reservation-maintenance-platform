package com.example.equipmentmanagement.controller;


import com.example.equipmentmanagement.entity.Reservation;
import com.example.equipmentmanagement.mapper.ReservationMapper;
import com.example.equipmentmanagement.service.ReservationService;
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
    public int createReservation(@RequestBody Reservation reservation,@AuthenticationPrincipal Integer currentUserId) {
        reservation.setUserId(currentUserId);
        return reservationService.createReservation(reservation);
    }

    @PutMapping("/{id}/approve")
    public int approveReservation(@PathVariable int id) {
        return reservationService.approveReservation(id);
    }

    @PutMapping("/{id}/cancel")
    public int cancelReservation(
            @PathVariable int id,
            @AuthenticationPrincipal Integer currentUserId,
            Authentication authentication) {

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        return reservationService.cancelReservation(
                id, currentUserId, isAdmin
        );
    }
}
