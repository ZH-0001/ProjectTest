package cn.edu.studyroom.service;
import cn.edu.studyroom.api.*;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class AdminService {
    private final RoomMapper rooms;private final SeatMapper seats;private final ReserveMapper reserves;private final TimeRules times;
    public AdminService(RoomMapper rooms,SeatMapper seats,ReserveMapper reserves,TimeRules times){this.rooms=rooms;this.seats=seats;this.reserves=reserves;this.times=times;}
    private Room room(long id){var r=rooms.lock(id);Api.require(r!=null&&!r.getDeleted(),404,"RESOURCE_NOT_FOUND","自习室不存在");return r;}
    private void unused(long roomId,Long seatId){
        var q=new QueryWrapper<Reserve>().eq("room_id",roomId).eq("status","ACTIVE").gt("end_time",times.now());
        if(seatId!=null)q.eq("seat_id",seatId);
        Api.require(reserves.selectCount(q)==0,409,"RESOURCE_IN_USE","存在待开始或进行中的预约，请待预约结束后操作");
    }
    public Map<String,Object> addRoom(Requests.RoomInput in){
        Api.require(in.roomId()==null,400,"INVALID_PARAMETER","新增自习室不可指定ID");
        Api.require((in.openTime()==null)==(in.closeTime()==null),400,"INVALID_PARAMETER","开放起止时间须同时提供");
        var open=in.openTime()==null?LocalTime.of(8,0):in.openTime();var close=in.closeTime()==null?LocalTime.of(22,0):in.closeTime();times.opening(open,close);
        Room r=new Room();r.setName(in.name().trim());r.setLocation(in.location().trim());r.setOpenTime(open);r.setCloseTime(close);r.setEnabled(true);r.setDeleted(false);rooms.insert(r);return Views.room(r);
    }
    public Map<String,Object> updateRoom(Requests.RoomInput in){
        Api.require(in.roomId()!=null&&in.roomId()>0,400,"INVALID_PARAMETER","roomId必填");times.opening(in.openTime(),in.closeTime());Room r=room(in.roomId());
        for(var pending:reserves.selectList(new QueryWrapper<Reserve>().eq("room_id",r.getId()).eq("status","ACTIVE").gt("end_time",times.now())))
            Api.require(times.covers(new TimeRules.Interval(pending.getStartTime(),pending.getEndTime()),in.openTime(),in.closeTime()),409,"RESOURCE_IN_USE","新的开放时间会影响已有预约");
        r.setName(in.name().trim());r.setLocation(in.location().trim());r.setOpenTime(in.openTime());r.setCloseTime(in.closeTime());rooms.updateById(r);return Views.room(r);
    }
    public Map<String,Object> status(long id,boolean enabled){var r=room(id);if(!enabled)unused(id,null);r.setEnabled(enabled);rooms.updateById(r);return Views.room(r);}
    public Map<String,Object> deleteRoom(long id){
        Room r=rooms.lock(id);Api.require(r!=null,404,"RESOURCE_NOT_FOUND","自习室不存在");
        if(!r.getDeleted()){unused(id,null);r.setDeleted(true);r.setEnabled(false);rooms.updateById(r);}return Api.map("id",Long.toString(id),"deleted",true);
    }
    private void seatFields(Requests.SeatInput in){Api.require(Set.of("WINDOW","AISLE","MIDDLE").contains(in.seatType()),400,"INVALID_PARAMETER","座位类型不正确");}
    private void saveSeat(Seat s,boolean insert){
        try{if(insert)seats.insert(s);else seats.updateById(s);}catch(DuplicateKeyException e){throw new Api.Failure(409,"SEAT_NO_EXISTS","该自习室内座位编号已存在（包含已删除座位）");}
    }
    public Map<String,Object> addSeat(Requests.SeatInput in){
        seatFields(in);Api.require(in.seatId()==null&&in.roomId()!=null&&in.roomId()>0,400,"INVALID_PARAMETER","新增须指定roomId且不可指定seatId");room(in.roomId());
        Seat s=new Seat();s.setRoomId(in.roomId());s.setSeatNo(in.seatNo().trim());s.setSeatType(in.seatType());s.setHasPower(in.hasPower());s.setIsQuiet(in.isQuiet());s.setDeleted(false);saveSeat(s,true);return Views.seat(s);
    }
    public Map<String,Object> updateSeat(Requests.SeatInput in){
        seatFields(in);Api.require(in.seatId()!=null&&in.seatId()>0&&in.roomId()==null,400,"INVALID_PARAMETER","编辑须指定seatId且不可修改roomId");
        Seat initial=seats.selectById(in.seatId());Api.require(initial!=null,404,"RESOURCE_NOT_FOUND","座位不存在");room(initial.getRoomId());Seat s=seats.lock(in.seatId());
        Api.require(!s.getDeleted(),404,"RESOURCE_NOT_FOUND","座位不存在");
        if(!s.getSeatType().equals(in.seatType())||!s.getHasPower().equals(in.hasPower())||!s.getIsQuiet().equals(in.isQuiet()))unused(s.getRoomId(),s.getId());
        s.setSeatNo(in.seatNo().trim());s.setSeatType(in.seatType());s.setHasPower(in.hasPower());s.setIsQuiet(in.isQuiet());saveSeat(s,false);return Views.seat(s);
    }
    public Map<String,Object> deleteSeat(long id){
        Seat initial=seats.selectById(id);Api.require(initial!=null,404,"RESOURCE_NOT_FOUND","座位不存在");
        // A deleted parent does not prevent a safe, idempotent child deletion.
        rooms.lock(initial.getRoomId());Seat s=seats.lock(id);
        if(!s.getDeleted()){unused(s.getRoomId(),id);s.setDeleted(true);seats.updateById(s);}return Api.map("id",Long.toString(id),"deleted",true);
    }
}
