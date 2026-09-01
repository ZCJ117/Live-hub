package com.hmdp.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.feign.OrderFeignClient;
import com.hmdp.agent.security.Desensitizer;
import com.hmdp.dto.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单查询工具（FR-05，Phase 2 唯一端到端工具，PRD 第 7 章 MS1）
 * 归属校验：userId 来自 ToolContext（登录态），orderId 仅作为聚焦过滤，越权查询返回空（不泄露存在性）
 */
@Component
@AgentToolHost
@Slf4j
@RequiredArgsConstructor
public class QueryMyOrdersTool {

    private final OrderFeignClient orderFeignClient;
    private final ObjectMapper objectMapper;

    @AgentTool(name = "query_my_orders",
            friendlyText = "正在为您查询订单…",
            description = "查询当前用户的优惠券订单。参数：orderId(可空,聚焦某订单), status(可空,1未支付/2已支付/3已核销/4已取消/5退款中/6已退款), days(可空,最近N天,默认7), page(可空,页码), size(可空,每页条数,默认5)")
    public ToolResult queryMyOrders(ToolContext ctx, Map<String, Object> args) {
        try {
            Long orderId = extractLong(args.get("orderId"));
            // 焦点订单优先（用户点选后聚焦，FR-05 交互 4）；仅允许本人上下文内聚焦
            if (orderId == null) {
                orderId = ctx.getFocusOrderId();
            }
            Integer status = extractInt(args.get("status"));
            Integer days = extractInt(args.get("days"));
            Integer page = extractInt(args.get("page"));
            Integer size = extractInt(args.get("size"));

            Result result = orderFeignClient.queryMyOrders(orderId, status, days, page, size);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
                String msg = result == null ? "订单服务无响应" : result.getErrorMsg();
                return ToolResult.fail("ORDER_QUERY_FAIL", "订单服务暂时繁忙，请稍后再试");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = (List<Map<String, Object>>) result.getData();
            List<OrderCardDTO> cards = new ArrayList<>();
            if (records != null) {
                for (Map<String, Object> r : records) {
                    cards.add(OrderCardDTO.from(
                            toLong(r.get("id")),
                            (String) r.get("voucherTitle"),
                            toLong(r.get("payValue")),
                            toLong(r.get("actualValue")),
                            toInt(r.get("status")),
                            parseTime(r.get("createTime"))));
                }
            }

            if (cards.isEmpty()) {
                return ToolResult.builder()
                        .success(true)
                        .data(cards)
                        .summary("未找到符合条件的订单记录")
                        .cardPayload(Map.of("ORDER_LIST", Map.of("orders", cards, "total", 0)))
                        .build();
            }

            String summary = Desensitizer.mask("已找到 " + cards.size() + " 条订单记录："
                    + cards.get(0).getVoucherTitle()
                    + "（" + cards.get(0).getStatusText() + "）" + (cards.size() > 1 ? " 等" : ""));
            Map<String, Object> payload = new HashMap<>();
            payload.put("orders", cards);
            payload.put("total", result.getTotal() == null ? cards.size() : result.getTotal());

            return ToolResult.builder()
                    .success(true)
                    .data(cards)
                    .summary(summary)
                    .cardPayload(Map.of("ORDER_LIST", payload))
                    .build();
        } catch (Exception e) {
            log.error("query_my_orders 执行失败", e);
            return ToolResult.fail("ORDER_QUERY_ERROR", "订单服务暂时繁忙，请稍后再试");
        }
    }

    private Long extractLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer extractInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** order-service 经 Jackson 序列化的 ISO-8601 时间串 → LocalDateTime（解析失败不阻塞卡片返回） */
    private LocalDateTime parseTime(Object v) {
        if (v instanceof String s && !s.isBlank()) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Long toLong(Object v) {
        return extractLong(v);
    }

    private Integer toInt(Object v) {
        return extractInt(v);
    }
}
