package com.example.equipmentmanagement.dto;

import java.time.LocalDateTime;

/** Only editable fields are accepted. Identity and status are controlled by the server. */
public record CreateReservationRequest(Integer deviceId, LocalDateTime startTime, LocalDateTime endTime) {}
