package com.hmdp.agent.llm;

import com.hmdp.agent.config.GlmProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 容灾（T4.14/PRD 4.3）：3 次重试（指数退避）→ 备用模型切换 → 抛 LlmException；
 * 流式调用仅在首 delta 前可安全重试
 */
class GlmClientResilienceTest {

    private MockWebServer server;
    private GlmClient client;
    private GlmProperties props;

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        props = new GlmProperties();
        props.setApiKey("test-key");
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setMainModel("glm-main");
        props.setLightModel("glm-light");
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(2));
        client = new GlmClient(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MockResponse ok(String content) {
        return new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                .setHeader("Content-Type", "application/json");
    }

    @Test
    void 前2次500_第3次成功_重试生效() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(ok("恢复回答"));

        LlmTypes.Response r = client.complete(LlmTypes.Request.builder()
                .model("glm-main")
                .messages(List.of(LlmTypes.Message.user("hi")))
                .build());

        assertEquals("恢复回答", r.getContent());
        assertEquals(3, server.getRequestCount());
        assertEquals("/chat/completions", server.takeRequest().getPath());
    }

    @Test
    void 主模型3连败_切换备用模型_成功() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        server.enqueue(ok("备用模型回答"));

        LlmTypes.Response r = client.complete(LlmTypes.Request.builder()
                .model("glm-main")
                .messages(List.of(LlmTypes.Message.user("hi")))
                .build());

        assertEquals("备用模型回答", r.getContent());
        assertEquals(4, server.getRequestCount());
        // 前 3 次主模型，第 4 次备用模型
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8().contains("glm-main"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8().contains("glm-main"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8().contains("glm-main"));
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8().contains("glm-light"));
    }

    @Test
    void 全部失败_抛LlmException() {
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        assertThrows(LlmTypes.LlmException.class, () -> client.complete(
                LlmTypes.Request.builder().model("glm-main")
                        .messages(List.of(LlmTypes.Message.user("hi"))).build()));
        assertEquals(6, server.getRequestCount());
    }

    @Test
    void 流式_首delta前失败_重试成功且无重复输出() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"));

        StringBuilder out = new StringBuilder();
        GlmClient.StreamResult r = client.streamChat(LlmTypes.Request.builder()
                .model("glm-light")
                .messages(List.of(LlmTypes.Message.user("hi")))
                .build(), out::append);

        assertEquals("你好", r.content());
        assertEquals("你好", out.toString(), "重试不得向下游重复输出失败尝试的内容");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 流式_中途断流_不再重试_抛异常() {
        // 断流点必须落在首个 delta 事件之后（MockWebServer 在响应体一半处断开，故用 padding 撑过半程；
        // throttle 保证首个事件先完整到达客户端）
        String deltaEvent = "data: {\"choices\":[{\"delta\":{\"content\":\"前半\"}}]}\n\n";
        server.enqueue(new MockResponse().setBody(deltaEvent + "ping\n".repeat(40))
                .setHeader("Content-Type", "text/event-stream")
                .throttleBody(60, 200, TimeUnit.MILLISECONDS)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(new MockResponse().setResponseCode(500)); // 不应被消费

        StringBuilder out = new StringBuilder();
        assertThrows(LlmTypes.LlmException.class, () -> client.streamChat(
                LlmTypes.Request.builder().model("glm-light")
                        .messages(List.of(LlmTypes.Message.user("hi"))).build(), out::append));
        assertEquals(1, server.getRequestCount(), "已产出 delta 后不得重试（防重复输出）");
        assertEquals("前半", out.toString());
    }
}
