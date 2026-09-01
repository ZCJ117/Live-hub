package com.hmdp.agent.tool;

import com.hmdp.agent.feign.RagFeignClient;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * kb_search 单测（T3.11/FR-07）：来源标注 + 完全降级（故障不算工具失败）
 */
class KbSearchToolTest {

    private final RagFeignClient ragFeign = mock(RagFeignClient.class);
    private final KbSearchTool tool = new KbSearchTool(ragFeign);
    private final ToolContext ctx = ToolContext.builder().sessionId(1L).userId(10L).build();

    @Test
    void hits_carry_source_annotation() {
        when(ragFeign.search(any())).thenReturn(Result.ok(Map.of("hits", List.of(
                new HashMap<>(Map.of("content", "招牌是麻辣香锅", "score", 0.92, "kbId", 5))))));

        ToolResult r = tool.kbSearch(ctx, Map.of("shopId", 1L, "query", "招牌菜是什么"));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) data.get("hits");
        assertEquals(1, hits.size());
        assertEquals("据商户资料", hits.get(0).get("source")); // 验收 12：100% 来源标注
    }

    @Test
    void empty_hits_degrade_gracefully() {
        when(ragFeign.search(any())).thenReturn(Result.ok(Map.of("hits", List.of())));
        ToolResult r = tool.kbSearch(ctx, Map.of("shopId", 1L, "query", "招牌菜"));
        assertTrue(r.isSuccess()); // 降级不算失败
        assertTrue(r.getSummary().contains("暂无") || r.getSummary().contains("仅"));
    }

    @Test
    void feign_failure_fully_degrades_not_fail() {
        when(ragFeign.search(any())).thenThrow(new RuntimeException("rag down"));
        ToolResult r = tool.kbSearch(ctx, Map.of("shopId", 1L, "query", "招牌菜"));
        assertTrue(r.isSuccess()); // 完全降级（D1.4 C4）：不计工具失败、不触发中断
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((List<?>) data.get("hits")).isEmpty());
    }

    @Test
    void missing_args_fails() {
        assertFalse(tool.kbSearch(ctx, Map.of("shopId", 1L)).isSuccess());
        assertFalse(tool.kbSearch(ctx, Map.of("query", "x")).isSuccess());
    }
}
