package cn.edu.studyroom.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.util.List;

@Configuration
public class AssistantConfiguration {
    static final ThreadLocal<TurnBudget> HTTP_BUDGET = new ThreadLocal<>();
    public static class EnabledWithKey implements Condition {
        @Override public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
            return ctx.getEnvironment().getProperty("app.assistant.enabled",Boolean.class,false)
                && !ctx.getEnvironment().getProperty("app.assistant.api-key","").isBlank();
        }
    }
    @Bean @Conditional(EnabledWithKey.class)
    ChatModel assistantChatModel(org.springframework.core.env.Environment env, ObjectMapper json) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var rest = RestClient.builder().requestFactory((uri,method)->{
            var budget = HTTP_BUDGET.get();
            var factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(budget == null ? Duration.ofSeconds(60) : budget.remaining());
            return factory.createRequest(uri,method);
        }).requestInterceptor((request,body,execution)->{
            var node = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(body);
            node.set("thinking",json.createObjectNode().put("type","disabled"));
            return execution.execute(request,json.writeValueAsBytes(node));
        });
        var api = DeepSeekApi.builder().baseUrl(env.getProperty("app.assistant.base-url","https://api.deepseek.com"))
            .apiKey(env.getProperty("app.assistant.api-key")).restClientBuilder(rest)
            .responseErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
                public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException { return response.getStatusCode().isError(); }
                public void handleError(java.net.URI uri,org.springframework.http.HttpMethod method,org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                    int status=response.getStatusCode().value();
                    if(status==429)throw new cn.edu.studyroom.api.Api.Failure(429,"ASSISTANT_RATE_LIMITED","模型服务限流，请稍后重试");
                    throw new cn.edu.studyroom.api.Api.Failure(503,"ASSISTANT_UNAVAILABLE",status==401||status==403?"模型凭据不可用，请联系管理员检查配置":"模型服务暂时不可用，请稍后重试");
                }
            }).build();
        // No global resolver and no automatic execution path, including for undeclared tools.
        ToolCallingManager manualOnly = new ToolCallingManager() {
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
                return options.getToolCallbacks().stream().map(org.springframework.ai.tool.ToolCallback::getToolDefinition).toList();
            }
            public ToolExecutionResult executeToolCalls(org.springframework.ai.chat.prompt.Prompt prompt, org.springframework.ai.chat.model.ChatResponse response) {
                throw new IllegalStateException("Automatic tool execution is disabled");
            }
        };
        return DeepSeekChatModel.builder().deepSeekApi(api).toolCallingManager(manualOnly)
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build())
            .defaultOptions(DeepSeekChatOptions.builder().model(env.getProperty("app.assistant.model","deepseek-flash"))
                .temperature(0.2).maxTokens(800).internalToolExecutionEnabled(false).build()).build();
    }
}
