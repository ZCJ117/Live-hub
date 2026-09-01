package com.hmdp.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.planner.Intent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测数据集自校验（始终运行，不依赖环境）：规模/配额/标签合法性
 * 配额按 T3.15 实际语料设定：口语化 ≥40、错别字 ≥20、方言 ≥15、复合 ≥4（PRD 要求三类表达覆盖）
 */
class DatasetValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void intent_testset_200_items_with_quota() throws Exception {
        JsonNode items = mapper.readTree(getClass().getResourceAsStream("/eval/intent-testset.json")).path("items");
        assertEquals(200, items.size());
        Map<String, Integer> byLabel = new HashMap<>();
        Map<String, Integer> byStyle = new HashMap<>();
        for (JsonNode it : items) {
            assertFalse(it.path("text").asText("").isBlank(), it.path("id").asText() + " text 为空");
            assertTrue(it.path("text").asText().length() <= 50, it.path("id").asText() + " text 超长");
            assertNotNull(Intent.of(it.path("label").asText()), it.path("id").asText() + " 非法 label");
            byLabel.merge(it.path("label").asText(), 1, Integer::sum);
            byStyle.merge(it.path("style").asText(""), 1, Integer::sum);
        }
        assertEquals(35, byLabel.get("ORDER_QUERY"));
        assertEquals(30, byLabel.get("VOUCHER_CONSULT"));
        assertEquals(25, byLabel.get("SHOP_CONSULT"));
        assertEquals(25, byLabel.get("REFUND"));
        assertEquals(25, byLabel.get("COMPLAINT"));
        assertEquals(45, byLabel.get("CHAT"));
        assertEquals(15, byLabel.get("HUMAN_DEMAND"));
        assertTrue(byStyle.getOrDefault("colloquial", 0) >= 40, "口语化不足: " + byStyle);
        assertTrue(byStyle.getOrDefault("typo", 0) >= 20, "错别字不足: " + byStyle);
        assertTrue(byStyle.getOrDefault("dialect", 0) >= 15, "方言不足: " + byStyle);
        assertTrue(byStyle.getOrDefault("composite", 0) >= 4, "复合意图不足: " + byStyle);
    }

    @Test
    void multiturn_testset_50_groups() throws Exception {
        JsonNode groups = mapper.readTree(getClass().getResourceAsStream("/eval/multiturn-testset.json")).path("groups");
        assertEquals(50, groups.size());
        for (JsonNode g : groups) {
            assertTrue(g.path("turns").size() >= 2 && g.path("turns").size() <= 4, g.path("id").asText());
            g.path("turns").forEach(t -> assertFalse(t.path("user").asText("").isBlank()));
        }
    }

    @Test
    void safety_testset_30_items() throws Exception {
        JsonNode items = mapper.readTree(getClass().getResourceAsStream("/eval/safety-testset.json")).path("items");
        assertEquals(30, items.size());
        items.forEach(it -> assertTrue(it.path("mustGuideBack").asBoolean(false)));
    }
}
