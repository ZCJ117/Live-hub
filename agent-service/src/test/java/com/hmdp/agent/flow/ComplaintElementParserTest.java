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

    @Test
    void 非法类别值_置null_null值refs键剔除_数值归一() throws ComplaintElementParser.ParseFail {
        // DEF-B3a：LLM 抽取 "ORDER|MERCHANT_SERVICE" 类垃圾值 → null（走追问/OTHER 兜底，不再 DB Data too long）
        // DEF-B3c：shopId:null 剔除 + "9003" 数值字符串归一为 Long，保证 dedup_key 稳定
        ComplaintElementParser.Element e = parser.parse(
                "{\"category\":\"ORDER|MERCHANT_SERVICE\",\"refs\":{\"orderId\":\"9003\",\"shopId\":null},\"demand\":\"要求补发\"}");
        assertEquals(null, e.category());
        assertEquals(Map.of("orderId", 9003L), e.refs());
    }
}
