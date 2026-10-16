package com.example.equipmentmanagement.mapper;

import com.example.equipmentmanagement.entity.RepairOrder;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RepairOrderMapper {
    @Select("select id,device_id,user_id,description,status,created_at,update_at as updated_at " +
            "from repair_order where id=#{id}")
    public RepairOrder getRepairOrder(int id);

    @Insert("insert into repair_order(device_id, user_id, description) " +
            "values (#{deviceId},#{userId},#{description})")
    public int insertRepairOrder(RepairOrder repairOrder);


    @Select("select id,device_id,user_id,description,status,created_at,update_at as updated_at " +
            "from repair_order order by id desc")
    public List<RepairOrder> getAllRepairOrders();

    @Select("select id,device_id,user_id,description,status,created_at,update_at as updated_at " +
            "from repair_order where user_id=#{userId} order by id desc")
    public List<RepairOrder> getRepairOrdersByUserId(@Param("userId") Integer userId);
    @Update("update repair_order set status='PROCESSING' where id=#{id} and status='PENDING'")
    public int processRepairOrderStatus(@Param("id")int id);
    @Update("update repair_order set status='COMPLETED' where id=#{id} and status='PROCESSING'")
    public int completeRepairOrderStatus(@Param("id") int id);
}
