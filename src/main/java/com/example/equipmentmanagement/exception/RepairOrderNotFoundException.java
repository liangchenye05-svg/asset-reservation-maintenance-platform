package com.example.equipmentmanagement.exception;

public class RepairOrderNotFoundException extends RuntimeException {
    public RepairOrderNotFoundException(String message) {
        super(message);
    }
}
