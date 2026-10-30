package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.api.Requests;
import cn.edu.studyroom.service.TimeRules;
import java.time.*;
import java.util.*;

public final class AssistantCard {
    public final String id = UUID.randomUUID().toString();
    public final String bookingRequestId = UUID.randomUUID().toString();
    public final String kind;
    public final long userId;
    public final Instant generatedAt;
    public final Map<String,Object> data;
    private volatile Map<String,Object> reservation;
    public AssistantCard(String kind, long userId, Instant generatedAt, Map<String,Object> data) {
        this.kind=kind;this.userId=userId;this.generatedAt=generatedAt;
        this.data=Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
    public boolean expired(Instant now) { return !now.isBefore(generatedAt.plusSeconds(120)); }
    public Requests.Booking booking() {
        return new Requests.Booking(Long.parseLong(data.get("seatId").toString()),OffsetDateTime.parse(data.get("startTime").toString()),
            OffsetDateTime.parse(data.get("endTime").toString()),bookingRequestId);
    }
    public void booked(Map<String,Object> result) { reservation = Map.copyOf(result.entrySet().stream().filter(e->e.getValue()!=null)
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue))); }
    public Map<String,Object> view(Instant now) {
        var result = new LinkedHashMap<>(data);
        result.putAll(Api.map("cardId",id,"kind",kind,"generatedAt",generatedAt.atOffset(TimeRules.OFFSET).toString(),
            "expiresAt",generatedAt.plusSeconds(120).atOffset(TimeRules.OFFSET).toString(),
            "actionState",reservation!=null?"BOOKED":expired(now)?"EXPIRED":"READY","reservation",reservation));
        return result;
    }
}
