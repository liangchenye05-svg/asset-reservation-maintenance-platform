package com.example.equipmentmanagement.entity;

import java.time.LocalDateTime;

public class Device {
    private Integer id;
   private String deviceCode;
    private String name;
    private String status;
    private String location;
    private String model;
    private LocalDateTime createdAt;

    public Device() {}

    public Device(Integer id, String deviceCode, String name, String status, String location, String model, LocalDateTime createdAt) {

        this.id = id;
        this.deviceCode = deviceCode;
        this.name = name;
        this.status = status;
        this.location = location;
        this.model = model;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }
    public void setId(Integer id) {
        this.id = id;
    }
    public String getDeviceCode() {
        return deviceCode;
    }
    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;

    }
    public String getLocation() {
        return location;
    }
    public void setLocation(String location) {
        this.location = location;
    }
    public String getModel() {
        return model;
    }
    public void setModel(String model) {
        this.model = model;
    }
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
