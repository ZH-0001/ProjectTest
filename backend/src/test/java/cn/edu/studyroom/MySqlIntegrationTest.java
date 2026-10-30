package cn.edu.studyroom;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.seed-enabled=false","app.assistant.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@Import(MySqlIntegrationTest.TestTime.class)
class MySqlIntegrationTest {
    static final LocalDateTime BASE=LocalDateTime.of(2026,10,15,8,0);
    static final String PASSWORD="Test-only-123!";
    @DynamicPropertySource static void testDatabase(DynamicPropertyRegistry registry) throws Exception {
        var props=new Properties();var file=Path.of(".env.local");if(!Files.exists(file))file=Path.of("backend/.env.local");
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){props.load(reader);}
        String url=System.getenv().getOrDefault("DB_TEST_URL",props.getProperty("DB_TEST_URL",""));
        if(!url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")||url.equals(props.getProperty("DB_URL")))
            throw new IllegalStateException("DB_TEST_URL must name a dedicated database ending in _test, distinct from DB_URL");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME",props.getProperty("DB_USERNAME","root")));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("DB_PASSWORD",props.getProperty("DB_PASSWORD","")));
    }
    public static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(BASE.toInstant(TimeRules.OFFSET));
        void set(LocalDateTime value){time.set(value.toInstant(TimeRules.OFFSET));}
        @Override public ZoneId getZone(){return TimeRules.OFFSET;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return time.get();}
    }
    @TestConfiguration static class TestTime {@Bean @Primary MutableClock testClock(){return new MutableClock();}}
    @Autowired MutableClock clock;
    @Autowired UserMapper users; @Autowired RoomMapper rooms; @Autowired SeatMapper seats; @Autowired ReserveMapper reserves;
    @MockitoSpyBean BehaviorMapper behaviors;
    @Autowired BookingService booking; @Autowired AdminService admin; @Autowired CatalogService catalog;
    @Autowired RecommendationService recommendations; @Autowired StatisticsService statistics;
    @Autowired PasswordEncoder passwords; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    User student,other,administrator;Room room;Seat seat,second;
    final List<Long> userIds=new ArrayList<>(),roomIds=new ArrayList<>();
    String prefix;
    @BeforeEach void fixtures(){
        clock.set(BASE);prefix="t_"+UUID.randomUUID().toString().substring(0,8);reset(behaviors);
        String hash=passwords.encode(PASSWORD);
        student=user("one","STUDENT",hash);other=user("two","STUDENT",hash);administrator=user("admin","ADMIN",hash);
        room=createRoom("测试自习室");seat=createSeat(room,"01","WINDOW",true,true);second=createSeat(room,"02","AISLE",false,false);
    }
    User user(String account,String role,String hash){User u=new User();u.setAccount(prefix+account);u.setName(account);u.setRole(role);u.setPasswordHash(hash);users.insert(u);userIds.add(u.getId());return u;}
    Room createRoom(String name){Room r=new Room();r.setName(name);r.setLocation("集成测试");r.setEnabled(true);r.setDeleted(false);r.setOpenTime(LocalTime.of(8,0));r.setCloseTime(LocalTime.of(22,0));rooms.insert(r);roomIds.add(r.getId());return r;}
    Seat createSeat(Room r,String number,String type,boolean power,boolean quiet){Seat s=new Seat();s.setRoomId(r.getId());s.setSeatNo(number);s.setSeatType(type);s.setHasPower(power);s.setIsQuiet(quiet);s.setDeleted(false);seats.insert(s);return s;}
    @AfterEach void cleanup(){
        reset(behaviors);
        if(!roomIds.isEmpty()){
            behaviors.delete(new QueryWrapper<Behavior>().in("room_id",roomIds));reserves.delete(new QueryWrapper<Reserve>().in("room_id",roomIds));
            seats.delete(new QueryWrapper<Seat>().in("room_id",roomIds));rooms.deleteByIds(roomIds);
        }
        if(!userIds.isEmpty())users.deleteByIds(userIds);
    }
    Requests.Booking request(Seat s,int start,int end){return new Requests.Booking(s.getId(),BASE.plusDays(1).withHour(start).atOffset(TimeRules.OFFSET),BASE.plusDays(1).withHour(end).atOffset(TimeRules.OFFSET),UUID.randomUUID().toString());}
    long reserve(User u,Seat s,int start,int end){return Long.parseLong((String)booking.add(u.getId(),request(s,start,end)).get("id"));}
    List<String> race(List<Supplier<Object>> calls) throws Exception {
        var start=new CountDownLatch(1);var ready=new CountDownLatch(calls.size());
        try(var pool=Executors.newFixedThreadPool(calls.size())){
            var futures=calls.stream().map(call->pool.submit(()->{ready.countDown();start.await();try{call.get();return "OK";}catch(Api.Failure ex){return ex.code;}})).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();List<String> result=new ArrayList<>();for(var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }
    }
    @Test void createsBothRecordsAndReplaysAfterCancellationAndTimePassage(){
        var input=request(seat,10,12);var first=booking.add(student.getId(),input);long id=Long.parseLong((String)first.get("id"));
        assertThat(behaviors.selectCount(new QueryWrapper<Behavior>().eq("reserve_id",id))).isEqualTo(1);
        assertThat(booking.add(student.getId(),input).get("id")).isEqualTo(first.get("id"));
        booking.cancel(student.getId(),id);var cancelAgain=booking.cancel(student.getId(),id);
        assertThat(cancelAgain.get("cancelledAt")).isNotNull();clock.set(BASE.plusDays(2));
        assertThat(booking.add(student.getId(),input).get("status")).isEqualTo("CANCELLED");
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("user_id",student.getId()))).isEqualTo(1);
    }
    @Test void rejectsChangedIdempotencyPayload(){
        var in=request(seat,10,12);booking.add(student.getId(),in);
        assertFailure("IDEMPOTENCY_CONFLICT",()->booking.add(student.getId(),new Requests.Booking(second.getId(),in.startTime(),in.endTime(),in.requestId())));
    }
    @Test void concurrentSameSeatHasOneWinner() throws Exception {
        var result=race(List.of(()->booking.add(student.getId(),request(seat,10,12)),()->booking.add(other.getId(),request(seat,10,12))));
        assertThat(result).containsExactlyInAnyOrder("OK","SEAT_CONFLICT");
    }
    @Test void concurrentUserAcrossRoomsHasOneWinner() throws Exception {
        var elsewhere=createSeat(createRoom("另一间"),"01","MIDDLE",false,false);
        var result=race(List.of(()->booking.add(student.getId(),request(seat,10,12)),()->booking.add(student.getId(),request(elsewhere,10,12))));
        assertThat(result).containsExactlyInAnyOrder("OK","USER_CONFLICT");
    }
    @Test void concurrentIdenticalRequestIsIdempotent() throws Exception {
        var in=request(seat,10,12);
        assertThat(race(List.of(()->booking.add(student.getId(),in),()->booking.add(student.getId(),in)))).containsOnly("OK");
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("user_id",student.getId()))).isEqualTo(1);
        assertThat(behaviors.selectCount(new QueryWrapper<Behavior>().eq("user_id",student.getId()))).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"9,11","11,13","9,13","10,12","10,11"})
    void overlapCasesAreRejected(int start,int end){reserve(student,seat,10,12);assertFailure("SEAT_CONFLICT",()->reserve(other,seat,start,end));assertFailure("USER_CONFLICT",()->reserve(student,second,start,end));}
    @Test void adjacentReservationsAndReleasedSeatAreAllowed(){
        long first=reserve(student,seat,10,12);reserve(other,seat,12,14);booking.cancel(student.getId(),first);reserve(student,seat,10,12);
    }
    @Test void failedBehaviorWriteRollsBackReservation(){
        doThrow(new DataIntegrityViolationException("test-only failure")).when(behaviors).insert(any(Behavior.class));
        assertThatThrownBy(()->reserve(student,seat,10,12)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("user_id",student.getId()))).isZero();
    }
    @Test void cancelAtStartAndLaterIsRejectedAndOwnershipIsEnforced(){
        long id=reserve(student,seat,10,12);assertFailure("RESOURCE_NOT_FOUND",()->booking.cancel(other.getId(),id));
        clock.set(BASE.plusDays(1).withHour(10));assertFailure("INVALID_RESERVE_STATE",()->booking.cancel(student.getId(),id));
        clock.set(BASE.plusDays(1).withHour(12));assertFailure("INVALID_RESERVE_STATE",()->booking.cancel(student.getId(),id));
    }
    @Test void roomAndSeatAdministrationProtectExistingReservations(){
        long id=reserve(student,seat,10,12);
        assertFailure("RESOURCE_IN_USE",()->admin.status(room.getId(),false));assertFailure("RESOURCE_IN_USE",()->admin.deleteRoom(room.getId()));
        assertFailure("RESOURCE_IN_USE",()->admin.deleteSeat(seat.getId()));
        assertFailure("RESOURCE_IN_USE",()->admin.updateSeat(new Requests.SeatInput(seat.getId(),null,"01","AISLE",false,false)));
        assertFailure("RESOURCE_IN_USE",()->admin.updateRoom(new Requests.RoomInput(room.getId(),"新名称","位置",LocalTime.of(11,0),LocalTime.of(22,0))));
        admin.updateRoom(new Requests.RoomInput(room.getId(),"新名称","位置",LocalTime.of(9,0),LocalTime.of(21,0)));
        assertThat(booking.view(reserves.selectById(id),BASE).get("roomName")).isEqualTo("测试自习室");
    }
    @Test void seatNumbersRemainReservedAfterDeletion(){
        admin.deleteSeat(seat.getId());admin.deleteSeat(seat.getId());
        assertFailure("SEAT_NO_EXISTS",()->admin.addSeat(new Requests.SeatInput(null,room.getId(),"01","WINDOW",true,true)));
        var r=createRoom("新室");assertThat(admin.addSeat(new Requests.SeatInput(null,r.getId(),"01","WINDOW",true,true))).containsEntry("seatNo","01");
    }
    @Test void concurrentDisableAndBookingStayConsistent() throws Exception {
        var result=race(List.of(()->booking.add(student.getId(),request(seat,10,12)),()->admin.status(room.getId(),false)));
        assertThat(result).contains("OK");
        if(reserves.selectCount(new QueryWrapper<Reserve>().eq("room_id",room.getId()))>0){assertThat(rooms.selectById(room.getId()).getEnabled()).isTrue();assertThat(result).contains("RESOURCE_IN_USE");}
        else {assertThat(rooms.selectById(room.getId()).getEnabled()).isFalse();assertThat(result).contains("ROOM_UNAVAILABLE");}
    }
    @Test void simultaneousCancelAndNewBookingNeverOverlap() throws Exception {
        long id=reserve(student,seat,10,12);
        var result=race(List.of(()->booking.cancel(student.getId(),id),()->booking.add(other.getId(),request(seat,10,12))));
        assertThat(result).allMatch(s->s.equals("OK")||s.equals("SEAT_CONFLICT"));
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("seat_id",seat.getId()).eq("status","ACTIVE"))).isLessThanOrEqualTo(1);
    }
    @Test void concurrentOpeningChangeAndBookingStayConsistent() throws Exception {
        var result=race(List.of(()->booking.add(student.getId(),request(seat,10,12)),()->admin.updateRoom(new Requests.RoomInput(room.getId(),"改时段","位置",LocalTime.of(12,0),LocalTime.of(22,0)))));
        assertThat(result).contains("OK");
        if(reserves.selectCount(new QueryWrapper<Reserve>().eq("room_id",room.getId()))>0){assertThat(rooms.selectById(room.getId()).getOpenTime()).isEqualTo(LocalTime.of(8,0));assertThat(result).contains("RESOURCE_IN_USE");}
        else {assertThat(rooms.selectById(room.getId()).getOpenTime()).isEqualTo(LocalTime.of(12,0));assertThat(result).contains("OUTSIDE_OPEN_HOURS");}
    }
    @Test void recommendationUsesOnlyEndedUncancelledSnapshots(){
        long ended=reserve(student,seat,10,12);long cancelled=reserve(student,second,12,14);booking.cancel(student.getId(),cancelled);
        clock.set(BASE.plusDays(1).withHour(12));reserve(student,second,14,16);
        admin.updateSeat(new Requests.SeatInput(seat.getId(),null,"改编号","AISLE",false,false));
        var b=behaviors.history(student.getId(),BASE.plusDays(1).withHour(12));
        assertThat(b).hasSize(1);assertThat(b.getFirst().getReserveId()).isEqualTo(ended);assertThat(b.getFirst().getSeatTypeSnapshot()).isEqualTo("WINDOW");
        var in=request(seat,18,20);var rec=recommendations.recommend(student.getId(),in.startTime(),in.endTime());assertThat(rec).containsEntry("historyCount",1).containsEntry("mode","PERSONALIZED");
    }
    @Test void coldStartFiltersTimesResourcesAndUserConflicts(){
        var in=request(seat,10,12);var result=recommendations.recommend(student.getId(),in.startTime(),in.endTime());
        assertThat(result).containsEntry("mode","DEFAULT").containsEntry("historyCount",0);
        reserve(student,seat,10,12);assertFailure("USER_CONFLICT",()->recommendations.recommend(student.getId(),in.startTime(),in.endTime()));
        admin.status(createRoom("停用室").getId(),false);
        var q=catalog.seats(other.getId(),room.getId(),in.startTime(),in.endTime());assertThat(q).containsEntry("userAvailable",true);
        assertThat((List<?>)q.get("seats")).hasSize(2);
    }
    @Test void statisticsUseStartDateAndFillZeroDays(){
        var baseline=statistics.daily(BASE.toLocalDate(),BASE.toLocalDate().plusDays(1));
        long cancelled=reserve(student,seat,10,12);reserve(other,second,10,12);booking.cancel(student.getId(),cancelled);
        var data=statistics.daily(BASE.toLocalDate(),BASE.toLocalDate().plusDays(1));
        // Browser acceptance data can coexist; assert only this fixture's contribution.
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).isEqualTo(baseline.get(0));
        assertThat(data.get(1)).containsEntry("date",BASE.toLocalDate().plusDays(1).toString())
            .containsEntry("totalCount",((Number)baseline.get(1).get("totalCount")).longValue()+2)
            .containsEntry("cancelledCount",((Number)baseline.get(1).get("cancelledCount")).longValue()+1)
            .containsEntry("activeCount",((Number)baseline.get(1).get("activeCount")).longValue()+1);
        var emptyDay=LocalDate.of(2200,1,1);
        while(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reserve WHERE start_time>=? AND start_time<?)",Boolean.class,emptyDay.atStartOfDay(),emptyDay.plusDays(1).atStartOfDay())))emptyDay=emptyDay.plusDays(1);
        assertThat(statistics.daily(emptyDay,emptyDay).getFirst()).containsEntry("totalCount",0L).containsEntry("cancelledCount",0L).containsEntry("activeCount",0L);
    }
    record Client(MockHttpSession session,String csrf){}
    Client anonymous() throws Exception {var res=mvc.perform(get("/user/csrf")).andExpect(status().isOk()).andReturn();return new Client((MockHttpSession)res.getRequest().getSession(),json.readTree(res.getResponse().getContentAsString()).path("data").path("csrfToken").asText());}
    Client login(User user) throws Exception {
        var anon=anonymous();mvc.perform(post("/user/login").session(anon.session).header("X-CSRF-Token",anon.csrf).contentType("application/json").content(json.writeValueAsString(Api.map("account",user.getAccount(),"password",PASSWORD)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        var token=mvc.perform(get("/user/csrf").session(anon.session)).andReturn();return new Client(anon.session,json.readTree(token.getResponse().getContentAsString()).path("data").path("csrfToken").asText());
    }
    @Test void sessionCsrfRoleAndOwnershipChecksWorkOverHttp() throws Exception {
        mvc.perform(get("/reserve/myList")).andExpect(status().isUnauthorized());
        var anon=anonymous();mvc.perform(post("/user/login").session(anon.session).contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var studentClient=login(student);mvc.perform(get("/admin/room/list").session(studentClient.session)).andExpect(status().isForbidden());
        mvc.perform(get("/user/me").session(studentClient.session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(student.getId().toString()));
        long otherBooking=reserve(other,seat,10,12);
        mvc.perform(post("/reserve/cancel").session(studentClient.session).header("X-CSRF-Token",studentClient.csrf).contentType("application/json").content(json.writeValueAsString(Api.map("reserveId",Long.toString(otherBooking))))).andExpect(status().isNotFound());
        var adminClient=login(administrator);mvc.perform(get("/admin/reserve/list").session(adminClient.session)).andExpect(status().isOk());
        mvc.perform(post("/user/logout").session(studentClient.session).header("X-CSRF-Token",studentClient.csrf).contentType("application/json").content("{}")).andExpect(status().isOk());
        assertThat(studentClient.session.isInvalid()).isTrue();mvc.perform(get("/reserve/myList")).andExpect(status().isUnauthorized());
    }
    @Test void registrationRejectsRoleAndUsesBcrypt() throws Exception {
        var client=anonymous();String account=prefix+"new";
        var body=Api.map("account",account,"name","同学","password",PASSWORD,"role","ADMIN");
        mvc.perform(post("/user/register").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.remove("role");
        mvc.perform(post("/user/register").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STUDENT"));
        User registered=users.selectOne(new QueryWrapper<User>().eq("account",account));userIds.add(registered.getId());
        assertThat(registered.getPasswordHash()).startsWith("$2");assertThat(passwords.matches(PASSWORD,registered.getPasswordHash())).isTrue();
        mvc.perform(post("/user/register").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
    }
    @Test void queryTimeEncodingAndPayloadIdentityAreHandled() throws Exception {
        var client=login(student);var in=request(seat,10,12);
        mvc.perform(get("/seat/list/"+room.getId()).session(client.session).param("startTime",in.startTime().toString()).param("endTime",in.endTime().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.seats[0].seatAvailable").value(true));
        var body=Api.map("seatId",seat.getId().toString(),"startTime",in.startTime().toString(),"endTime",in.endTime().toString(),"requestId",in.requestId(),"userId",other.getId().toString());
        mvc.perform(post("/reserve/add").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.remove("userId");mvc.perform(post("/reserve/add").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk());
        assertThat(reserves.selectCount(new QueryWrapper<Reserve>().eq("user_id",student.getId()))).isEqualTo(1);
    }
    @Test void deletedAndClosedResourcesAreExcluded(){
        admin.deleteSeat(seat.getId());admin.status(room.getId(),false);
        var in=request(second,10,12);
        assertFailure("ROOM_UNAVAILABLE",()->booking.add(student.getId(),in));
        var result=recommendations.recommend(student.getId(),in.startTime(),in.endTime());
        assertThat((List<?>)result.get("seats")).allSatisfy(value->assertThat(((Map<?,?>)value).get("roomId")).isNotEqualTo(room.getId().toString()));
        admin.status(room.getId(),true);admin.deleteRoom(room.getId());admin.deleteRoom(room.getId());
        assertFailure("RESOURCE_NOT_FOUND",()->booking.add(student.getId(),in));
        assertThat((List<?>)recommendations.recommend(student.getId(),in.startTime(),in.endTime()).get("seats")).allSatisfy(value->assertThat(((Map<?,?>)value).get("roomId")).isNotEqualTo(room.getId().toString()));
    }
    @Test void concurrentRegistrationEnforcesAccountUniqueness() throws Exception {
        var anon1=anonymous();var anon2=anonymous();String account=prefix+"race";
        var payload=json.writeValueAsString(Api.map("account",account,"name","注册测试","password",PASSWORD));
        Supplier<Object> register1=()->httpRegister(anon1,payload);Supplier<Object> register2=()->httpRegister(anon2,payload);
        var result=race(List.of(register1,register2));
        var registered=users.selectList(new QueryWrapper<User>().eq("account",account));registered.forEach(u->userIds.add(u.getId()));
        assertThat(registered).hasSize(1);assertThat(result).containsExactlyInAnyOrder("OK","ACCOUNT_EXISTS");
    }
    Object httpRegister(Client client,String payload){
        try{var response=mvc.perform(post("/user/register").session(client.session).header("X-CSRF-Token",client.csrf).contentType("application/json").content(payload)).andReturn().getResponse();
            if(response.getStatus()!=200)throw new Api.Failure(response.getStatus(),json.readTree(response.getContentAsString()).path("code").asText(),"registration failed");return true;
        }catch(Api.Failure failure){throw failure;}catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void tenThousandRowsMeetSequentialQueryLatencyTarget() throws Exception {
        List<Reserve> data=new ArrayList<>();
        for(int i=0;i<10000;i++){
            Reserve r=new Reserve();r.setUserId(student.getId());r.setRoomId(room.getId());r.setSeatId(seat.getId());
            r.setStartTime(BASE.minusDays(10000-i).withHour(10));r.setEndTime(r.getStartTime().plusHours(2));
            r.setStatus(i%5==0?"CANCELLED":"ACTIVE");r.setRequestId(UUID.randomUUID().toString());data.add(r);
        }
        jdbc.batchUpdate("INSERT INTO reserve(user_id,room_id,seat_id,start_time,end_time,status,request_id) VALUES(?,?,?,?,?,?,?)",data,500,(ps,r)->{
            ps.setLong(1,r.getUserId());ps.setLong(2,r.getRoomId());ps.setLong(3,r.getSeatId());ps.setObject(4,r.getStartTime());ps.setObject(5,r.getEndTime());ps.setString(6,r.getStatus());ps.setString(7,r.getRequestId());
        });
        jdbc.update("INSERT INTO user_behavior(reserve_id,user_id,room_id,seat_id,start_time,end_time,room_name_snapshot,seat_no_snapshot,seat_type_snapshot,has_power_snapshot,is_quiet_snapshot,operation_type) SELECT id,user_id,room_id,seat_id,start_time,end_time,'性能测试','01','WINDOW',TRUE,TRUE,'RESERVE' FROM reserve WHERE user_id=?",student.getId());
        var studentClient=login(student);var adminClient=login(administrator);var in=request(seat,10,12);
        List<Map<String,Object>> measurements=new ArrayList<>();
        String[] endpoints={"/room/list","/seat/list/"+room.getId(),"/reserve/myList","/ai/recommend","/admin/reserve/list","/admin/statistics/daily"};
        for(String path:endpoints){
            List<Double> samples=new ArrayList<>();
            for(int i=0;i<110;i++){
                var req=get(path).session(path.startsWith("/admin/")?adminClient.session:studentClient.session);
                if(path.startsWith("/seat/")||path.equals("/ai/recommend"))req.param("startTime",in.startTime().toString()).param("endTime",in.endTime().toString());
                if(path.equals("/admin/statistics/daily"))req.param("fromDate",BASE.toLocalDate().minusDays(6).toString()).param("toDate",BASE.toLocalDate().toString());
                long start=System.nanoTime();mvc.perform(req).andExpect(status().isOk());double ms=(System.nanoTime()-start)/1_000_000.0;
                if(i>=10)samples.add(ms);
            }
            samples.sort(Double::compare);double p95=samples.get(94);
            measurements.add(Api.map("endpoint",path,"samples",100,"warmups",10,"p95Ms",p95));
            assertThat(p95).as(path+" P95 milliseconds").isLessThanOrEqualTo(1000);
        }
        var report=Api.map("recordedAt",Instant.now().toString(),"reservationRows",jdbc.queryForObject("SELECT COUNT(*) FROM reserve",Long.class),
          "roomCount",jdbc.queryForObject("SELECT COUNT(*) FROM study_room",Long.class),"seatCount",jdbc.queryForObject("SELECT COUNT(*) FROM seat",Long.class),
          "fixtureReservationRows",10000,"fixtureRoomCount",1,"fixtureSeatCount",2,"historyCount",8000,
          "java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"cpu",System.getenv("PROCESSOR_IDENTIFIER"),"availableProcessors",Runtime.getRuntime().availableProcessors(),
          "jvmMaxMemoryBytes",Runtime.getRuntime().maxMemory(),"mysql",jdbc.queryForObject("SELECT VERSION()",String.class),
          "method","single client, Spring MockMvc dispatch including security and JSON serialization; excludes sockets and browser rendering","measurements",measurements);
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/performance-report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8);
        System.out.println("Performance report: backend/target/performance-report.json");
    }
    static void assertFailure(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.code).isEqualTo(code));}
}
