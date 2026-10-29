package com.example.equipmentmanagement.controller;


import com.example.equipmentmanagement.dto.CreateRepairOrderRequest;

import com.example.equipmentmanagement.service.RepairOrderService;
import com.example.equipmentmanagement.entity.RepairOrder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/repair-orders")
public class RepairOrderController {
    private final RepairOrderService repairOrderService;
    public RepairOrderController(RepairOrderService repairOrderService) {
        this.repairOrderService = repairOrderService;
    }
    @PostMapping
    public int createRepairOrder(@RequestBody CreateRepairOrderRequest request,@AuthenticationPrincipal Integer currentUserId) {
        return repairOrderService.createRepairOrder(request.getDeviceId(),request.getDescription(),currentUserId);

    }

    @GetMapping
    public List<RepairOrder> getRepairOrders(
            @AuthenticationPrincipal Integer currentUserId,
            Authentication authentication, @RequestParam(required = false) String status) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return repairOrderService.getRepairOrders(currentUserId, isAdmin,status);
    }

    @GetMapping("/{id}")
    public RepairOrder getRepairOrder(
            @PathVariable int id,
            @AuthenticationPrincipal Integer currentUserId,
            Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return repairOrderService.getRepairOrder(id, currentUserId, isAdmin);
    }

    @PutMapping("/{id}/process")
    public int processRepairOrder(@PathVariable int id) {
        return repairOrderService.processRepairOrder(id);
    }
    @PutMapping("/{id}/complete")
    public int completeRepairOrder(@PathVariable int id) {
        return repairOrderService.completeRepairOrder(id);
    }
}
