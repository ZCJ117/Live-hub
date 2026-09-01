package com.hmdp.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化输出解析组件单测（T3.2/R2）：容错提取 + schema 校验 + 失败埋点由 IntentClassifier 负责
 */
class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser();

    private IntentResult ok(String raw) throws Exception {
        return parser.parseIntent(raw);
    }

    @Test
    void parses_valid_json() throws Exception {
        IntentResult r = ok("{\"intent\":\"ORDER_QUERY\",\"entities\":{\"orderId\":123},\"confidence\":0.9,\"subtasks\":[]}");
        assertEquals(Intent.ORDER_QUERY, r.intent());
        assertEquals(0.9, r.confidence());
        assertEquals("123", String.valueOf(r.entities().get("orderId")));
        assertTrue(r.subtasks().isEmpty());
    }

    @Test
    void parses_json_wrapped_in_markdown_fence() throws Exception {
        IntentResult r = ok("```json\n{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":0.95,\"subtasks\":[]}\n```");
        assertEquals(Intent.CHAT, r.intent());
    }

    @Test
    void parses_json_with_surrounding_text() throws Exception {
        IntentResult r = ok("分类结果：{\"intent\":\"REFUND\",\"entities\":{},\"confidence\":0.8,\"subtasks\":[]} 以上。");
        assertEquals(Intent.REFUND, r.intent());
    }

    @Test
    void parses_composite_subtasks() throws Exception {
        IntentResult r = ok("{\"intent\":\"ORDER_QUERY\",\"entities\":{},\"confidence\":0.85,"
                + "\"subtasks\":[\"查询我的订单\",\"申请退款\"]}");
        assertEquals(List.of("查询我的订单", "申请退款"), r.subtasks());
    }

    @Test
    void rejects_illegal_intent() {
        assertThrows(IntentParseException.class,
                () -> ok("{\"intent\":\"FOOBAR\",\"entities\":{},\"confidence\":0.9}"));
    }

    @Test
    void rejects_missing_intent() {
        assertThrows(IntentParseException.class,
                () -> ok("{\"entities\":{},\"confidence\":0.9}"));
    }

    @Test
    void rejects_confidence_out_of_range() {
        assertThrows(IntentParseException.class,
                () -> ok("{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":1.5}"));
        assertThrows(IntentParseException.class,
                () -> ok("{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":\"high\"}"));
    }

    @Test
    void rejects_subtasks_not_array() {
        assertThrows(IntentParseException.class,
                () -> ok("{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":0.9,\"subtasks\":\"查订单\"}"));
    }

    @Test
    void rejects_garbage_and_empty() {
        assertThrows(IntentParseException.class, () -> ok("not json at all"));
        assertThrows(IntentParseException.class, () -> ok(""));
        assertThrows(IntentParseException.class, () -> ok(null));
    }

    @Test
    void entities_missing_yields_empty_map() throws Exception {
        IntentResult r = ok("{\"intent\":\"CHAT\",\"confidence\":0.9,\"subtasks\":[]}");
        assertNotNull(r.entities());
        assertTrue(r.entities().isEmpty());
        assertEquals(Map.of(), r.entities());
    }
}
