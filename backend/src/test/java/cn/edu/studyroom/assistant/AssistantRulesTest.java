package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.service.RecommendationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AssistantRulesTest {
    final ObjectMapper json=new ObjectMapper();
    SearchCriteria patch(SearchCriteria before,String changes) throws Exception {return before.merge(json.readTree(changes));}
    static void failure(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.code).isEqualTo(code));
    }
    @Test void patchesPreserveOtherFieldsAndHandleFalseAndClear() throws Exception {
        var a=patch(SearchCriteria.empty(),"""
          [{"field":"hasPower","operation":"SET","value":false,"strength":"SOFT"},
           {"field":"startTime","operation":"SET","value":"2026-10-31T14:00:00+08:00"}]
          """);
        var b=patch(a,"""
          [{"field":"startTime","operation":"SET","value":"2026-10-31T15:00:00+08:00"}]
          """);
        assertThat(b.fields().get("hasPower").value()).isEqualTo(false);
        assertThat(b.start().getHour()).isEqualTo(15);assertThat(a.start().getHour()).isEqualTo(14);
        assertThat(patch(b,"[{\"field\":\"hasPower\",\"operation\":\"CLEAR\"}]").hasPreferences()).isFalse();
    }
    @Test void rejectsUnknownDuplicateTypesStrengthAndIdentityAtomically() throws Exception {
        String[] invalid={
          "[{\"field\":\"userId\",\"operation\":\"SET\",\"value\":\"1\",\"strength\":\"HARD\"}]",
          "[{\"field\":\"hasPower\",\"operation\":\"SET\",\"value\":\"false\",\"strength\":\"SOFT\"}]",
          "[{\"field\":\"hasPower\",\"operation\":\"CLEAR\",\"value\":false}]",
          "[{\"field\":\"hasPower\",\"operation\":\"SET\",\"value\":true}]",
          "[{\"field\":\"seatType\",\"operation\":\"SET\",\"value\":\"OTHER\",\"strength\":\"HARD\"}]",
          "[{\"field\":\"seatType\",\"operation\":\"CLEAR\",\"extra\":true}]",
          "[{\"field\":\"seatType\",\"operation\":\"CLEAR\"},{\"field\":\"seatType\",\"operation\":\"CLEAR\"}]",
          "[{\"field\":\"startTime\",\"operation\":\"SET\",\"value\":\"2026-10-31T14:00:00+08:00\",\"strength\":\"HARD\"}]"};
        for(String input:invalid)failure("INVALID_PARAMETER",()->patch(SearchCriteria.empty(),input));
        failure("INVALID_TIME",()->patch(SearchCriteria.empty(),"[{\"field\":\"startTime\",\"operation\":\"SET\",\"value\":\"2026-10-31T14:00:00Z\"}]"));
        var original=SearchCriteria.empty();failure("INVALID_PARAMETER",()->patch(original,"[{\"field\":\"hasPower\",\"operation\":\"SET\",\"value\":true,\"strength\":\"SOFT\"},{\"field\":\"unknown\",\"operation\":\"CLEAR\"}]"));
        assertThat(original.fields()).isEmpty();
    }
    Seat seat(long id,boolean power){var s=new Seat();s.setId(id);s.setRoomId(1L);s.setSeatNo("S"+id);s.setSeatType("WINDOW");s.setHasPower(power);s.setIsQuiet(true);return s;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> seats(Map<String,Object> result){return (List<Map<String,Object>>)result.get("seats");}
    @Test void explicitOverridesOnlyOneHistoricalDimension() throws Exception {
        var h=new Behavior();h.setRoomId(1L);h.setSeatTypeSnapshot("WINDOW");h.setHasPowerSnapshot(false);h.setIsQuietSnapshot(true);
        var r=new Room();r.setId(1L);r.setName("室");
        var criteria=patch(SearchCriteria.empty(),"[{\"field\":\"hasPower\",\"operation\":\"SET\",\"value\":true,\"strength\":\"SOFT\"}]");
        var result=seats(new RecommendationEngine().rank(List.of(seat(1,false),seat(2,true)),Map.of(1L,r),List.of(h),criteria));
        assertThat(result).extracting(s->s.get("seatId")).containsExactly("2","1");
        assertThat(result.getFirst().get("score")).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.getLast().get("score")).isEqualTo(new BigDecimal("80.00"));
        var breakdown=(List<Map<String,Object>>)result.getFirst().get("breakdown");
        assertThat(breakdown).extracting(s->s.get("source")).containsExactly("HISTORY","HISTORY","EXPLICIT","HISTORY");
        assertThat(breakdown.get(2)).containsEntry("matched",true).containsEntry("score",new BigDecimal("20.00"));
    }
    @Test void hardFilterRunsBeforeTopFiveAndColdStartHasExplicitAndNoneSources() throws Exception {
        var r=new Room();r.setId(1L);r.setName("室");var candidates=new ArrayList<Seat>();for(int i=1;i<=6;i++)candidates.add(seat(i,i==6));
        var c=patch(SearchCriteria.empty(),"[{\"field\":\"hasPower\",\"operation\":\"SET\",\"value\":true,\"strength\":\"HARD\"}]");
        var result=seats(new RecommendationEngine().rank(candidates,Map.of(1L,r),List.of(),c));
        assertThat(result).hasSize(1);assertThat(result.getFirst()).containsEntry("seatId","6").containsEntry("score",new BigDecimal("20.00"));
        assertThat((List<Map<String,Object>>)result.getFirst().get("breakdown")).extracting(s->s.get("source")).containsExactly("NONE","NONE","EXPLICIT","NONE");
    }
    @Test void budgetEnforcesBothLimitsDeadlineAndCancellation() {
        var valid=new AtomicBoolean(true);var b=new TurnBudget(Duration.ofSeconds(1),valid::get);
        for(int i=0;i<4;i++)b.model();failure("ASSISTANT_LIMIT_REACHED",b::model);assertThat(b.models()).isEqualTo(4);
        for(int i=0;i<8;i++)b.tool();failure("ASSISTANT_LIMIT_REACHED",b::tool);assertThat(b.tools()).isEqualTo(8);
        valid.set(false);failure("ASSISTANT_CANCELLED",b::remaining);
        failure("ASSISTANT_TIMEOUT",()->new TurnBudget(Duration.ZERO,()->true).remaining());
    }
    @Test void quotaIsAtomicRollingAndSharedAcrossSessions() throws Exception {
        var q=new AssistantQuota();var now=Instant.parse("2026-10-30T02:00:00Z");
        try(var pool=Executors.newFixedThreadPool(16)) {
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<40;i++)tasks.add(pool.submit(()->{try{q.acquire(1,now);return true;}catch(Api.Failure e){return false;}}));
            int accepted=0;for(var t:tasks)if(t.get())accepted++;assertThat(accepted).isEqualTo(10);
        }
        q.acquire(2,now);failure("ASSISTANT_RATE_LIMITED",()->q.acquire(1,now.plusSeconds(59)));q.acquire(1,now.plusSeconds(60));
    }
    @Test void dailyQuotaResetsAtBeijingMidnight() {
        var q=new AssistantQuota();var now=Instant.parse("2026-10-30T10:00:00Z");
        for(int i=0;i<200;i++)q.acquire(1,now.plusSeconds(i*61L));
        failure("ASSISTANT_RATE_LIMITED",()->q.acquire(1,now.plusSeconds(200*61L)));
        q.acquire(1,Instant.parse("2026-10-30T16:00:00Z"));
    }
    @Test void cardsAndResultCachesAreBoundedAndExpire() {
        var m=new AssistantMemory(1);var now=Instant.now();
        for(int i=0;i<110;i++){var c=new AssistantCard("CANDIDATE",1,now,Map.of());m.cards.put(c.id,c);}
        for(int i=0;i<210;i++){var p=new AssistantMemory.Pending("x",0,Duration.ofMinutes(1),m);p.finished=now;m.results.put("p"+i,p);}
        m.clean(now);assertThat(m.cards).hasSize(100);assertThat(m.results).hasSize(200);
        m.clean(now.plusSeconds(1800));assertThat(m.cards).isEmpty();assertThat(m.results).isEmpty();
        var c=new AssistantCard("CANDIDATE",1,now,Map.of());assertThat(c.expired(now.plusSeconds(119))).isFalse();assertThat(c.expired(now.plusSeconds(120))).isTrue();
    }
}
