package com.example.equipmentmanagement.dto;

import com.example.equipmentmanagement.entity.Reservation;
import java.util.List;

public record MaintenanceImpact(long approvedReservationCount, List<Reservation> reservations) {}
