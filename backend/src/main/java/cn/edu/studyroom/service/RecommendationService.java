package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final RoomMapper rooms;private final SeatMapper seats;private final ReserveMapper reserves;private final BehaviorMapper behaviors;
    private final BookingService booking;private final TimeRules times;private final RecommendationEngine engine;
    public RecommendationService(RoomMapper rooms,SeatMapper seats,ReserveMapper reserves,BehaviorMapper behaviors,BookingService booking,TimeRules times,RecommendationEngine engine){this.rooms=rooms;this.seats=seats;this.reserves=reserves;this.behaviors=behaviors;this.booking=booking;this.times=times;this.engine=engine;}
    public Map<String,Object> recommend(long user,OffsetDateTime start,OffsetDateTime end){
        var t=times.parse(start,end);var now=times.now();times.validate(t,now);Api.require(booking.userFree(user,t),409,"USER_CONFLICT","你在该时段已有预约");
        var availableRooms=rooms.selectList(new QueryWrapper<Room>().eq("deleted",false).eq("enabled",true)).stream().filter(r->times.covers(t,r.getOpenTime(),r.getCloseTime())).collect(Collectors.toMap(Room::getId,r->r));
        List<Seat> candidates=List.of();
        if(!availableRooms.isEmpty()){
            var taken=new HashSet<>(reserves.selectList(BookingService.overlap(t).in("room_id",availableRooms.keySet())).stream().map(Reserve::getSeatId).toList());
            candidates=seats.selectList(new QueryWrapper<Seat>().eq("deleted",false).in("room_id",availableRooms.keySet())).stream().filter(s->!taken.contains(s.getId())).toList();
        }
        var result=engine.rank(candidates,availableRooms,behaviors.history(user,now));result.put("startTime",start.toString());result.put("endTime",end.toString());return result;
    }
}
