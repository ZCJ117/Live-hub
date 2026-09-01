package com.hmdp.agent.tool;

import com.hmdp.agent.feign.RagFeignClient;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 商户知识库检索工具（FR-07 扩展，T3.11）
 * 完全降级原则（D1.4 C4）：无资料/调用失败 → success=true + 空 hits + 降级提示，
 * 不计工具失败、不触发连续失败中断；主链路不依赖 RAG
 */
@Component
@AgentToolHost
@Slf4j
@RequiredArgsConstructor
public class KbSearchTool {

    private static final int DEFAULT_TOP_K = 3;

    private final RagFeignClient ragFeignClient;

    @AgentTool(name = "kb_search",
            friendlyText = "正在检索商户资料…",
            description = "检索商户知识库（菜品/招牌/特色等扩展信息）。参数：shopId(必填), query(必填,检索问题), topK(可空,默认3)。返回内容来源为商户资料，引用时标注'据商户资料'")
    public ToolResult kbSearch(ToolContext ctx, Map<String, Object> args) {
        Long shopId = extractLong(args.get("shopId"));
        Object queryObj = args.get("query");
        String query = queryObj == null ? null : String.valueOf(queryObj);
        if (shopId == null || query == null || query.isBlank()) {
            return ToolResult.fail("KB_ARGS_REQUIRED", "需要 shopId 与 query 参数");
        }
        try {
            Result result = ragFeignClient.search(Map.of("shopId", shopId, "query", query, "topK", DEFAULT_TOP_K));
            List<Map<String, Object>> hits = extractHits(result);
            if (hits.isEmpty()) {
                return ToolResult.builder().success(true).data(Map.of("hits", List.of()))
                        .summary("商户知识库暂无该问题相关资料，仅能提供基础信息").build();
            }
            // 来源标注（FR-07 验收 3：100% 带来源）
            hits.forEach(h -> h.put("source", "据商户资料"));
            return ToolResult.builder().success(true).data(Map.of("hits", hits))
                    .summary("已检索到 " + hits.size() + " 条商户资料").build();
        } catch (Exception e) {
            log.warn("rag-service 检索失败，完全降级: shopId={}", shopId, e);
            return ToolResult.builder().success(true).data(Map.of("hits", List.of()))
                    .summary("扩展信息暂不可用，仅提供基础信息").build();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractHits(Result result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof Map)) {
            return List.of();
        }
        Object hits = ((Map<String, Object>) result.getData()).get("hits");
        return hits instanceof List ? (List<Map<String, Object>>) hits : List.of();
    }

    private Long extractLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
