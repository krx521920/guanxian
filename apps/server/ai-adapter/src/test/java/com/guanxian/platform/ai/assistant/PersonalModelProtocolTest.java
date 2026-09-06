package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.AiProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PersonalModelProtocolTest {
    @Test void providerLengthFinishCannotBecomeASuccessfulRememberedAnswer() {
        var history = new AssistantConversationMemory(2);
        ClientHttpConnector transport = (method, uri, callback) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            return callback.apply(request).then(Mono.fromSupplier(() -> {
                MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                response.setBody(frame("{\"content\":\"被截断的答案\"}", "\"length\"") + "data: [DONE]\n\n");
                return response;
            }));
        };
        var client = SpringAiAssistantConfiguration.createClient(properties(PersonalModelProvider.DEEPSEEK), history,
                RestClient.builder(), WebClient.builder().clientConnector(transport));
        assertThrows(RuntimeException.class, () -> client.prompt().user("问题")
                .advisors(advisor -> advisor.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "truncated"))
                .stream().content().blockLast(Duration.ofSeconds(5)));
        assertTrue(history.get("truncated").isEmpty());
    }
    @Test void providerErrorBodiesAreDiscardedBeforeReachingTheModelLayer() {
        ClientHttpConnector transport = (method, uri, callback) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            return callback.apply(request).then(Mono.fromSupplier(() -> {
                MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.UNAUTHORIZED);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                response.setBody("{\"error\":\"echoed-private-test-key-must-not-leak\"}");
                return response;
            }));
        };
        var client = SpringAiAssistantConfiguration.createClient(properties(PersonalModelProvider.KIMI), memory(),
                RestClient.builder(), WebClient.builder().clientConnector(transport));
        RuntimeException error = assertThrows(RuntimeException.class, () -> client.prompt()
                .advisors(advisor -> advisor.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "test-error"))
                .user("测试").stream().content().collectList().block(Duration.ofSeconds(5)));
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains("echoed-private-test-key"));
        }
    }

    @Test void allFourPresetsSendTheirModelAndBearerKeyAndParseActualSseFrames() {
        for (PersonalModelProvider provider : PersonalModelProvider.values()) {
            AiProviderProperties props = properties(provider);
            AtomicInteger calls = new AtomicInteger();
            ClientHttpConnector transport = (method, uri, callback) -> {
                assertEquals(provider.endpoint(), uri.toString());
                MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                return callback.apply(request).then(Mono.defer(() -> {
                    assertEquals("Bearer test-only-personal-key", request.getHeaders().getFirst("Authorization"));
                    String body = request.getBodyAsString().block();
                    assertNotNull(body);
                    assertTrue(body.contains("user-selected-model"));
                    assertTrue(body.contains("\"stream\":true"));
                    assertFalse(body.contains("test-only-personal-key"));
                    calls.incrementAndGet();
                    MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                    response.setBody(frame("{\"content\":\"连接\"}", "null")
                            + frame("{\"content\":\"成功\"}", "\"stop\"") + "data: [DONE]\n\n");
                    return Mono.just(response);
                }));
            };
            var client = SpringAiAssistantConfiguration.createClient(props, memory(), RestClient.builder(),
                    WebClient.builder().clientConnector(transport));
            String result = client.prompt().advisors(advisor -> advisor.param("chat_memory_conversation_id", "test-connection"))
                    .user("测试").stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
            assertEquals("连接成功", result);
            assertEquals(1, calls.get());
        }
    }

    @Test void actualSpringAiToolCallRoundTripConsumesToolFramesAndSendsToolResult() {
        AtomicInteger requests = new AtomicInteger();
        TestReadTool tool = new TestReadTool();
        ClientHttpConnector transport = (method, uri, callback) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            return callback.apply(request).then(Mono.defer(() -> {
                int index = requests.incrementAndGet();
                String body = request.getBodyAsString().block();
                assertNotNull(body);
                MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
                if (index == 1) {
                    assertTrue(body.contains("test_read_tool"));
                    response.setBody(frame("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_fixture\",\"type\":\"function\",\"function\":{\"name\":\"test_read_tool\",\"arguments\":\"{}\"}}]}", "\"tool_calls\"") + "data: [DONE]\n\n");
                } else {
                    assertEquals(2, index);
                    assertTrue(body.contains("VISIBLE_ONLY_FIXTURE"));
                    assertTrue(body.contains("call_fixture"));
                    response.setBody(frame("{\"content\":\"工具查询成功\"}", "\"stop\"") + "data: [DONE]\n\n");
                }
                return Mono.just(response);
            }));
        };
        var client = SpringAiAssistantConfiguration.createClient(properties(PersonalModelProvider.DEEPSEEK), memory(),
                RestClient.builder(), WebClient.builder().clientConnector(transport));
        String result = client.prompt().advisors(advisor -> advisor.param("chat_memory_conversation_id", "test-tools"))
                .user("执行只读查询").tools(tool).stream().content().collectList()
                .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
        assertEquals("工具查询成功", result);
        assertEquals(2, requests.get());
        assertEquals(1, tool.calls);
    }

    static final class TestReadTool {
        int calls;
        @Tool(name = "test_read_tool", description = "Read a fixed test fixture")
        public String read() { calls++; return "VISIBLE_ONLY_FIXTURE"; }
    }

    private static String frame(String delta, String finish) {
        return "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"user-selected-model\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":" + finish + "}]}\n\n";
    }
    private static MessageWindowChatMemory memory() {
        return MessageWindowChatMemory.builder().chatMemoryRepository(new BoundedChatMemoryRepository(2)).maxMessages(12).build();
    }
    private static AiProviderProperties properties(PersonalModelProvider provider) {
        AiProviderProperties props = new AiProviderProperties();
        props.setEndpoint(provider.endpoint());
        props.setApiKey("test-only-personal-key");
        props.setModel("user-selected-model");
        return props;
    }
}
