package com.example.equipmentmanagement.mapper;

import com.example.equipmentmanagement.entity.RepairOrder;
import com.example.equipmentmanagement.dto.RepairOrderQuery;
import com.example.equipmentmanagement.entity.RepairHistory;
import com.example.equipmentmanagement.entity.Reservation;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;


import java.util.List;

@Mapper
public interface RepairOrderMapper {
    @Select({"<script>",
            "SELECT r.id,r.device_id,r.user_id,r.description,r.status,r.created_at,r.update_at AS updated_at,r.history_complete, (r.status='PROCESSING' AND d.maintenance_restore_blocked=1) AS manual_review_required",
            "FROM repair_order r JOIN device d ON d.id=r.device_id WHERE 1=1",
            "<if test='ownerId != null'>AND r.user_id=#{ownerId}</if>",
            "<if test='query.status != null and query.status != \"OPEN\"'>AND r.status=#{query.status}</if>",
            "<if test='query.status == \"OPEN\"'>AND r.status IN ('PENDING','PROCESSING')</if>",
            "<if test='query.deviceId != null'>AND r.device_id=#{query.deviceId}</if>",
            "<if test='query.keyword != null'>AND (CAST(r.id AS CHAR)=#{query.keyword}",
            "OR LOCATE(#{query.keyword},r.description)>0 OR LOCATE(#{query.keyword},d.name)>0",
            "OR LOCATE(#{query.keyword},d.device_code)>0)</if>",
            "ORDER BY r.id DESC LIMIT #{query.pageSize} OFFSET #{offset}", "</script>"})
    List<RepairOrder> selectFiltered(@Param("ownerId") Integer ownerId, @Param("query") RepairOrderQuery query,
            @Param("offset") long offset);

    @Select({"<script>",
            "SELECT COUNT(*) FROM repair_order r JOIN device d ON d.id=r.device_id WHERE 1=1",
            "<if test='ownerId != null'>AND r.user_id=#{ownerId}</if>",
            "<if test='query.status != null and query.status != \"OPEN\"'>AND r.status=#{query.status}</if>",
            "<if test='query.status == \"OPEN\"'>AND r.status IN ('PENDING','PROCESSING')</if>",
            "<if test='query.deviceId != null'>AND r.device_id=#{query.deviceId}</if>",
            "<if test='query.keyword != null'>AND (CAST(r.id AS CHAR)=#{query.keyword}",
            "OR LOCATE(#{query.keyword},r.description)>0 OR LOCATE(#{query.keyword},d.name)>0",
            "OR LOCATE(#{query.keyword},d.device_code)>0)</if>", "</script>"})
    long countFiltered(@Param("ownerId") Integer ownerId, @Param("query") RepairOrderQuery query);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select r.id,r.device_id,r.user_id,r.description,r.status,r.created_at,r.update_at as updated_at,r.history_complete, (r.status='PROCESSING' AND d.maintenance_restore_blocked=1) as manual_review_required " +
            "from repair_order r JOIN device d ON d.id=r.device_id where r.id=#{id}")
    public RepairOrder getRepairOrder(int id);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into repair_order(device_id, user_id, description, history_complete) " +
            "values (#{deviceId},#{userId},#{description},1)")
    public int insertRepairOrder(RepairOrder repairOrder);


    @Select("select r.id,r.device_id,r.user_id,r.description,r.status,r.created_at,r.update_at as updated_at,r.history_complete, (r.status='PROCESSING' AND d.maintenance_restore_blocked=1) as manual_review_required " +
            "from repair_order r JOIN device d ON d.id=r.device_id where(#{status} IS NULL OR r.status = #{status}) order by r.id desc")
    public List<RepairOrder> getAllRepairOrders(@Param("status")String status);

    @Select("select r.id,r.device_id,r.user_id,r.description,r.status,r.created_at,r.update_at as updated_at,r.history_complete, (r.status='PROCESSING' AND d.maintenance_restore_blocked=1) as manual_review_required " +
            "from repair_order r JOIN device d ON d.id=r.device_id where r.user_id=#{userId} AND (#{status} IS NULL OR r.status = #{status})" +
            "  order by r.id desc")
    public List<RepairOrder> getRepairOrdersByUserId(@Param("userId") Integer userId, @Param("status") String status);
    @Update("update repair_order set status='PROCESSING' where id=#{id} and status='PENDING'")
    public int processRepairOrderStatus(@Param("id")int id);
    @Update("update repair_order set status='COMPLETED' where id=#{id} and status='PROCESSING'")
    public int completeRepairOrderStatus(@Param("id") int id);

    @Select("SELECT COUNT(*) FROM repair_order WHERE device_id=#{deviceId} AND status IN ('PENDING','PROCESSING')")
    int countOpenForDevice(int deviceId);

    @Insert("INSERT INTO repair_order_history(repair_order_id,actor_id,action,from_status,to_status,review_note) " +
            "VALUES(#{orderId},#{actorId},#{action},#{fromStatus},#{toStatus},#{reviewNote})")
    int insertHistory(@Param("orderId") int orderId, @Param("actorId") int actorId, @Param("action") String action,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus, @Param("reviewNote") String reviewNote);

    @Select("SELECT id,repair_order_id,actor_id,action,from_status,to_status,created_at,review_note " +
            "FROM repair_order_history WHERE repair_order_id=#{id} ORDER BY id ASC")
    List<RepairHistory> history(int id);

    @Select("SELECT COUNT(*) FROM reservation WHERE device_id=#{deviceId} AND status='APPROVED' AND end_time>#{now}")
    long countMaintenanceConflicts(@Param("deviceId") int deviceId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM reservation WHERE device_id=#{deviceId} AND status='APPROVED' AND end_time>#{now} " +
            "ORDER BY start_time ASC,id ASC LIMIT 100")
    List<Reservation> maintenanceConflicts(@Param("deviceId") int deviceId, @Param("now") LocalDateTime now);
}
