package cn.edu.studyroom.assistant;

import cn.edu.studyroom.api.Api;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DeepSeekAdapterTest {
    final ObjectMapper json=new ObjectMapper();
    ChatModel model(String base){return new AssistantConfiguration().assistantChatModel(new MockEnvironment()
        .withProperty("app.assistant.api-key","test-only").withProperty("app.assistant.base-url",base),json);}
    @Test void disabledOrMissingKeyCreatesNoModel() {
        var runner=new ApplicationContextRunner().withUserConfiguration(AssistantConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new);
        runner.withPropertyValues("app.assistant.enabled=false","app.assistant.api-key=test-only").run(c->assertThat(c).doesNotHaveBean(ChatModel.class));
        runner.withPropertyValues("app.assistant.enabled=true","app.assistant.api-key=").run(c->assertThat(c).doesNotHaveBean(ChatModel.class));
    }
    @Test void actualHttpBodyDisablesThinkingAndPreservesManualToolContinuation() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var seen=new ArrayList<JsonNode>();
        server.createContext("/chat/completions",ex->{
            seen.add(json.readTree(ex.getRequestBody()));
            String message=seen.size()==1?"{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_one\",\"type\":\"function\",\"function\":{\"name\":\"listRooms\",\"arguments\":\"{}\"}}]}":"{\"role\":\"assistant\",\"content\":\"verified\"}";
            byte[] bytes=("{\"id\":\"probe\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();
        try {
            var model=model("http://127.0.0.1:"+server.getAddress().getPort());var messages=new ArrayList<Message>();messages.add(new UserMessage("diagnostic"));
            var tools=new AssistantTools(null,null,null,null,json).definitions();
            var options=org.springframework.ai.deepseek.DeepSeekChatOptions.builder().toolCallbacks(tools).internalToolExecutionEnabled(false).build();
            var first=model.call(new Prompt(messages,options)).getResult().getOutput();assertThat(first.getToolCalls()).hasSize(1);messages.add(first);
            messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call_one","listRooms","probe-ok"))).build());
            assertThat(model.call(new Prompt(messages,options)).getResult().getOutput().getText()).isEqualTo("verified");
            assertThat(seen).hasSize(2);for(var body:seen){assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");assertThat(body.has("extra_body")).isFalse();
                var names=new ArrayList<String>();body.path("tools").forEach(t->names.add(t.path("function").path("name").asText()));assertThat(names).containsExactly("listRooms","searchSeats","myReservations");}
            assertThat(seen.get(1).path("messages").toString()).contains("tool_call_id","call_one","probe-ok");
        }finally{server.stop(0);}
    }
    @Test void upstreamErrorsAreSanitizedWithoutHiddenRetries() throws Exception {
        for(int status:new int[]{429,401,500}) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/chat/completions",ex->{count.incrementAndGet();ex.getRequestBody().readAllBytes();byte[] bytes="private-upstream-body".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);ex.close();});server.start();
            try {
                var model=model("http://127.0.0.1:"+server.getAddress().getPort());
                assertThatThrownBy(()->model.call(new Prompt("hello"))).isInstanceOfSatisfying(Api.Failure.class,e->{assertThat(e.code).isEqualTo(status==429?"ASSISTANT_RATE_LIMITED":"ASSISTANT_UNAVAILABLE");assertThat(e.getMessage()).doesNotContain("private-upstream-body");});
                assertThat(count).hasValue(1);
            }finally{server.stop(0);}
        }
    }
    @Test void httpRequestUsesRemainingTurnDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",ex->{try{Thread.sleep(500);}catch(InterruptedException ignored){}ex.close();});server.start();
        try {
            var model=model("http://127.0.0.1:"+server.getAddress().getPort());AssistantConfiguration.HTTP_BUDGET.set(new TurnBudget(Duration.ofMillis(80),()->true));
            long start=System.nanoTime();assertThatThrownBy(()->model.call(new Prompt("hello"))).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        }finally{AssistantConfiguration.HTTP_BUDGET.remove();server.stop(0);}
    }
}
