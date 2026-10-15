package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatisticsService {
    private final JdbcTemplate jdbc;
    public StatisticsService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> daily(LocalDate from,LocalDate to){
        BookingService.dateRange(from,to);
        Api.require(java.time.temporal.ChronoUnit.DAYS.between(from,to)<=366,400,"INVALID_PARAMETER","单次统计日期范围最多367天");
        var grouped=new HashMap<LocalDate,long[]>();
        jdbc.query("SELECT DATE(start_time) day,COUNT(*) total,SUM(CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END) cancelled FROM reserve WHERE start_time>=? AND start_time<? GROUP BY DATE(start_time)",rs->{grouped.put(rs.getDate("day").toLocalDate(),new long[]{rs.getLong("total"),rs.getLong("cancelled")});},from.atStartOfDay(),to.plusDays(1).atStartOfDay());
        List<Map<String,Object>> result=new ArrayList<>();for(var day=from;!day.isAfter(to);day=day.plusDays(1)){long[] count=grouped.getOrDefault(day,new long[]{0,0});result.add(Api.map("date",day.toString(),"totalCount",count[0],"cancelledCount",count[1],"activeCount",count[0]-count[1]));}return result;
    }
}
