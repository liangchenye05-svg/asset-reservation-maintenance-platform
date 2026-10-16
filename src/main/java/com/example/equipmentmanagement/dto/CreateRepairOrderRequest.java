package com.example.equipmentmanagement.dto;

public class CreateRepairOrderRequest {
    private Integer deviceId;
    private String description;

    public CreateRepairOrderRequest() {}

    public CreateRepairOrderRequest(Integer deviceId, String description) {
        this.deviceId = deviceId;
        this.description = description;
    }
    public Integer getDeviceId() {
        return deviceId;
    }
    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
}
