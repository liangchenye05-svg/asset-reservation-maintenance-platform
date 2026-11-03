package com.example.equipmentmanagement.entity;

import java.time.LocalDateTime;

public record RepairHistory(Integer id, Integer repairOrderId, Integer actorId, String action,
                            String fromStatus, String toStatus, LocalDateTime createdAt, String reviewNote) {}
