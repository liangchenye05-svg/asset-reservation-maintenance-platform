package com.example.equipmentmanagement.service;

import com.example.equipmentmanagement.common.PageResult;
import com.example.equipmentmanagement.entity.Device;
import com.example.equipmentmanagement.exception.DeviceNotFoundException;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class DeviceService {
    private final DeviceMapper deviceMapper;

    public DeviceService(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    public Device getDeviceById(int id) {

        if (id<=0){
            throw new IllegalArgumentException("设备ID不合法");
        }
        Device device = deviceMapper.getDeviceById(id);

        if(device==null){
            throw new DeviceNotFoundException( "设备不存在");
        }
        return device;
    }

    public List<Device>getDeviceByStatus (String status) {
        if (status==null||status.isBlank()){

              throw new IllegalArgumentException("设备状态不合法");
        }
        List<Device> devices = deviceMapper.getDeviceByStatus(status);

        return devices;
    }

    public int addDevice(Device device) {
        if (device==null){
            throw new IllegalArgumentException("设备信息不能为空");
        }
        if(device.getDeviceCode()==null||device.getDeviceCode().isBlank()){
            throw new IllegalArgumentException("设备编码不合法");
        }
        if(device.getName()==null||device.getName().isBlank()){
            throw  new IllegalArgumentException("设备名字不合法");
        }
        if(device.getLocation()==null||device.getLocation().isBlank()){
            throw new IllegalArgumentException("设备地址不合法");
        }
        validateLengths(device, true);
        int rows = deviceMapper.insertDevice(device);
        if(rows!=1){
            throw new IllegalStateException("设备新增失败");
        }
        return rows;
    }

    public int updateDevice(int id ,Device device) {
        if(id<=0){
            throw new IllegalArgumentException("设备id不合法");
        }
        if (device==null){
            throw new IllegalArgumentException("设备信息不能为空");
        }
        if(device.getName()==null||device.getName().isBlank()){
            throw  new IllegalArgumentException("设备名字不合法");
        }
        if(device.getLocation()==null||device.getLocation().isBlank()){
            throw new IllegalArgumentException("设备地址不合法");
        }
        validateLengths(device, false);
        if(deviceMapper.getDeviceById(id)==null)
        {
            throw new DeviceNotFoundException("设备不存在");
        }
        device.setId(id);

        int rows = deviceMapper.updateDevice(device);
        if(rows!=1){
            throw new IllegalStateException("设备修改失败");
        }
        return rows;
    }

    public int deleteDevice(int id) {
        if(id<=0){
            throw new IllegalArgumentException("设备id不合法");
        }

        if (deviceMapper.getDeviceById(id)==null){
            throw new DeviceNotFoundException("设备不存在");
        }

        int rows = deviceMapper.deleteDevice(id);

        if(rows!=1){
            throw new IllegalStateException("设备删除失败");
        }
        return rows;
    }
    public PageResult<Device> getDeviceByPage(int page, int pageSize) {


        if (page<=0){
            throw new IllegalArgumentException("页码不合法");
        }
        if (pageSize<=0){
            throw new IllegalArgumentException("单页数量不合法");
        }
        if(pageSize>100){
            throw new IllegalArgumentException("超过单页最大查询数量");
        }

        long offset = (page - 1L) * pageSize;

            List<Device> devices=deviceMapper.selectByPage(offset,pageSize);

            long total=deviceMapper.countDevices();

            PageResult<Device> result=new PageResult<>( total,page , pageSize,devices);

            return result;
    }



    private void validateLengths(Device device, boolean creating) {
        if (creating) checkLength(device.getDeviceCode(), 50, "设备编码");
        checkLength(device.getName(), 50, "设备名字");
        checkLength(device.getLocation(), 100, "设备地址");
        checkLength(device.getModel(), 50, "设备型号");
    }

    private void checkLength(String value, int max, String field) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(field + "长度不能超过" + max + "个字符");
        }
    }
}
