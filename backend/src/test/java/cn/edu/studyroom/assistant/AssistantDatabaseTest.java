package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.*;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import cn.edu.studyroom.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static cn.edu.studyroom.assistant.AssistantRulesTest.failure;
import static cn.edu.studyroom.assistant.AssistantServiceTest.*;

@SpringBootTest(properties={"app.seed-enabled=false","app.assistant.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@Import(AssistantDatabaseTest.TestTime.class)
class AssistantDatabaseTest {
    static final Instant BASE=Instant.parse("2026-10-30T00:00:00Z");
    static Properties config() throws Exception {
        var p=new Properties();var path=Path.of(".env.local");if(!Files.exists(path))path=Path.of("backend/.env.local");
        if(Files.exists(path))try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(reader);}return p;
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        var p=config();String url=System.getenv().getOrDefault("DB_TEST_URL",p.getProperty("DB_TEST_URL",""));
        if(!url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")||url.equals(p.getProperty("DB_URL")))throw new IllegalStateException("Use a dedicated DB_TEST_URL ending in _test");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME",p.getProperty("DB_USERNAME","root")));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("DB_PASSWORD",p.getProperty("DB_PASSWORD","")));
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(BASE);
        public ZoneId getZone(){return TimeRules.OFFSET;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}
    }
    @TestConfiguration static class TestTime {@Bean @Primary MutableClock assistantTestClock(){return new MutableClock();}}
    @MockitoBean ChatModel model;
    @Autowired AssistantService assistant;@Autowired AssistantTools tools;@Autowired BookingService bookings;
    @Autowired MutableClock clock;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    @Autowired UserMapper users;@Autowired RoomMapper rooms;@Autowired SeatMapper seats;@Autowired ReserveMapper reserves;@Autowired BehaviorMapper behaviors;@Autowired PasswordEncoder passwords;
    User user,admin;Room room;Seat seat;
    final List<MockHttpSession> sessions=new ArrayList<>();
    @BeforeEach void fixtures(){
        clock.time.set(BASE);String prefix="as_"+UUID.randomUUID().toString().substring(0,8);String hash=passwords.encode("Test-only-123!");
        user=new User();user.setAccount(prefix);user.setName("助手验收");user.setRole("STUDENT");user.setPasswordHash(hash);users.insert(user);
        admin=new User();admin.setAccount(prefix+"a");admin.setName("管理员");admin.setRole("ADMIN");admin.setPasswordHash(hash);users.insert(admin);
        room=new Room();room.setName(prefix);room.setLocation("助手测试空间");room.setOpenTime(LocalTime.of(8,0));room.setCloseTime(LocalTime.of(22,0));room.setEnabled(true);room.setDeleted(false);rooms.insert(room);
        seat=new Seat();seat.setRoomId(room.getId());seat.setSeatNo("01");seat.setSeatType("WINDOW");seat.setHasPower(true);seat.setIsQuiet(true);seat.setDeleted(false);seats.insert(seat);
    }
    @AfterEach void cleanup(){
        sessions.forEach(s->{if(!s.isInvalid())s.invalidate();});
        behaviors.delete(new QueryWrapper<Behavior>().eq("user_id",user.getId()));reserves.delete(new QueryWrapper<Reserve>().eq("user_id",user.getId()));
        seats.delete(new QueryWrapper<Seat>().eq("room_id",room.getId()));rooms.deleteById(room.getId());users.deleteByIds(List.of(user.getId(),admin.getId()));
    }
    record Client(MockHttpSession session,String csrf){}
    Client login(User u) throws Exception {
        var initial=mvc.perform(get("/user/csrf")).andReturn();var s=(MockHttpSession)initial.getRequest().getSession();sessions.add(s);
        String token=json.readTree(initial.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        mvc.perform(post("/user/login").session(s).header("X-CSRF-Token",token).contentType("application/json").content(json.writeValueAsString(Api.map("account",u.getAccount(),"password","Test-only-123!")))).andExpect(status().isOk());
        var next=mvc.perform(get("/user/csrf").session(s)).andReturn();return new Client(s,json.readTree(next.getResponse().getContentAsString()).path("data").path("csrfToken").asText());
    }
    ResultActions postJson(Client c,String path,Object body) throws Exception {return mvc.perform(post(path).session(c.session).header("X-CSRF-Token",c.csrf).contentType("application/json").content(json.writeValueAsString(body)));}
    String searchArguments(int hour) throws Exception {return json.writeValueAsString(Api.map("changes",List.of(
        Api.map("field","startTime","operation","SET","value","2026-10-31T"+hour+":00:00+08:00"),
        Api.map("field","endTime","operation","SET","value","2026-10-31T16:00:00+08:00"),
        Api.map("field","roomId","operation","SET","value",room.getId().toString(),"strength","HARD"),
        Api.map("field","hasPower","operation","SET","value",true,"strength","SOFT"))));}
    ChatResponse call(String name,String arguments){return new ChatResponse(List.of(new Generation(org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,arguments))).build())));}
    void scriptSearch(int hour) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(call("listRooms",json.writeValueAsString(Map.of("query",room.getName()))),call("searchSeats",searchArguments(hour)),text("找到一个符合条件的座位，请通过卡片确认。"));
    }
    @SuppressWarnings("unchecked") Map<String,Object> firstCard(Map<String,Object> response){return ((List<Map<String,Object>>)response.get("cards")).getFirst();}
    @Test void httpSearchCardsBookReplayExpiryAndCrossSessionProtection() throws Exception {
        var c=login(user);scriptSearch(14);String requestId=UUID.randomUUID().toString();
        var payload=Api.map("message","明天14点到16点，必须在"+room.getName()+"，最好有插座","requestId",requestId);
        var response=postJson(c,"/assistant/chat",payload).andExpect(status().isOk()).andExpect(jsonPath("$.data.cards[0].breakdown[2].source").value("EXPLICIT")).andReturn();
        String card=json.readTree(response.getResponse().getContentAsString()).path("data").path("cards").get(0).path("cardId").asText();
        postJson(c,"/assistant/chat",payload).andExpect(status().isOk());verify(model,times(3)).call(any(Prompt.class));
        var otherSession=login(user);postJson(otherSession,"/assistant/book",Map.of("cardId",card)).andExpect(status().isNotFound());
        var booked=postJson(c,"/assistant/book",Map.of("cardId",card)).andExpect(status().isOk()).andReturn();String id=json.readTree(booked.getResponse().getContentAsString()).path("data").path("id").asText();
        clock.time.set(BASE.plusSeconds(120));postJson(c,"/assistant/book",Map.of("cardId",card)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        bookings.cancel(user.getId(),Long.parseLong(id));postJson(c,"/assistant/book",Map.of("cardId",card)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("user_id",user.getId()))).isEqualTo(1);
        scriptSearch(14);var fresh=assistant.chat(c.session,user.getId(),"重新查询",UUID.randomUUID().toString());String freshCard=firstCard(fresh).get("cardId").toString();
        clock.time.set(BASE.plusSeconds(240));postJson(c,"/assistant/book",Map.of("cardId",freshCard)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_CARD_EXPIRED"));
        clock.time.set(BASE.plusSeconds(1920));postJson(c,"/assistant/book",Map.of("cardId",freshCard)).andExpect(status().isNotFound());
    }
    @Test void cardBookingRechecksSeatAndShowsUserReservations() throws Exception {
        var session=new MockHttpSession();sessions.add(session);scriptSearch(14);var card=firstCard(assistant.chat(session,user.getId(),"查座",UUID.randomUUID().toString()));
        var in=new Requests.Booking(seat.getId(),OffsetDateTime.parse("2026-10-31T14:00:00+08:00"),OffsetDateTime.parse("2026-10-31T16:00:00+08:00"),UUID.randomUUID().toString());
        bookings.add(admin.getId(),in);
        try{failure("SEAT_CONFLICT",()->assistant.book(session,user.getId(),card.get("cardId").toString()));}
        finally{var rows=reserves.selectList(new QueryWrapper<Reserve>().eq("user_id",admin.getId()));for(var r:rows)bookings.cancel(admin.getId(),r.getId());behaviors.delete(new QueryWrapper<Behavior>().eq("user_id",admin.getId()));reserves.delete(new QueryWrapper<Reserve>().eq("user_id",admin.getId()));}
        assistant.book(session,user.getId(),card.get("cardId").toString());
        when(model.call(any(Prompt.class))).thenReturn(call("myReservations","{\"status\":\"PENDING\"}"),text("查询到本人待开始的预约。"));
        var my=assistant.chat(session,user.getId(),"我的预约",UUID.randomUUID().toString());assertThat(firstCard(my)).containsEntry("kind","RESERVATION").containsEntry("status","PENDING");
    }
    @Test void toolsRejectUnknownNamesIdentityAndInvalidConditionBatches() throws Exception {
        var d=new AssistantTools.Draft(SearchCriteria.empty(),Set.of());
        failure("ASSISTANT_TOOL_FORBIDDEN",()->tools.execute("reserveAdd","{}",user.getId(),d));
        failure("ASSISTANT_PROTOCOL_ERROR",()->tools.execute("listRooms","broken",user.getId(),d));
        assertThat(((Map<?,?>)tools.execute("myReservations","{\"userId\":1}",user.getId(),d)).containsKey("error")).isTrue();
        var partial=(Map<?,?>)tools.execute("searchSeats","{\"changes\":[]}",user.getId(),d);assertThat(partial.get("needsClarification")).isEqualTo(true);
        when(model.call(any(Prompt.class))).thenReturn(call("deleteRoom","{}"));
        failure("ASSISTANT_TOOL_FORBIDDEN",()->assistant.chat(new MockHttpSession(),user.getId(),"删除房间",UUID.randomUUID().toString()));
        assertThat(rooms.selectById(room.getId())).isNotNull();
    }
    @Test void assistantHttpRequiresStudentCsrfAndStrictPayloads() throws Exception {
        mvc.perform(get("/assistant/conversation")).andExpect(status().isUnauthorized());var adminClient=login(admin);
        mvc.perform(get("/assistant/conversation").session(adminClient.session)).andExpect(status().isForbidden());
        var c=login(user);mvc.perform(post("/assistant/chat").session(c.session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        postJson(c,"/assistant/chat",Api.map("message","查座","requestId",UUID.randomUUID().toString(),"userId","1")).andExpect(status().isBadRequest());
        postJson(c,"/assistant/book",Api.map("cardId",UUID.randomUUID().toString(),"seatId",seat.getId().toString())).andExpect(status().isBadRequest());
    }
    @Test @EnabledIfSystemProperty(named="assistant.live",matches="true")
    void realDeepSeekSearchModifyExplainBookAndQuery() throws Exception {
        var p=config();String key=System.getenv().getOrDefault("DEEPSEEK_API_KEY",p.getProperty("DEEPSEEK_API_KEY",""));assertThat(!key.isBlank()).isTrue();
        var env=new MockEnvironment().withProperty("app.assistant.api-key",key).withProperty("app.assistant.model",p.getProperty("DEEPSEEK_MODEL","deepseek-flash")).withProperty("app.assistant.base-url",p.getProperty("DEEPSEEK_BASE_URL","https://api.deepseek.com"));
        var realModel=new AssistantConfiguration().assistantChatModel(env,json);
        var measurements=new ArrayList<Map<String,Object>>();
        ChatModel traced=prompt->{long begin=System.nanoTime();var response=realModel.call(prompt);var names=response.getResult().getOutput().getToolCalls().stream().map(c->c.name()).toList();
            measurements.add(Api.map("elapsedMs",(System.nanoTime()-begin)/1_000_000,"tokens",response.getMetadata().getUsage().getTotalTokens(),"tools",names));System.out.println("LIVE model tools="+names);return response;};
        var checkedTools=spy(tools);
        doAnswer(inv->{Object result=tools.execute(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3));
            if(result instanceof Map<?,?> map && map.containsKey("error"))System.out.println("LIVE tool validation="+map.get("error")+" "+map.get("message"));return result;
        }).when(checkedTools).execute(anyString(),anyString(),anyLong(),any());
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("live",traced);
        var s=new MockHttpSession();sessions.add(s);long start=System.nanoTime();
        try(varClose ignored=new varClose(new AssistantService(factory.getBeanProvider(ChatModel.class),checkedTools,json,clock,bookings,reserves))) {
            var live=ignored.service;var first=live.chat(s,user.getId(),"帮我查询2026年10月31日14点到16点的座位，必须在名为“"+room.getName()+"”的自习室，最好有插座。",UUID.randomUUID().toString());
            assertThat(firstCard(first)).containsEntry("roomId",room.getId().toString()).containsEntry("hasPower",true);
            var second=live.chat(s,user.getId(),"开始时间改为15点，其余条件不变，请重新查询。",UUID.randomUUID().toString());
            var card=firstCard(second);assertThat(OffsetDateTime.parse(card.get("startTime").toString()).getHour()).isEqualTo(15);
            var state=(Map<?,?>)second.get("conversationState");assertThat(state.keySet().containsAll(List.of("roomId","hasPower","endTime"))).isTrue();
            var explain=live.chat(s,user.getId(),"解释一下刚才这张卡片的四项得分，哪些来自本次偏好，哪些暂无依据？不要重新查询。",UUID.randomUUID().toString());assertThat(explain.get("reply").toString()).isNotBlank();
            live.book(s,user.getId(),card.get("cardId").toString());
            var own=live.chat(s,user.getId(),"查询我待开始的预约。",UUID.randomUUID().toString());assertThat(firstCard(own)).containsEntry("kind","RESERVATION").containsEntry("status","PENDING");
            var report=Api.map("verifiedAt",Instant.now().toString(),"model",p.getProperty("DEEPSEEK_MODEL","deepseek-flash"),"turns",4,"elapsedMs",(System.nanoTime()-start)/1_000_000,"search",true,"modify",true,"explain",true,"book",true,"myReservations",true,
                "modelCalls",measurements.size(),"totalTokens",measurements.stream().mapToInt(m->((Number)m.get("tokens")).intValue()).sum(),"requests",measurements);
            Files.writeString(Path.of("target/assistant-live-report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("LIVE ASSISTANT PASS: search, modify, explanation, card booking and own reservations; target/assistant-live-report.json");
        }
    }
    record varClose(AssistantService service) implements AutoCloseable {public void close(){service.close();}}
}
