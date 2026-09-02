package com.hmdp.agent.sse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 降级轮询收集器单测：delta 聚合 / card 收集 / done·error 完成信号 / 超时 partial
 */
class PollRoundCollectorTest {

    @Test
    void delta聚合_card收集_done完成() {
        PollRoundCollector c = new PollRoundCollector();
        c.accept("session", Map.of("sessionId", "1")); // 轮询协议不回传，忽略
        c.accept("delta", Map.of("text", "您好，"));
        c.accept("delta", Map.of("text", "请描述问题"));
        c.accept("tool_call", Map.of("toolName", "query_my_orders")); // 忽略
        c.accept("card", Map.of("cardType", "CLARIFY_MENU", "payload", Map.of()));
        c.accept("done", Map.of("roundNo", 1, "finishReason", "OK"));

        assertTrue(c.await(0));
        assertEquals("您好，请描述问题", c.text());
        assertEquals(1, c.cards().size());
        assertEquals("OK", c.finishReason());
    }

    @Test
    void error完成_后随done的finishReason覆盖() {
        PollRoundCollector c = new PollRoundCollector();
        c.accept("error", Map.of("code", "CHAT_FAIL", "friendlyText", "服务暂时繁忙"));
        assertTrue(c.await(0));
        assertEquals("ERROR", c.finishReason());

        c.accept("done", Map.of("finishReason", "MSG_LIMIT"));
        assertEquals("MSG_LIMIT", c.finishReason());
    }

    @Test
    void 未完成_限时等待超时返回false() {
        PollRoundCollector c = new PollRoundCollector();
        long start = System.currentTimeMillis();
        boolean completed = c.await(80);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(completed);
        assertTrue(elapsed >= 50, "应至少等待近似 timeout 时长");
        assertEquals("", c.text());
        assertTrue(c.cards().isEmpty());
    }
}
