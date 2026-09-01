package com.hmdp.agent.tool;

import com.hmdp.agent.feign.ShopFeignClient;
import com.hmdp.agent.security.Desensitizer;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 商户查询工具（FR-07，T3.10）
 * 营业状态由 openHours 推导（businessStatus），LLM 不得常识推测（PRD 3.7 边界）
 */
@Component
@AgentToolHost
@Slf4j
@RequiredArgsConstructor
public class QueryShopTool {

    private final ShopFeignClient shopFeignClient;

    @AgentTool(name = "query_shop",
            friendlyText = "正在为您查询商户信息…",
            description = "查询商户详情：营业状态/地址/评分/人均/营业时间。参数：shopId(必填,商户ID)；只有店名时先用 search_shop_by_name")
    public ToolResult queryShop(ToolContext ctx, Map<String, Object> args) {
        Long shopId = extractLong(args.get("shopId"));
        if (shopId == null) {
            return ToolResult.fail("SHOP_ID_REQUIRED", "请提供商户ID，或先用 search_shop_by_name 按店名搜索");
        }
        Result result;
        try {
            result = shopFeignClient.queryShopById(shopId);
        } catch (Exception e) {
            log.warn("shop-service 调用失败: shopId={}", shopId, e);
            return ToolResult.fail("SHOP_QUERY_FAIL", "商户服务暂时繁忙，请稍后再试");
        }
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return ToolResult.fail("SHOP_NOT_FOUND", "未找到该商户");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) result.getData();
        Map<String, Object> data = new HashMap<>(s);
        data.put("shopId", shopId);
        // 营业状态推导（实时以 openHours 计算，非 LLM 推测）
        data.put("businessStatus", OpenHoursParser.status(
                s.get("openHours") == null ? null : String.valueOf(s.get("openHours")), LocalDateTime.now()));
        String summary = Desensitizer.mask(
                "已查到商户「" + s.get("name") + "：" + data.get("businessStatus")
                        + "，地址 " + s.get("address") + "」");
        return ToolResult.builder().success(true).data(data).summary(summary).build();
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
