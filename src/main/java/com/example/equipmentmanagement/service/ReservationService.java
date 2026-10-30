package com.example.equipmentmanagement.service;


import com.example.equipmentmanagement.entity.Device;
import com.example.equipmentmanagement.entity.Reservation;
import com.example.equipmentmanagement.exception.DeviceNotFoundException;
import com.example.equipmentmanagement.exception.ReservationConflictException;
import com.example.equipmentmanagement.exception.ReservationNotFoundException;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.ReservationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import com.example.equipmentmanagement.common.PageResult;


@Service

public class ReservationService {
    private final ReservationMapper reservationMapper;
    private final DeviceMapper deviceMapper;

    public ReservationService(ReservationMapper reservationMapper, DeviceMapper deviceMapper) {
        this.reservationMapper = reservationMapper;
        this.deviceMapper = deviceMapper;
    }

    public PageResult<Reservation> getReservations(Integer currentUserId, boolean admin,
            int page, int pageSize, String status, Integer deviceId) {
        if (currentUserId == null) throw new AccessDeniedException("请先登录");
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("页码必须大于0，单页数量为1至100");
        if (deviceId != null && deviceId < 1) throw new IllegalArgumentException("设备ID不合法");
        status = status == null || status.isBlank() ? null : status.trim();
        if (status != null && !Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(status))
            throw new IllegalArgumentException("预约状态不合法");
        // Identity is derived on the server. Client filters cannot widen this scope.
        Integer ownerId = admin ? null : currentUserId;
        long total = reservationMapper.countPage(ownerId, status, deviceId);
        return new PageResult<>(total, page, pageSize,
                reservationMapper.selectPage(ownerId, status, deviceId, (page - 1L) * pageSize, pageSize));
    }

    public Reservation getReservation(int id, Integer currentUserId, boolean admin) {
        if (currentUserId == null) throw new AccessDeniedException("请先登录");
        if (id < 1) throw new IllegalArgumentException("预约ID不合法");
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) throw new ReservationNotFoundException("预约记录不存在");
        if (!admin && !Objects.equals(reservation.getUserId(), currentUserId))
            throw new AccessDeniedException("无权查看其他用户的预约");
        return reservation;
    }

    @Transactional
    public Reservation createReservationRecord(Reservation reservation) {
        createReservation(reservation);
        Reservation saved = reservationMapper.selectById(reservation.getId());
        if (saved == null) throw new IllegalStateException("预约保存结果不可读取");
        return saved;
    }

    public int createReservation(Reservation reservation){
        if (reservation==null){
            throw new IllegalArgumentException("预约信息不能为空");
        }
        if(reservation.getDeviceId()==null){
            throw new IllegalArgumentException("设备id不能为空");
        }
        if (reservation.getUserId()==null){

            throw new IllegalArgumentException("用户id不能为空");
        }
        if (reservation.getStartTime()==null){
            throw new IllegalArgumentException("开始时间不能为空");
        }
        if (reservation.getEndTime()==null){
            throw new IllegalArgumentException("结束时间不能为空");
        }
        if(!reservation.getStartTime().isBefore(reservation.getEndTime())){
            throw new IllegalArgumentException("时间信息不合法");
        }
            Device device =deviceMapper.getDeviceById(reservation.getDeviceId());
         if(device==null){
             throw new DeviceNotFoundException("设备不存在");
         }

         int conflicts=reservationMapper.countConflicts(reservation.getDeviceId(),reservation.getStartTime(),reservation.getEndTime());
         if(conflicts>0){
             throw new IllegalArgumentException("该设备在指定时间段内已被预约");
         }
int rows=reservationMapper.insertReservation(reservation);
         if(rows!=1){
             throw new IllegalStateException("预约保存失败");
         }
return rows;
    }

    public int rejectReservation(int id){
            Reservation reservation=reservationMapper.selectById(id);
            if(reservation==null){
                throw new ReservationNotFoundException("设备预约不存在");
            }
            Device device=deviceMapper.getDeviceById(reservation.getDeviceId());
            if(device==null){
                throw new DeviceNotFoundException("设备不存在");
            }
            if(!"PENDING".equals(reservation.getStatus())){
                throw new IllegalArgumentException("当前预约状态不允许拒绝");
            }
            int rows=reservationMapper.rejectIfPending(id);
            if(rows==0){
                throw new ReservationConflictException("预约状态已变化，无法拒绝");
            }
            return rows;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int approveReservation(int id){
        Reservation reservation=reservationMapper.selectById(id);
        if(reservation==null){
            throw new ReservationNotFoundException("设备预约不存在");
        }

        Device device=deviceMapper.selectByIdForUpdate(reservation.getDeviceId());
        if(device==null){
            throw new DeviceNotFoundException("设备不存在");
        }

        reservation=reservationMapper.selectById(id);
        if(reservation==null){
            throw new ReservationNotFoundException("预约记录不存在");
        }

        if(!"PENDING".equals(reservation.getStatus())){
            throw new IllegalArgumentException("当前预约状态不允许审批");
        }

        int countConflicts=reservationMapper.countConflicts(reservation.getDeviceId(), reservation.getStartTime(), reservation.getEndTime());
        if(countConflicts>0){
            throw new IllegalArgumentException("预约时间冲突，无法审批通过");
        }

        int rows=reservationMapper.approveIfPending(id);
if(rows!=1){
    throw new IllegalStateException("预约审批失败");
}
return rows;
    }

@Transactional(isolation = Isolation.READ_COMMITTED)
    public int cancelReservation(int id, Integer currentUserId,boolean isAdmin )  {
        Reservation reservation=reservationMapper.selectById(id);
        if(reservation==null){
            throw new ReservationNotFoundException("设备预约不存在");
        }
        Device device=deviceMapper.selectByIdForUpdate(reservation.getDeviceId());
        if(device==null){
            throw new DeviceNotFoundException("设备不存在");
        }

        reservation=reservationMapper.selectById(id);
        if(reservation==null){
            throw new ReservationNotFoundException("设备预约不存在");
        }

    if (!isAdmin &&
            !Objects.equals(reservation.getUserId(), currentUserId)) {
                 throw new AccessDeniedException("无权取消其他用户的预约");
    }

        if(!"PENDING".equals(reservation.getStatus())&&!"APPROVED".equals(reservation.getStatus())){
            throw new IllegalArgumentException("当前状态不允许取消");
        }

        LocalDateTime now=LocalDateTime.now();
        if(!now.isBefore(reservation.getStartTime())){
            throw new IllegalArgumentException("设备使用时间已经开始，不允许取消");
        }
       int rows=reservationMapper.cancelIfAllowed(id);
        if(rows!=1){
            throw new IllegalStateException("预约取消失败");
        }
        return rows;
    }


}
