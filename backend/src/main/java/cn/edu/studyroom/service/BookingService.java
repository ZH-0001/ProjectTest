package cn.edu.studyroom.service;

import cn.edu.studyroom.api.*;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class BookingService {
    private final UserMapper users; private final RoomMapper rooms; private final SeatMapper seats;
    private final ReserveMapper reserves; private final BehaviorMapper behaviors; private final TimeRules times;
    public BookingService(UserMapper users,RoomMapper rooms,SeatMapper seats,ReserveMapper reserves,BehaviorMapper behaviors,TimeRules times){
        this.users=users;this.rooms=rooms;this.seats=seats;this.reserves=reserves;this.behaviors=behaviors;this.times=times;
    }
    public static QueryWrapper<Reserve> overlap(TimeRules.Interval t){return new QueryWrapper<Reserve>().eq("status","ACTIVE").lt("start_time",t.end()).gt("end_time",t.start());}
    public boolean userFree(long user,TimeRules.Interval t){return reserves.selectCount(overlap(t).eq("user_id",user))==0;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> add(long user,Requests.Booking input){
        Api.require(input.requestId().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),400,"INVALID_PARAMETER","requestId须为UUID");
        String key=UUID.fromString(input.requestId()).toString();
        var interval=times.parse(input.startTime(),input.endTime());
        Api.require(users.lock(user)!=null,401,"UNAUTHENTICATED","请重新登录");
        Reserve old=reserves.selectOne(new QueryWrapper<Reserve>().eq("user_id",user).eq("request_id",key));
        if(old!=null){
            Api.require(old.getSeatId().equals(input.seatId())&&old.getStartTime().equals(interval.start())&&old.getEndTime().equals(interval.end()),409,"IDEMPOTENCY_CONFLICT","该请求标识已用于其他预约参数");
            return view(old,times.now());
        }
        Seat initial=seats.selectById(input.seatId());
        Api.require(initial!=null,404,"RESOURCE_NOT_FOUND","座位不存在");
        Room room=rooms.lock(initial.getRoomId());
        Seat seat=seats.lock(input.seatId());
        var now=times.now();times.validate(interval,now);checkRoom(room);
        Api.require(!seat.getDeleted(),404,"RESOURCE_NOT_FOUND","座位不存在");
        Api.require(times.covers(interval,room.getOpenTime(),room.getCloseTime()),409,"OUTSIDE_OPEN_HOURS","目标时段不在开放时间内");
        Api.require(userFree(user,interval),409,"USER_CONFLICT","你在该时段已有预约");
        Api.require(reserves.selectCount(overlap(interval).eq("seat_id",seat.getId()))==0,409,"SEAT_CONFLICT","该座位在目标时段已被预约");
        Reserve r=new Reserve();r.setUserId(user);r.setRoomId(room.getId());r.setSeatId(seat.getId());
        r.setStartTime(interval.start());r.setEndTime(interval.end());r.setStatus("ACTIVE");r.setRequestId(key);r.setCreatedAt(now);
        reserves.insert(r);
        Behavior b=new Behavior();b.setReserveId(r.getId());b.setUserId(user);b.setRoomId(room.getId());b.setSeatId(seat.getId());
        b.setStartTime(r.getStartTime());b.setEndTime(r.getEndTime());b.setRoomNameSnapshot(room.getName());b.setSeatNoSnapshot(seat.getSeatNo());
        b.setSeatTypeSnapshot(seat.getSeatType());b.setHasPowerSnapshot(seat.getHasPower());b.setIsQuietSnapshot(seat.getIsQuiet());b.setOperationType("RESERVE");b.setCreatedAt(now);
        behaviors.insert(b);return view(r,b,now);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> cancel(long user,long id){
        users.lock(user);
        Reserve initial=reserves.selectById(id);
        Api.require(initial!=null&&initial.getUserId()==user,404,"RESOURCE_NOT_FOUND","预约不存在或不可访问");
        rooms.lock(initial.getRoomId());seats.lock(initial.getSeatId());
        Reserve r=reserves.lock(id);var now=times.now();
        if(!"CANCELLED".equals(r.getStatus())){
            Api.require(now.isBefore(r.getStartTime()),409,"INVALID_RESERVE_STATE","预约已经开始或结束，不能取消");
            r.setStatus("CANCELLED");r.setCancelledAt(now);reserves.updateById(r);
        }
        return Api.map("reserveId",r.getId().toString(),"status","CANCELLED","cancelledAt",TimeRules.format(r.getCancelledAt()));
    }
    public void checkRoom(Room room){
        Api.require(room!=null&&!room.getDeleted(),404,"RESOURCE_NOT_FOUND","自习室不存在");
        Api.require(room.getEnabled(),409,"ROOM_UNAVAILABLE","自习室已停用");
    }
    public Map<String,Object> view(Reserve r,LocalDateTime now){
        Behavior b=behaviors.selectOne(new QueryWrapper<Behavior>().eq("reserve_id",r.getId()));
        return view(r,b,now);
    }
    private Map<String,Object> view(Reserve r,Behavior b,LocalDateTime now){
        if(b==null) throw new IllegalStateException("Missing reservation snapshot");
        return Api.map("id",r.getId().toString(),"roomId",r.getRoomId().toString(),"seatId",r.getSeatId().toString(),"roomName",b.getRoomNameSnapshot(),
          "seatNo",b.getSeatNoSnapshot(),"seatType",b.getSeatTypeSnapshot(),"hasPower",b.getHasPowerSnapshot(),"isQuiet",b.getIsQuietSnapshot(),
          "startTime",TimeRules.format(r.getStartTime()),"endTime",TimeRules.format(r.getEndTime()),
          "status",times.state(r.getStatus(),r.getStartTime(),r.getEndTime(),now),"createdAt",TimeRules.format(r.getCreatedAt()),"cancelledAt",TimeRules.format(r.getCancelledAt()));
    }
    public Map<String,Object> list(Long user,int page,int size,String status,String account,LocalDate from,LocalDate to){
        validatePage(page,size);var now=times.now();var q=new QueryWrapper<Reserve>();
        if(user!=null) q.eq("user_id",user);
        if(account!=null&&!account.isBlank()){
            User u=users.selectOne(new QueryWrapper<User>().eq("account",account.trim()));
            if(u==null) return Api.map("records",List.of(),"total",0,"page",page,"size",size);
            q.eq("user_id",u.getId());
        }
        if(from!=null||to!=null){dateRange(from,to);q.ge("start_time",from.atStartOfDay()).lt("start_time",to.plusDays(1).atStartOfDay());}
        if(status!=null&&!status.isBlank()) switch(status){
            case "CANCELLED"->q.eq("status","CANCELLED");
            case "PENDING"->q.eq("status","ACTIVE").gt("start_time",now);
            case "IN_PROGRESS"->q.eq("status","ACTIVE").le("start_time",now).gt("end_time",now);
            case "ENDED"->q.eq("status","ACTIVE").le("end_time",now);
            default->throw new Api.Failure(400,"INVALID_PARAMETER","预约状态无效");
        }
        long total=reserves.selectCount(q);
        List<Reserve> records=reserves.selectList(q.orderByDesc("created_at","id").last("LIMIT "+size+" OFFSET "+((long)(page-1)*size)));
        List<Map<String,Object>> result=new ArrayList<>();
        if(!records.isEmpty()){
            var snapshots=behaviors.selectList(new QueryWrapper<Behavior>().in("reserve_id",records.stream().map(Reserve::getId).toList())).stream().collect(Collectors.toMap(Behavior::getReserveId,b->b));
            Map<Long,User> accounts=user==null?users.selectBatchIds(records.stream().map(Reserve::getUserId).distinct().toList()).stream().collect(Collectors.toMap(User::getId,u->u)):Map.of();
            for(var r:records){var data=view(r,snapshots.get(r.getId()),now);if(user==null){data.put("account",accounts.get(r.getUserId()).getAccount());data.put("studentName",accounts.get(r.getUserId()).getName());}result.add(data);}
        }
        return Api.map("records",result,"total",total,"page",page,"size",size);
    }
    public static void validatePage(int page,int size){Api.require(page>=1&&size>=1&&size<=100,400,"INVALID_PARAMETER","分页参数不正确");}
    public static void dateRange(LocalDate from,LocalDate to){Api.require(from!=null&&to!=null&&!from.isAfter(to)&&to.isBefore(LocalDate.of(9999,12,31)),400,"INVALID_PARAMETER","日期范围不正确");}
}
