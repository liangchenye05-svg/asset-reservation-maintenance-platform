package com.example.equipmentmanagement.entity;

import java.time.LocalDateTime;

public class RepairOrder {
    private boolean historyComplete;
    private boolean manualReviewRequired;
    public boolean isHistoryComplete() { return historyComplete; }
    public void setHistoryComplete(boolean value) { historyComplete=value; }
    public boolean isManualReviewRequired() { return manualReviewRequired; }
    public void setManualReviewRequired(boolean value) { manualReviewRequired=value; }
    private Integer id;
    private Integer deviceId;
    private Integer userId;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RepairOrder() {}
    public RepairOrder(Integer id, Integer deviceId, Integer userId, String description, String status, LocalDateTime createdAt, LocalDateTime updatedAt)
    {
        this.id = id;
        this.deviceId = deviceId;
        this.userId = userId;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public Integer getId() {
        return id;
    }
    public void setId(Integer id) {
        this.id = id;
    }
    public Integer getDeviceId() {
        return deviceId;
    }
    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }
    public Integer getUserId() {
        return userId;
    }
    public void setUserId(Integer userId) {
        this.userId = userId;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
