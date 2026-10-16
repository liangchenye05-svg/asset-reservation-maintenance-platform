package com.example.equipmentmanagement.mapper;


import com.example.equipmentmanagement.entity.Reservation;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface ReservationMapper {
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
}
