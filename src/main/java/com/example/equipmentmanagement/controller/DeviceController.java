package com.example.equipmentmanagement.controller;

import com.example.equipmentmanagement.common.PageResult;
import com.example.equipmentmanagement.entity.Device;
import com.example.equipmentmanagement.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/devices")

public class DeviceController {
    private final DeviceService deviceService;
    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/{id}")
    public Device getDeviceById(@PathVariable int id) {
        return deviceService.getDeviceById(id);
    }

    @GetMapping
    public List<Device> getDeviceByStatus(@RequestParam String status) {
        return deviceService.getDeviceByStatus(status);
    }
    @PostMapping
    public int addDevice(@RequestBody Device device) {
        return deviceService.addDevice(device);
    }
    @PutMapping("/{id}")
    public int updateDevice(@PathVariable int id,
                            @RequestBody Device device) {
        return deviceService.updateDevice(id,device);
    }
    @DeleteMapping("/{id}")
    public int deleteDevice(@PathVariable int id) {
        return deviceService.deleteDevice(id);
    }

    @GetMapping("/page")
    public PageResult<Device> getDeviceByPage(@RequestParam int page, @RequestParam int pageSize) {
        return deviceService.getDeviceByPage(page, pageSize);
    }


}
