package cn.edu.studyroom;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.service.RecommendationEngine;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecommendationEngineTest {
    final RecommendationEngine engine=new RecommendationEngine();
    Room room(long id){Room r=new Room();r.setId(id);r.setName("Room "+id);return r;}
    Seat seat(long id,long room,String type,boolean power,boolean quiet){Seat s=new Seat();s.setId(id);s.setRoomId(room);s.setSeatNo(Long.toString(id));s.setSeatType(type);s.setHasPower(power);s.setIsQuiet(quiet);s.setDeleted(false);return s;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> ranked(Map<String,Object> result){return (List<Map<String,Object>>)result.get("seats");}
    @Test void producesSpecifiedScoreAndAnEvidenceBasedReason(){
        List<Behavior> history=new ArrayList<>();
        for(int i=0;i<10;i++){Behavior b=new Behavior();b.setRoomId(i<6?1L:2L);b.setSeatTypeSnapshot(i<7?"WINDOW":"AISLE");b.setHasPowerSnapshot(i<8);b.setIsQuietSnapshot(i<5);history.add(b);}
        var result=engine.rank(List.of(seat(1,1,"WINDOW",true,true)),Map.of(1L,room(1)),history);
        assertThat(ranked(result).getFirst()).containsEntry("score",new BigDecimal("66.00")).containsEntry("reason","你有10次有效历史预约，其中6次选择该自习室");
    }
    @Test void coldStartIsStableLimitedAndHandlesEmptyResults(){
        List<Seat> candidates=new ArrayList<>();for(int i=9;i>=1;i--)candidates.add(seat(i,1,"MIDDLE",false,false));
        var result=engine.rank(candidates,Map.of(1L,room(1)),List.of());
        assertThat(result).containsEntry("mode","DEFAULT");
        assertThat(ranked(result)).extracting(s->s.get("seatId")).containsExactly("1","2","3","4","5");
        assertThat(ranked(engine.rank(List.of(),Map.of(),List.of()))).isEmpty();
    }
    @Test void countsFalseBooleanPreferencesAndSortsEqualScoresByResourceId(){
        Behavior b=new Behavior();b.setRoomId(3L);b.setSeatTypeSnapshot("WINDOW");b.setHasPowerSnapshot(false);b.setIsQuietSnapshot(false);
        var result=engine.rank(List.of(seat(9,2,"AISLE",true,true),seat(8,2,"AISLE",false,false),seat(10,1,"AISLE",false,false)),Map.of(1L,room(1),2L,room(2)),List.of(b));
        assertThat(ranked(result)).extracting(s->s.get("seatId")).containsExactly("10","8","9");
        assertThat(ranked(result).getFirst().get("score")).isEqualTo(new BigDecimal("30.00"));
        assertThat(ranked(result).getLast().get("reason")).isEqualTo("该座位在目标时段可预约，暂无匹配的历史偏好");
    }
}
