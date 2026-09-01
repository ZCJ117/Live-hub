package com.hmdp.agent.tool;

import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.feign.OrderFeignClient;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单查询增强单测（T3.7/FR-05）：超时自动重试 1 次 / size 截断分页 / 卡片含 voucherId
 */
class QueryMyOrdersToolRetryTest {

    private final OrderFeignClient orderFeign = mock(OrderFeignClient.class);
    private final QueryMyOrdersTool tool = new QueryMyOrdersTool(orderFeign);
    private final ToolContext ctx = ToolContext.builder().sessionId(1L).userId(10L).focusOrderId(42L).build();

    private Map<String, Object> orderRow(long id, long voucherId, int status) {
        return Map.of("id", id, "voucherId", voucherId, "voucherTitle", "满100减30券",
                "payValue", 7000L, "actualValue", 10000L, "status", status,
                "createTime", "2026-08-30T10:00:00");
    }

    @Test
    void retries_once_after_timeout_then_succeeds() {
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("read timeout"))
                .thenReturn(Result.ok(List.of(orderRow(1L, 8L, 2)), 1L));

        ToolResult r = tool.queryMyOrders(ctx, Map.of());
        assertTrue(r.isSuccess()); // T3.7：超时自动重试 1 次
        verify(orderFeign, times(2)).queryMyOrders(any(), any(), any(), any(), any());
    }

    @Test
    void double_failure_returns_friendly_fail() {
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenThrow(new RuntimeException("timeout"));

        ToolResult r = tool.queryMyOrders(ctx, Map.of());
        assertFalse(r.isSuccess());
        assertEquals("ORDER_TIMEOUT", r.getErrorCode());
        assertTrue(r.getSummary().contains("暂时繁忙")); // FR-05 话术
    }

    @Test
    void cards_carry_voucher_id() {
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenReturn(Result.ok(List.of(orderRow(1L, 8L, 2)), 1L));

        ToolResult r = tool.queryMyOrders(ctx, Map.of());
        @SuppressWarnings("unchecked")
        List<OrderCardDTO> cards = (List<OrderCardDTO>) r.getData();
        assertEquals(8L, cards.get(0).getVoucherId());
        assertEquals(Boolean.FALSE, cards.get(0).getCancelled());
    }

    @Test
    void cancelled_order_flagged() {
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenReturn(Result.ok(List.of(orderRow(2L, 9L, 4)), 1L));

        ToolResult r = tool.queryMyOrders(ctx, Map.of());
        @SuppressWarnings("unchecked")
        List<OrderCardDTO> cards = (List<OrderCardDTO>) r.getData();
        assertEquals(Boolean.TRUE, cards.get(0).getCancelled()); // 置灰标记
    }

    @Test
    void size_capped_and_paging_hint_when_more() {
        // 库里 120 条（total=120），单页最多 5 条 → summary 提示分页
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenReturn(Result.ok(List.of(
                        orderRow(1L, 8L, 2), orderRow(2L, 8L, 2), orderRow(3L, 8L, 2),
                        orderRow(4L, 8L, 2), orderRow(5L, 8L, 2)), 120L));

        ToolResult r = tool.queryMyOrders(ctx, Map.of("size", 50));
        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(orderFeign).queryMyOrders(any(), any(), any(), any(), sizeCaptor.capture());
        assertEquals(5, sizeCaptor.getValue()); // 强制截断到 5（FR-05 边界：>50 强制分页）
        assertTrue(r.getSummary().contains("120"));
        assertTrue(r.getSummary().contains("下一页"));
    }

    @Test
    void focus_order_used_when_no_order_id_arg() {
        when(orderFeign.queryMyOrders(any(), any(), any(), any(), any()))
                .thenReturn(Result.ok(List.of(orderRow(42L, 8L, 2)), 1L));

        tool.queryMyOrders(ctx, Map.of());
        verify(orderFeign).queryMyOrders(eq(42L), any(), any(), any(), any()); // 焦点优先
    }
}
