package com.example.equipmentmanagement.mapper;


import com.example.equipmentmanagement.entity.Reservation;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReservationMapper {
    @Select({"<script>",
            "SELECT * FROM reservation WHERE 1=1",
            "<if test='userId != null'>AND user_id=#{userId}</if>",
            "<if test='status != null'>AND status=#{status}</if>",
            "<if test='deviceId != null'>AND device_id=#{deviceId}</if>",
            "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}", "</script>"})
    List<Reservation> selectPage(@Param("userId") Integer userId, @Param("status") String status,
            @Param("deviceId") Integer deviceId, @Param("offset") long offset, @Param("limit") int limit);

    @Select({"<script>",
            "SELECT COUNT(*) FROM reservation WHERE 1=1",
            "<if test='userId != null'>AND user_id=#{userId}</if>",
            "<if test='status != null'>AND status=#{status}</if>",
            "<if test='deviceId != null'>AND device_id=#{deviceId}</if>", "</script>"})
    long countPage(@Param("userId") Integer userId, @Param("status") String status,
            @Param("deviceId") Integer deviceId);

    // A second read after waiting for the device lock must reach the database.
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select * from reservation where id=#{id}")
    public Reservation selectById(int id);

    @Select("select count(*) from reservation" +
            " where device_id=#{deviceId} " +
            "and status='APPROVED'" +
            "and  start_time < #{endTime}" +
            " and  end_time > #{startTime}")
    public int countConflicts(
            @Param("deviceId")int deviceId,
            @Param("startTime")LocalDateTime startTime,
            @Param("endTime")LocalDateTime endTime);


    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("insert into reservation(device_id, user_id, start_time, end_time)" +
            "values (#{deviceId},#{userId},#{startTime},#{endTime})")
    public int insertReservation(Reservation reservation);

    @Update("update reservation set status=#{status} where id=#{id}")
    public int updateStatus(@Param("status")String status,@Param("id")int id);

    @Update("update reservation set status='APPROVED' where id=#{id} " +
            "and status='PENDING'")
            public int  approveIfPending(int id);

    @Update("update reservation set status='CANCELLED' where id=#{id} and status in('PENDING','APPROVED')")
    public int  cancelIfAllowed(int id);

    @Update("update reservation set status='REJECTED' where id=#{id} and status='PENDING'")
    public int  rejectIfPending(int id);
}
