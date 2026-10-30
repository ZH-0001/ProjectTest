package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.service.TimeRules;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AssistantQuota {
    private final Map<Long,Usage> users=new ConcurrentHashMap<>();
    private static final class Usage { LocalDate day; int count; Instant last; final Deque<Instant> minute=new ArrayDeque<>(); }
    public void acquire(long user,Instant now) {
        users.compute(user,(id,existing)->{
            var u=existing==null?new Usage():existing;
            var today=now.atOffset(TimeRules.OFFSET).toLocalDate();
            if(!today.equals(u.day)){u.day=today;u.count=0;}
            while(!u.minute.isEmpty() && !u.minute.getFirst().plusSeconds(60).isAfter(now))u.minute.removeFirst();
            Api.require(u.minute.size()<10 && u.count<200,429,"ASSISTANT_RATE_LIMITED","助手消息额度已达上限，请稍后重试");
            u.count++;u.minute.addLast(now);u.last=now;
            return u;
        });
    }
    public void clean(Instant now) {
        for(var id:users.keySet())users.computeIfPresent(id,(key,u)->u.last!=null && u.last.plusSeconds(86400).isBefore(now)?null:u);
    }
}
