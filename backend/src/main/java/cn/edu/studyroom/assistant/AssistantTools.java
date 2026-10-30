package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.domain.Entities.Room;
import cn.edu.studyroom.mapper.RoomMapper;
import cn.edu.studyroom.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.*;
import java.time.Clock;
import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/** The entire dispatch surface is this explicit switch; model names never resolve Spring beans. */
@Service
public class AssistantTools {
    private final RoomMapper rooms;
    private final RecommendationService recommendations;
    private final BookingService bookings;
    private final Clock clock;
    private final ObjectMapper json;
    public AssistantTools(RoomMapper rooms, RecommendationService recommendations, BookingService bookings, Clock clock, ObjectMapper json) {
        this.rooms=rooms;this.recommendations=recommendations;this.bookings=bookings;this.clock=clock;this.json=json;
    }
    public static class Draft {
        public SearchCriteria criteria;
        public final Set<String> knownRooms;
        public final List<AssistantCard> cards = new ArrayList<>();
        public Draft(SearchCriteria criteria, Set<String> knownRooms) { this.criteria=criteria;this.knownRooms=new LinkedHashSet<>(knownRooms); }
    }
    public static final String CHANGE_SCHEMA = """
        {"type":"object","properties":{"changes":{"type":"array","maxItems":6,"items":{
          "type":"object","properties":{
            "field":{"type":"string","enum":["startTime","endTime","roomId","seatType","hasPower","isQuiet"]},
            "operation":{"type":"string","enum":["SET","CLEAR"]},
            "value":{"description":"SET时提供；时间为+08:00的ISO字符串；ID和类型为字符串，插座/安静区为boolean","anyOf":[{"type":"string"},{"type":"boolean"}]},
            "strength":{"type":"string","enum":["HARD","SOFT"]}},
          "required":["field","operation"],"additionalProperties":false}}},"required":["changes"],"additionalProperties":false}
        """;
    public List<ToolCallback> definitions() {
        return List.of(definition("listRooms","查询开放自习室及ID。可按名称或位置query搜索；默认第一页，每页20条。roomId必须从此工具结果选取。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"page\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}"),
            definition("searchSeats","按当前会话条件搜索可预约座位。只用changes增量更新条件，空数组沿用。SET属性须strength，SET时间禁止strength；CLEAR禁止value/strength。不完整时返回需补充字段。",CHANGE_SCHEMA),
            definition("myReservations","查询当前学生自己的预约。status可选PENDING、IN_PROGRESS、ENDED、CANCELLED；每页10条。",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"PENDING\",\"IN_PROGRESS\",\"ENDED\",\"CANCELLED\"]},\"page\":{\"type\":\"integer\",\"minimum\":1}},\"additionalProperties\":false}"));
    }
    private ToolCallback definition(String name,String description,String schema) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build(); }
            public String call(String input) { throw new IllegalStateException("Tools require an authenticated manual dispatch context"); }
        };
    }
    public Object execute(String name, String arguments, long user, Draft draft) {
        Api.require(Set.of("listRooms","searchSeats","myReservations").contains(name),403,"ASSISTANT_TOOL_FORBIDDEN","拒绝未声明的工具");
        Api.require(arguments!=null && arguments.length()<=10000,400,"ASSISTANT_PROTOCOL_ERROR","工具参数格式无效");
        JsonNode args;
        try { args=json.readTree(arguments); } catch (Exception ex) { throw new Api.Failure(502,"ASSISTANT_PROTOCOL_ERROR","模型返回的工具参数不是有效JSON"); }
        Api.require(args!=null && args.isObject(),502,"ASSISTANT_PROTOCOL_ERROR","工具参数须为对象");
        try {
            return switch(name) {
                case "listRooms" -> list(args,draft);
                case "searchSeats" -> search(args,user,draft);
                case "myReservations" -> mine(args,user,draft);
                default -> throw new IllegalStateException();
            };
        } catch (Api.Failure ex) {
            // Business validation is data for the next model step, never permission to relax conditions.
            return Api.map("error",ex.code,"message",ex.getMessage(),"criteriaApplied",draft.criteria.view());
        }
    }
    private void fields(JsonNode args, Set<String> allowed) {
        args.fieldNames().forEachRemaining(k->Api.require(allowed.contains(k),400,"INVALID_PARAMETER","工具参数包含未知字段"));
    }
    private int page(JsonNode args) {
        Api.require(!args.has("page") || args.get("page").isInt(),400,"INVALID_PARAMETER","page须为整数");
        int p=args.path("page").asInt(1);Api.require(p>=1 && p<=10000,400,"INVALID_PARAMETER","分页无效");return p;
    }
    private Object list(JsonNode args,Draft draft) {
        fields(args,Set.of("query","page")); int p=page(args);
        Api.require(!args.has("query") || args.get("query").isTextual(),400,"INVALID_PARAMETER","query须为字符串");
        String query=args.path("query").asText("").trim();Api.require(query.length()<=100,400,"INVALID_PARAMETER","查询词过长");
        var q=new QueryWrapper<Room>().eq("enabled",true).eq("deleted",false);
        if(!query.isBlank())q.and(w->w.like("name",query).or().like("location",query));
        long total=rooms.selectCount(q);
        var data=rooms.selectList(q.orderByAsc("id").last("LIMIT 20 OFFSET "+((long)(p-1)*20))).stream().map(Views::room).toList();
        data.forEach(r->draft.knownRooms.add(r.get("id").toString()));
        while(draft.knownRooms.size()>200)draft.knownRooms.remove(draft.knownRooms.iterator().next());
        return Api.map("records",data,"total",total,"page",p,"size",20);
    }
    @SuppressWarnings("unchecked")
    private Object search(JsonNode args,long user,Draft draft) {
        fields(args,Set.of("changes"));
        var next=draft.criteria.merge(args.get("changes"));
        var room=next.fields().get("roomId");
        Api.require(room==null || draft.knownRooms.contains(room.value().toString()),400,"INVALID_PARAMETER","请先查询并确认自习室ID");
        if(next.start()==null || next.end()==null) {
            draft.criteria=next;
            draft.cards.removeIf(c->c.kind.equals("CANDIDATE"));
            return Api.map("needsClarification",true,"message","请补充明确的预约开始和结束时间","criteriaApplied",next.view());
        }
        var result=recommendations.recommend(user,next.start(),next.end(),next);
        draft.criteria=next;
        // A later search in the same turn supersedes its previous candidate cards.
        draft.cards.removeIf(c->c.kind.equals("CANDIDATE"));
        for(var seat:(List<Map<String,Object>>)result.get("seats")) {
            var data=new LinkedHashMap<>(seat);data.put("startTime",next.start().toString());data.put("endTime",next.end().toString());
            data.put("historyCount",result.get("historyCount"));
            draft.cards.add(new AssistantCard("CANDIDATE",user,clock.instant(),data));
        }
        var relaxed=next.fields().entrySet().stream().filter(e->!e.getKey().endsWith("Time")).map(Map.Entry::getKey).toList();
        return Api.map("modelSummary",result,"criteriaApplied",next.view(),"relaxable",relaxed);
    }
    @SuppressWarnings("unchecked")
    private Object mine(JsonNode args,long user,Draft draft) {
        fields(args,Set.of("status","page"));int p=page(args);
        Api.require(!args.has("status") || args.get("status").isTextual(),400,"INVALID_PARAMETER","status须为字符串");
        var result=bookings.list(user,p,10,args.path("status").asText(""),null,null,null);
        draft.cards.removeIf(c->c.kind.equals("RESERVATION"));
        for(var record:(List<Map<String,Object>>)result.get("records")) {
            var data=new LinkedHashMap<>(record);data.put("reserveId",record.get("id"));
            draft.cards.add(new AssistantCard("RESERVATION",user,clock.instant(),data));
        }
        return result;
    }
}
