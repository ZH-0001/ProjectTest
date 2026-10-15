package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final RoomMapper rooms;private final SeatMapper seats;private final ReserveMapper reserves;private final BookingService booking;private final TimeRules times;
    public CatalogService(RoomMapper rooms,SeatMapper seats,ReserveMapper reserves,BookingService booking,TimeRules times){this.rooms=rooms;this.seats=seats;this.reserves=reserves;this.booking=booking;this.times=times;}
    public Map<String,Object> rooms(int page,int size,boolean student,Boolean enabled){
        BookingService.validatePage(page,size);var q=new QueryWrapper<Room>().eq("deleted",false);
        if(student)q.eq("enabled",true);else if(enabled!=null)q.eq("enabled",enabled);
        long total=rooms.selectCount(q);var data=rooms.selectList(q.orderByAsc("id").last("LIMIT "+size+" OFFSET "+((long)(page-1)*size))).stream().map(Views::room).toList();
        return Api.map("records",data,"total",total,"page",page,"size",size);
    }
    public Map<String,Object> seats(long user,long roomId,OffsetDateTime start,OffsetDateTime end){
        var t=times.parse(start,end);times.validate(t,times.now());Room room=rooms.selectById(roomId);booking.checkRoom(room);
        Api.require(times.covers(t,room.getOpenTime(),room.getCloseTime()),409,"OUTSIDE_OPEN_HOURS","目标时段不在开放时间内");
        var taken=new HashSet<>(reserves.selectList(BookingService.overlap(t).eq("room_id",roomId)).stream().map(Reserve::getSeatId).toList());
        var data=seats.selectList(new QueryWrapper<Seat>().eq("room_id",roomId).eq("deleted",false).orderByAsc("id")).stream().map(s->{var v=Views.seat(s);v.put("seatAvailable",!taken.contains(s.getId()));return v;}).toList();
        return Api.map("roomId",Long.toString(roomId),"startTime",start.toString(),"endTime",end.toString(),"userAvailable",booking.userFree(user,t),"seats",data);
    }
    public Map<String,Object> adminSeats(long roomId,int page,int size){
        BookingService.validatePage(page,size);Room room=rooms.selectById(roomId);Api.require(room!=null&&!room.getDeleted(),404,"RESOURCE_NOT_FOUND","自习室不存在");
        var q=new QueryWrapper<Seat>().eq("room_id",roomId).eq("deleted",false);long total=seats.selectCount(q);
        var data=seats.selectList(q.orderByAsc("id").last("LIMIT "+size+" OFFSET "+((long)(page-1)*size))).stream().map(Views::seat).toList();
        return Api.map("records",data,"total",total,"page",page,"size",size);
    }
}
