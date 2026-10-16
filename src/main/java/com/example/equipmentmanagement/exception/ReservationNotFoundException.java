package com.example.equipmentmanagement.exception;

public class ReservationNotFoundException extends RuntimeException {
    public  ReservationNotFoundException(String message) {
        super(message);
    }
}
