package com.example.equipmentmanagement.controller;


import com.example.equipmentmanagement.dto.CreateRepairOrderRequest;
import com.example.equipmentmanagement.dto.CompleteRepairOrderRequest;
import com.example.equipmentmanagement.dto.RepairOrderQuery;
import com.example.equipmentmanagement.common.PageResult;
import com.example.equipmentmanagement.dto.MaintenanceImpact;
import com.example.equipmentmanagement.entity.RepairHistory;

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

    @GetMapping("/page")
    public PageResult<RepairOrder> page(
            @AuthenticationPrincipal Integer currentUserId, Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer deviceId,
            @RequestParam(required = false) String keyword) {
        var query = new RepairOrderQuery(page, pageSize, status, deviceId, keyword);
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return repairOrderService.getRepairOrdersPage(currentUserId, admin, query);
    }

    @PutMapping("/{id}/process")
    public int processRepairOrder(@PathVariable int id, @AuthenticationPrincipal Integer actorId) {
        return repairOrderService.processRepairOrder(id, actorId);
    }
    @PutMapping("/{id}/complete")
    public int completeRepairOrder(@PathVariable int id, @AuthenticationPrincipal Integer actorId,
            @RequestBody(required=false) CompleteRepairOrderRequest request) {
        return repairOrderService.completeRepairOrder(id, actorId, request == null ? null : request.reviewNote());
    }

    @GetMapping("/{id}/history")
    public List<RepairHistory> history(@PathVariable int id, @AuthenticationPrincipal Integer userId,
            Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return repairOrderService.getHistory(id, userId, admin);
    }

    @GetMapping("/{id}/maintenance-impact")
    public MaintenanceImpact impact(@PathVariable int id, @AuthenticationPrincipal Integer actorId) {
        return repairOrderService.maintenanceImpact(id, actorId);
    }
}
