package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import cn.edu.studyroom.mapper.ReserveMapper;
import cn.edu.studyroom.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.edu.studyroom.assistant.AssistantRulesTest.failure;

class AssistantServiceTest {
    ChatModel model=mock(ChatModel.class);AssistantTools tools=mock(AssistantTools.class);
    Clock clock=Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"),ZoneOffset.ofHours(8));
    DefaultListableBeanFactory factory=new DefaultListableBeanFactory();
    AssistantService service;MockHttpSession session=new MockHttpSession();
    @BeforeEach void setup(){factory.registerSingleton("model",model);service=create(Duration.ofSeconds(5));when(tools.definitions()).thenReturn(List.of());}
    AssistantService create(Duration timeout){return new AssistantService(factory.getBeanProvider(ChatModel.class),tools,new ObjectMapper(),clock,mock(BookingService.class),mock(ReserveMapper.class),timeout);}
    @AfterEach void close(){service.close();}
    static ChatResponse text(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    static ChatResponse calls(String... names){var calls=new ArrayList<AssistantMessage.ToolCall>();for(var name:names)calls.add(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,"{}"));return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));}
    String id(){return UUID.randomUUID().toString();}
    @Test void replaysSuccessAndRejectsChangedPayloadWithoutCallingModelAgain(){
        when(model.call(any(Prompt.class))).thenReturn(text("好的"));String id=id();var first=service.chat(session,1,"查座",id);
        assertThat(service.chat(session,1,"查座",id)).isEqualTo(first);
        failure("ASSISTANT_REQUEST_CONFLICT",()->service.chat(session,1,"别的",id));verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void duplicateInFlightSharesTaskWhileDifferentRequestIsBusy() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(model.call(any(Prompt.class))).thenAnswer(inv->{entered.countDown();release.await();return text("完成");});
        try(var pool=Executors.newFixedThreadPool(2)) {
            String id=id();var first=pool.submit(()->service.chat(session,1,"查座",id));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->service.chat(session,1,"查座",id));
            failure("ASSISTANT_BUSY",()->service.chat(session,1,"另一个请求",id()));release.countDown();
            assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo(second.get(2,TimeUnit.SECONDS));verify(model,times(1)).call(any(Prompt.class));
        }finally{release.countDown();}
    }
    @Test void resetDiscardsLateReplyAndAllowsNewConversation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(inv->{entered.countDown();boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}return text("迟到回复");});
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->{try{return service.chat(session,1,"查座",id());}finally{done.countDown();}});
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();service.reset(session,1);release.countDown();assertThat(done.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->first.get()).hasCauseInstanceOf(Api.Failure.class).satisfies(e->assertThat(((Api.Failure)e.getCause()).code).isEqualTo("ASSISTANT_CANCELLED"));
            assertThat((List<?>)service.conversation(session,1).get("messages")).isEmpty();
        }finally{release.countDown();}
    }
    @Test void logoutInvalidationDiscardsLateReply() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(inv->{entered.countDown();release.await();return text("结果");});
        try(var pool=Executors.newSingleThreadExecutor()) {
            var task=pool.submit(()->service.chat(session,1,"查座",id()));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();session.invalidate();release.countDown();
            assertThatThrownBy(()->task.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(Api.Failure.class);
        }finally{release.countDown();}
    }
    @Test void failingTurnDiscardsDraftAndFailureIsAlsoDeduplicated() {
        when(model.call(any(Prompt.class))).thenReturn(calls("searchSeats")).thenThrow(new IllegalStateException("private-error-never-expose"));
        when(tools.execute(anyString(),anyString(),eq(1L),any())).thenAnswer(inv->{AssistantTools.Draft draft=inv.getArgument(3);draft.criteria=new SearchCriteria(Map.of("hasPower",new SearchCriteria.Condition(true,"SOFT")));return Map.of("ok",true);});
        String id=id();failure("ASSISTANT_UNAVAILABLE",()->service.chat(session,1,"查座",id));failure("ASSISTANT_UNAVAILABLE",()->service.chat(session,1,"查座",id));
        assertThat(service.conversation(session,1).get("conversationState")).isEqualTo(Map.of());assertThat((List<?>)service.conversation(session,1).get("messages")).isEmpty();verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void modelAndToolLimitsDiscardWholeTurn() {
        when(model.call(any(Prompt.class))).thenAnswer(inv->calls("listRooms"));
        failure("ASSISTANT_LIMIT_REACHED",()->service.chat(session,1,"继续",id()));verify(model,times(4)).call(any(Prompt.class));
        assertThat((List<?>)service.conversation(session,1).get("messages")).isEmpty();
        reset(model,tools);when(tools.definitions()).thenReturn(List.of());when(model.call(any(Prompt.class))).thenAnswer(inv->calls("listRooms","listRooms","listRooms"));
        failure("ASSISTANT_LIMIT_REACHED",()->service.chat(session,1,"查很多次",id()));verify(tools,times(8)).execute(anyString(),anyString(),eq(1L),any());
    }
    @Test void globalCapacityCountsOnlyActualTasks() throws Exception {
        var entered=new CountDownLatch(5);var release=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(inv->{entered.countDown();release.await();return text("完成");});
        try(var pool=Executors.newFixedThreadPool(5)) {
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<5;i++){long user=i+1;tasks.add(pool.submit(()->service.chat(new MockHttpSession(),user,"查座",id())));}
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();failure("ASSISTANT_BUSY",()->service.chat(new MockHttpSession(),99,"查座",id()));release.countDown();for(var task:tasks)task.get(2,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    @Test void timeoutCancelsAndDoesNotPublish() {
        service.close();service=create(Duration.ofMillis(80));when(model.call(any(Prompt.class))).thenAnswer(inv->{Thread.sleep(5000);return text("迟到");});
        String id=id();failure("ASSISTANT_TIMEOUT",()->service.chat(session,1,"查座",id));
        failure("ASSISTANT_TIMEOUT",()->service.chat(session,1,"查座",id));assertThat((List<?>)service.conversation(session,1).get("messages")).isEmpty();
    }
    @Test void completeTurnTrimmingKeepsToolPairsAndSeparateCriteria() {
        // Use separate users to stay within per-user rolling quota; a single session retains one owner.
        var moving=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        Clock testClock=new Clock(){public ZoneId getZone(){return ZoneOffset.ofHours(8);}public Clock withZone(ZoneId z){return this;}public Instant instant(){return moving.get();}};
        service.close();service=new AssistantService(factory.getBeanProvider(ChatModel.class),tools,new ObjectMapper(),testClock,mock(BookingService.class),mock(ReserveMapper.class));
        var count=new AtomicInteger();when(model.call(any(Prompt.class))).thenAnswer(inv->{var prompt=inv.getArgument(0,Prompt.class);int n=count.incrementAndGet();
            if(n==43){var messages=prompt.getInstructions();assertThat(messages).hasSize(82);assertThat(messages.get(1)).isInstanceOf(UserMessage.class);assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);assertThat(messages.get(3)).isInstanceOf(ToolResponseMessage.class);}
            return n%2==1?calls("listRooms"):text("完成");});
        for(int i=0;i<22;i++){service.chat(session,1,"第"+i+"轮",id());moving.updateAndGet(t->t.plusSeconds(61));}
        assertThat((List<?>)service.conversation(session,1).get("messages")).hasSize(20);assertThat(count).hasValue(44);
    }
    @Test void disabledModelHasReadableStateAndRejectsWrites() {
        factory.destroySingleton("model");assertThat(service.conversation(session,1)).containsEntry("available",false);
        failure("ASSISTANT_UNAVAILABLE",()->service.chat(session,1,"查座",id()));failure("ASSISTANT_UNAVAILABLE",()->service.book(session,1,id()));
        assertThat(service.reset(session,1)).containsEntry("available",false);
    }
    @Test void duplicateToolCallIdentifiersAndEmptyRepliesAreProtocolErrors() {
        var call=new AssistantMessage.ToolCall("same-id","function","listRooms","{}");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call,call)).build()))));
        failure("ASSISTANT_PROTOCOL_ERROR",()->service.chat(session,1,"查座",id()));
        when(model.call(any(Prompt.class))).thenReturn(text(" "));
        failure("ASSISTANT_PROTOCOL_ERROR",()->service.chat(session,1,"查座",id()));assertThat((List<?>)service.conversation(session,1).get("messages")).isEmpty();
    }
}
