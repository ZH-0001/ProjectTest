package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.*;
import java.util.Map;

public final class Views {
    private Views(){}
    public static Map<String,Object> user(User u){return Api.map("id",u.getId().toString(),"account",u.getAccount(),"name",u.getName(),"role",u.getRole());}
    public static Map<String,Object> room(Room r){return Api.map("id",r.getId().toString(),"name",r.getName(),"location",r.getLocation(),"openTime",r.getOpenTime().toString(),"closeTime",r.getCloseTime().toString(),"enabled",r.getEnabled(),"deleted",r.getDeleted());}
    public static Map<String,Object> seat(Seat s){return Api.map("id",s.getId().toString(),"roomId",s.getRoomId().toString(),"seatNo",s.getSeatNo(),"seatType",s.getSeatType(),"hasPower",s.getHasPower(),"isQuiet",s.getIsQuiet(),"deleted",s.getDeleted());}
}
