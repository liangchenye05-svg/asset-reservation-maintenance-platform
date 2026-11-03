package com.example.equipmentmanagement.mapper;

import com.example.equipmentmanagement.entity.Device;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DeviceMapper {

    @Select("select * from device where id=#{id}")
    public Device getDeviceById(int id);

    @Select("select *from device where status=#{status}")
    public List<Device> getDeviceByStatus(String status);

    @Select("select *from device order by id asc limit #{offset},#{pageSize}")
    public List<Device> selectByPage(@Param("offset") long offset, @Param("pageSize") int pageSize);

    @Select("select count(*) from device")
    public long countDevices();

    @Select("select *from device where id=#{id} for update")
    public Device selectByIdForUpdate(int id);

    @Update("UPDATE device SET status_before_maintenance=CASE WHEN status='MAINTENANCE' OR maintenance_restore_blocked=1 " +
            "THEN status_before_maintenance ELSE status END,status='MAINTENANCE' WHERE id=#{id}")
    int beginMaintenance(int id);

    @Update("UPDATE device SET status=status_before_maintenance,status_before_maintenance=NULL " +
            "WHERE id=#{id} AND status='MAINTENANCE' AND status_before_maintenance IS NOT NULL AND maintenance_restore_blocked=0")
    int restoreBeforeMaintenance(int id);

    @Insert("insert into device(device_code, name, location, model)" +
            "values (#{deviceCode},#{name},#{location},#{model})")
    public int insertDevice(Device device);

    @Update("update device set name=#{name}, " +
            "location=#{location}, " +
            "model=#{model} " +
            "where id=#{id}")
            public int updateDevice(Device device);

    @Delete("delete from device where id =#{id}")
    public int deleteDevice(int id);
}
