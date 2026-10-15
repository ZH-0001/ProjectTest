package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class TimeRules {
    public static final ZoneOffset OFFSET=ZoneOffset.ofHours(8);
    private final Clock clock;
    public TimeRules(Clock clock){this.clock=clock;}
    public LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),OFFSET);}
    public record Interval(LocalDateTime start,LocalDateTime end){}
    public Interval parse(OffsetDateTime start,OffsetDateTime end){
        Api.require(start!=null&&end!=null&&start.getOffset().equals(OFFSET)&&end.getOffset().equals(OFFSET),400,"INVALID_TIME","时间须使用北京时间偏移量+08:00");
        return new Interval(start.toLocalDateTime(),end.toLocalDateTime());
    }
    public void validate(Interval t,LocalDateTime now){
        long minutes=Duration.between(t.start,t.end).toMinutes();
        Api.require(t.start.isBefore(t.end)&&t.start.isAfter(now)&&!t.end.isAfter(now.plusDays(7))
          &&t.start.toLocalDate().equals(t.end.toLocalDate())&&slot(t.start.toLocalTime())&&slot(t.end.toLocalTime())
          &&minutes>=30&&minutes<=240,400,"INVALID_TIME","请选择未来7天内、同日且按半小时对齐的30分钟至4小时时段");
    }
    public static boolean slot(LocalTime t){return t!=null&&t.getMinute()%30==0&&t.getSecond()==0&&t.getNano()==0;}
    public void opening(LocalTime open,LocalTime close){
        Api.require(slot(open)&&slot(close)&&open.isBefore(close),400,"INVALID_TIME","开放时间须同日、按半小时对齐且开始早于结束");
    }
    public boolean covers(Interval t,LocalTime open,LocalTime close){return !t.start.toLocalTime().isBefore(open)&&!t.end.toLocalTime().isAfter(close);}
    public String state(String persisted,LocalDateTime start,LocalDateTime end,LocalDateTime now){
        if("CANCELLED".equals(persisted)) return "CANCELLED";
        if(now.isBefore(start)) return "PENDING";
        return now.isBefore(end)?"IN_PROGRESS":"ENDED";
    }
    public static String format(LocalDateTime t){return t==null?null:t.atOffset(OFFSET).toString();}
}
