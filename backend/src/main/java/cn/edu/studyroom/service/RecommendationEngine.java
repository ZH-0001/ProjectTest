package cn.edu.studyroom.service;
import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.*;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RecommendationEngine {
    private static <T> Map<T,Long> count(List<Behavior> history,Function<Behavior,T> key){return history.stream().collect(Collectors.groupingBy(key,Collectors.counting()));}
    private record Ranked(Seat seat,long numerator,String reason){}
    public Map<String,Object> rank(List<Seat> seats,Map<Long,Room> rooms,List<Behavior> history){
        int n=history.size();var rc=count(history,Behavior::getRoomId);var tc=count(history,Behavior::getSeatTypeSnapshot);
        var pc=count(history,Behavior::getHasPowerSnapshot);var qc=count(history,Behavior::getIsQuietSnapshot);
        List<Ranked> ranked=new ArrayList<>();
        for(var s:seats){
            long[] counts={rc.getOrDefault(s.getRoomId(),0L),tc.getOrDefault(s.getSeatType(),0L),pc.getOrDefault(s.getHasPower(),0L),qc.getOrDefault(s.getIsQuiet(),0L)};
            int[] weights={4,3,2,1};long total=0,best=0;int index=0;
            for(int i=0;i<4;i++){long c=counts[i]*weights[i];total+=c;if(c>best){best=c;index=i;}}
            String reason=n==0?"暂无有效历史，按默认顺序展示目标时段可预约座位":best==0?"该座位在目标时段可预约，暂无匹配的历史偏好":
              "你有"+n+"次有效历史预约，其中"+counts[index]+"次选择"+switch(index){case 0->"该自习室";case 1->typeName(s.getSeatType())+"座位";case 2->s.getHasPower()?"有插座座位":"无插座座位";default->s.getIsQuiet()?"安静区座位":"非安静区座位";};
            ranked.add(new Ranked(s,total,reason));
        }
        ranked.sort(Comparator.comparingLong(Ranked::numerator).reversed().thenComparing(r->r.seat.getRoomId()).thenComparing(r->r.seat.getId()));
        var result=ranked.stream().limit(5).map(r->{var s=r.seat;var data=Views.seat(s);data.put("seatId",s.getId().toString());data.put("roomName",rooms.get(s.getRoomId()).getName());
            data.put("score",n==0?BigDecimal.ZERO.setScale(2):BigDecimal.valueOf(r.numerator).multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(n),2,RoundingMode.HALF_UP));data.put("reason",r.reason);return data;}).toList();
        var preferences=Api.map("rooms",distribution(rc,n,"roomId"),"seatTypes",distribution(tc,n,"seatType"),"power",distribution(pc,n,"hasPower"),"quiet",distribution(qc,n,"isQuiet"));
        return Api.map("mode",n==0?"DEFAULT":"PERSONALIZED","historyCount",n,"preferences",preferences,"seats",result);
    }
    private <T> List<Map<String,Object>> distribution(Map<T,Long> counts,int n,String field){
        return counts.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().toString())).map(e->Api.map(field,e.getKey() instanceof Long?e.getKey().toString():e.getKey(),"count",e.getValue(),"ratio",BigDecimal.valueOf(e.getValue()).divide(BigDecimal.valueOf(n),6,RoundingMode.HALF_UP))).toList();
    }
    private String typeName(String type){return switch(type){case "WINDOW"->"靠窗";case "AISLE"->"过道";default->"中间";};}
}
