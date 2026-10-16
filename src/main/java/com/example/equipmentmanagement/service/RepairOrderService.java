package com.example.equipmentmanagement.service;

import com.example.equipmentmanagement.entity.Device;
import com.example.equipmentmanagement.entity.RepairOrder;
import com.example.equipmentmanagement.exception.DeviceNotFoundException;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.RepairOrderMapper;
import com.example.equipmentmanagement.exception.RepairOrderNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

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

        Device device =deviceMapper.getDeviceById(deviceId);
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
        return rows;
    }

    public List<RepairOrder> getRepairOrders(Integer currentUserId, boolean isAdmin) {
        if (currentUserId == null || currentUserId <= 0) {
            throw new IllegalArgumentException("用户身份不合法");
        }
        return isAdmin ? repairOrderMapper.getAllRepairOrders()
                : repairOrderMapper.getRepairOrdersByUserId(currentUserId);
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

    public int processRepairOrder(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("工单id要大于0");
        }
        RepairOrder repairOrder = repairOrderMapper.getRepairOrder(id);
        if (repairOrder == null) {
            throw new RepairOrderNotFoundException("工单不存在");
        }
        if  (!"PENDING".equals(repairOrder.getStatus())) {
            throw new IllegalArgumentException("工单状态不允许更新");
        }
        int rows=repairOrderMapper.processRepairOrderStatus(id);
        if(rows!=1){
            throw new IllegalArgumentException("工单状态已变化，无法受理");
        }
        return rows;
    }

    public int completeRepairOrder(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("工单id要大于0");
        }
        RepairOrder repairOrder = repairOrderMapper.getRepairOrder(id);
        if (repairOrder == null) {
            throw new RepairOrderNotFoundException("工单不存在");
        }
        if  (!"PROCESSING".equals(repairOrder.getStatus())) {
            throw new IllegalArgumentException("工单状态不允许更新");
        }
        int rows=repairOrderMapper.completeRepairOrderStatus(id);
        if(rows!=1){
            throw new IllegalArgumentException("工单状态已变化，无法受理");
        }
              return rows;
    }
}
