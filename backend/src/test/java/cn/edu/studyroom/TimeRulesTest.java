package cn.edu.studyroom;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.service.TimeRules;
import java.time.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

class TimeRulesTest {
    private final LocalDateTime now=LocalDateTime.of(2026,10,15,8,0);
    private final TimeRules rules=new TimeRules(Clock.fixed(now.toInstant(TimeRules.OFFSET),TimeRules.OFFSET));
    static Stream<Arguments> invalidTimes(){return Stream.of(
        Arguments.of("2026-10-15T08:00","2026-10-15T09:00"),
        Arguments.of("2026-10-16T10:00","2026-10-16T09:00"),
        Arguments.of("2026-10-16T10:00","2026-10-16T10:00"),
        Arguments.of("2026-10-16T10:15","2026-10-16T11:00"),
        Arguments.of("2026-10-16T10:00:01","2026-10-16T11:00"),
        Arguments.of("2026-10-16T10:00:00.000000001","2026-10-16T11:00"),
        Arguments.of("2026-10-16T10:00","2026-10-16T14:30"),
        Arguments.of("2026-10-16T23:30","2026-10-17T00:00"),
        Arguments.of("2026-10-22T08:00","2026-10-22T08:30")
    );}
    @ParameterizedTest @MethodSource("invalidTimes")
    void rejectsInvalidIntervals(String start,String end){assertThatThrownBy(()->rules.validate(new TimeRules.Interval(LocalDateTime.parse(start),LocalDateTime.parse(end)),now)).isInstanceOf(Api.Failure.class);}
    @Test void acceptsBoundaryDurationsAndFutureLimit(){
        for(int minutes:new int[]{30,240})rules.validate(new TimeRules.Interval(now.plusDays(1),now.plusDays(1).plusMinutes(minutes)),now);
        rules.validate(new TimeRules.Interval(now.plusDays(7).minusMinutes(30),now.plusDays(7)),now);
    }
    @Test void validatesOffsetOpeningAndStateBoundaries(){
        assertThatThrownBy(()->rules.parse(now.atOffset(ZoneOffset.UTC),now.plusHours(1).atOffset(ZoneOffset.UTC))).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(()->rules.opening(LocalTime.of(8,15),LocalTime.of(22,0))).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(()->rules.opening(LocalTime.of(22,0),LocalTime.of(8,0))).isInstanceOf(Api.Failure.class);
        rules.opening(LocalTime.of(8,0),LocalTime.of(22,0));
        var end=now.plusHours(1);
        assertThat(rules.state("ACTIVE",now,end,now.minusNanos(1))).isEqualTo("PENDING");
        assertThat(rules.state("ACTIVE",now,end,now)).isEqualTo("IN_PROGRESS");
        assertThat(rules.state("ACTIVE",now,end,end)).isEqualTo("ENDED");
        assertThat(rules.state("CANCELLED",now,end,now.minusDays(1))).isEqualTo("CANCELLED");
    }
}
