package com.example.equipmentmanagement.service;

import com.example.equipmentmanagement.entity.Device;
import com.example.equipmentmanagement.exception.RepairReviewRequiredException;
import com.example.equipmentmanagement.common.PageResult;
import com.example.equipmentmanagement.dto.RepairOrderQuery;
import com.example.equipmentmanagement.dto.MaintenanceImpact;
import com.example.equipmentmanagement.entity.RepairHistory;
import com.example.equipmentmanagement.entity.RepairOrder;
import com.example.equipmentmanagement.exception.DeviceNotFoundException;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.RepairOrderMapper;
import com.example.equipmentmanagement.exception.RepairOrderNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Objects;

@Service
public class RepairOrderService {
    private final RepairOrderMapper repairOrderMapper;
    private final DeviceMapper deviceMapper;


    public RepairOrderService(RepairOrderMapper repairOrderMapper, DeviceMapper deviceMapper) {
        this.repairOrderMapper = repairOrderMapper;
        this.deviceMapper = deviceMapper;

    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int createRepairOrder(Integer deviceId,String description,Integer currentUserId) {
        if(deviceId==null||deviceId<=0){
            throw new IllegalArgumentException("设备id不合法");
        }
        if(description==null||description.isBlank()||description.length()>500){
            throw new IllegalArgumentException("故障描述不能为空或超过500个字符");
        }

        if (currentUserId == null || currentUserId <= 0) {
            throw new IllegalArgumentException("用户身份不合法");
        }

        Device device =deviceMapper.selectByIdForUpdate(deviceId);
        if(device==null){
            throw new DeviceNotFoundException("设备不存在");
        }

        RepairOrder order = new RepairOrder();
        order.setDeviceId(deviceId);
        order.setUserId(currentUserId);
        order.setDescription(description);

        int rows=repairOrderMapper.insertRepairOrder(order);
        if(rows!=1){
            throw new IllegalStateException("维修工单创建失败");
        }
        repairOrderMapper.insertHistory(order.getId(), currentUserId, "CREATED", null, "PENDING", null);
        return rows;
    }

    public List<RepairOrder> getRepairOrders(Integer currentUserId, boolean isAdmin,String status) {
        if (currentUserId == null || currentUserId <= 0) {
            throw new IllegalArgumentException("用户身份不合法");
        }

        if(status!=null) {
            if (!"PENDING".equals(status) && !"PROCESSING".equals(status) && !"COMPLETED".equals(status)) {
                throw new IllegalArgumentException("状态不合法");
            }
        }

        return isAdmin ? repairOrderMapper.getAllRepairOrders(status)
                : repairOrderMapper.getRepairOrdersByUserId(currentUserId,status);
    }

    public RepairOrder getRepairOrder(int id, Integer currentUserId, boolean isAdmin) {
        if (id <= 0) {
            throw new IllegalArgumentException("工单id不合法");
        }
        if (currentUserId == null || currentUserId <= 0) {
            throw new IllegalArgumentException("用户身份不合法");
        }
        RepairOrder order = repairOrderMapper.getRepairOrder(id);
        if (order == null) {
            throw new RepairOrderNotFoundException("维修工单不存在");
        }
        if (!isAdmin && !Objects.equals(order.getUserId(), currentUserId)) {
            throw new AccessDeniedException("无权查看其他用户的维修工单");
        }
        return order;
    }

    public PageResult<RepairOrder> getRepairOrdersPage(Integer currentUserId, boolean admin, RepairOrderQuery query) {
        if (currentUserId == null) throw new AccessDeniedException("请先登录");
        Integer ownerId = admin ? null : currentUserId;
        long total = repairOrderMapper.countFiltered(ownerId, query);
        var records = repairOrderMapper.selectFiltered(ownerId, query, (query.page() - 1L) * query.pageSize());
        return new PageResult<>(total, query.page(), query.pageSize(), records);
    }

    public List<RepairHistory> getHistory(int id, Integer userId, boolean admin) {
        getRepairOrder(id, userId, admin);
        return repairOrderMapper.history(id);
    }

    public MaintenanceImpact maintenanceImpact(int id, Integer actorId) {
        var order = getRepairOrder(id, actorId, true);
        var now = LocalDateTime.now();
        return new MaintenanceImpact(repairOrderMapper.countMaintenanceConflicts(order.getDeviceId(), now),
                repairOrderMapper.maintenanceConflicts(order.getDeviceId(), now));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int processRepairOrder(int id, Integer actorId) {
        if (id <= 0) {
            throw new IllegalArgumentException("工单id要大于0");
        }
        RepairOrder repairOrder = repairOrderMapper.getRepairOrder(id);
        if (repairOrder == null) {
            throw new RepairOrderNotFoundException("工单不存在");
        }
        deviceMapper.selectByIdForUpdate(repairOrder.getDeviceId());
        repairOrder = repairOrderMapper.getRepairOrder(id);
        if  (!"PENDING".equals(repairOrder.getStatus())) {
            throw new IllegalArgumentException("工单状态不允许更新");
        }
        int rows=repairOrderMapper.processRepairOrderStatus(id);
        if(rows!=1){
            throw new IllegalArgumentException("工单状态已变化，无法受理");
        }
        deviceMapper.beginMaintenance(repairOrder.getDeviceId());
        repairOrderMapper.insertHistory(id, actorId, "PROCESSED", "PENDING", "PROCESSING", null);
        return rows;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int completeRepairOrder(int id, Integer actorId, String reviewNote) {
        if (id <= 0) {
            throw new IllegalArgumentException("工单id要大于0");
        }
        RepairOrder repairOrder = repairOrderMapper.getRepairOrder(id);
        if (repairOrder == null) {
            throw new RepairOrderNotFoundException("工单不存在");
        }
        Device device=deviceMapper.selectByIdForUpdate(repairOrder.getDeviceId());
        repairOrder = repairOrderMapper.getRepairOrder(id);
        if  (!"PROCESSING".equals(repairOrder.getStatus())) {
            throw new IllegalArgumentException("工单状态不允许更新");
        }
        if (device.isMaintenanceRestoreBlocked() && reviewNote == null) throw new RepairReviewRequiredException();
        int rows=repairOrderMapper.completeRepairOrderStatus(id);
        if(rows!=1){
            throw new IllegalArgumentException("工单状态已变化，无法受理");
        }
        repairOrderMapper.insertHistory(id, actorId, "COMPLETED", "PROCESSING", "COMPLETED", reviewNote);
        if (repairOrderMapper.countOpenForDevice(repairOrder.getDeviceId()) == 0)
            deviceMapper.restoreBeforeMaintenance(repairOrder.getDeviceId());
              return rows;
    }
}
