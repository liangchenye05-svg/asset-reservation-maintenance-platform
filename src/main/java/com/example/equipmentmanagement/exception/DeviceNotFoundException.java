package com.example.equipmentmanagement.exception;


public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String message) {
        super(message);
    }

}
