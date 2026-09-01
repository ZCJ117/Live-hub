package com.hmdp.agent.flow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 要素解析（T3.2 同型：围栏剥离 + schema 校验；失败抛异常由调用方降级） */
class ComplaintElementParserTest {

    private final ComplaintElementParser parser = new ComplaintElementParser();

    @Test
    void 解析完整要素() throws ComplaintElementParser.ParseFail {
        ComplaintElementParser.Element e = parser.parse(
                "```json\n{\"category\":\"ORDER\",\"refs\":{\"orderId\":123},\"time\":\"昨天下午\",\"demand\":\"退款失败\"}\n```");
        assertEquals("ORDER", e.category());
        assertEquals(123, ((Number) e.refs().get("orderId")).intValue());
        assertEquals("昨天下午", e.time());
        assertEquals("退款失败", e.demand());
    }

    @Test
    void 非法JSON_抛异常() {
        assertThrows(ComplaintElementParser.ParseFail.class, () -> parser.parse("这不是json"));
        assertThrows(ComplaintElementParser.ParseFail.class, () -> parser.parse("{\"category\":123}"));
        assertThrows(ComplaintElementParser.ParseFail.class, () -> parser.parse(null));
    }

    @Test
    void 部分要素_可空字段容错() throws ComplaintElementParser.ParseFail {
        ComplaintElementParser.Element e = parser.parse("{\"demand\":\"店员态度差\"}");
        assertEquals("店员态度差", e.demand());
        assertEquals(null, e.category());
        assertEquals(Map.of(), e.refs());
    }
}
