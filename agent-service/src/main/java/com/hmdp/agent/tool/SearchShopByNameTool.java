package com.hmdp.agent.tool;

import com.hmdp.agent.feign.ShopFeignClient;
import com.hmdp.agent.security.Desensitizer;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 店名搜索工具（FR-07，T3.10）：模糊匹配多个结果 → 列候选让用户选择，不猜（验收 11）
 * 去空格后唯一精确命中 → 直接返回详情结构
 */
@Component
@AgentToolHost
@Slf4j
@RequiredArgsConstructor
public class SearchShopByNameTool {

    private static final int MAX_CANDIDATES = 10;

    private final ShopFeignClient shopFeignClient;

    @AgentTool(name = "search_shop_by_name",
            friendlyText = "正在为您搜索商户…",
            description = "按店名模糊搜索商户。参数：name(必填,店名关键词)。多个结果时返回候选列表，必须请用户选择，禁止猜测")
    public ToolResult searchShopByName(ToolContext ctx, Map<String, Object> args) {
        Object nameObj = args.get("name");
        String name = nameObj == null ? null : String.valueOf(nameObj).trim();
        if (name == null || name.isBlank()) {
            return ToolResult.fail("NAME_REQUIRED", "请提供店名关键词");
        }
        Result result;
        try {
            result = shopFeignClient.queryShopByName(name, 1);
        } catch (Exception e) {
            log.warn("shop-service 搜索失败: name={}", name, e);
            return ToolResult.fail("SHOP_QUERY_FAIL", "商户服务暂时繁忙，请稍后再试");
        }
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())
                || !(result.getData() instanceof List)) {
            return ToolResult.builder().success(true).data(List.of())
                    .summary("未找到名称包含「" + name + "」的商户").build();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.getData();
        if (records.isEmpty()) {
            return ToolResult.builder().success(true).data(List.of())
                    .summary("未找到名称包含「" + name + "」的商户").build();
        }

        // 唯一精确命中（去空格比对）→ 直接详情
        List<Map<String, Object>> exact = records.stream()
                .filter(r -> name.replaceAll("\\s", "").equals(
                        String.valueOf(r.get("name")).replaceAll("\\s", "")))
                .toList();
        if (exact.size() == 1) {
            Map<String, Object> s = exact.get(0);
            Map<String, Object> data = new HashMap<>(s);
            data.put("shopId", toLong(s.get("id")));
            data.put("businessStatus", OpenHoursParser.status(
                    s.get("openHours") == null ? null : String.valueOf(s.get("openHours")), LocalDateTime.now()));
            return ToolResult.builder().success(true).data(data)
                    .summary(Desensitizer.mask("已查到商户「" + s.get("name") + "」")).build();
        }

        // 多结果 → 候选卡片（不猜）
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> r : records) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            Long id = toLong(r.get("id"));
            candidates.add(Map.of(
                    "shopId", id == null ? 0L : id,
                    "name", String.valueOf(r.get("name")),
                    "area", r.get("area") == null ? "" : String.valueOf(r.get("area"))));
        }
        return ToolResult.builder()
                .success(true)
                .data(candidates)
                .summary("找到 " + candidates.size() + " 家匹配商户，请用户选择，不要猜测")
                .cardPayload(Map.of("SHOP_CANDIDATES", Map.of("shops", candidates)))
                .build();
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
