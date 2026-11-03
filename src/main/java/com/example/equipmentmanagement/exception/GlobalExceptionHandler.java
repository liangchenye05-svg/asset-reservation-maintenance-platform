package com.example.equipmentmanagement.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice

public class GlobalExceptionHandler {
    @ExceptionHandler(RepairReviewRequiredException.class)
    public ResponseEntity<String> handle(RepairReviewRequiredException e) {
        return ResponseEntity.status(409).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handle(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<String> handle(DeviceNotFoundException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handle(DataIntegrityViolationException e)
        {
        return ResponseEntity.status(409).body("数据约束冲突，记录被引用或字段不符合约束");
        }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<String> handle(DuplicateKeyException e) {
        return ResponseEntity.status(409).body("唯一字段已存在");
    }
        @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<String> handle(ReservationNotFoundException e)
        {
        return ResponseEntity.status(404).body(e.getMessage());
        }

    @ExceptionHandler(RepairOrderNotFoundException.class)
    public ResponseEntity<String> handle(RepairOrderNotFoundException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<String> handle(ReservationConflictException e) {
        return ResponseEntity.status(409).body(e.getMessage());
    }
}
