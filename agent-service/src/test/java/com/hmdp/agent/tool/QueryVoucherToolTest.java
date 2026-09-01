package com.hmdp.agent.tool;

import com.hmdp.agent.feign.ShopFeignClient;
import com.hmdp.agent.feign.VoucherFeignClient;
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
 * 券查询工具单测（T3.8/FR-06）：规则完整性标记 + 适用范围附店名 + 防编造（缺失=null 不默认值）
 */
class QueryVoucherToolTest {

    private final VoucherFeignClient voucherFeign = mock(VoucherFeignClient.class);
    private final ShopFeignClient shopFeign = mock(ShopFeignClient.class);
    private final QueryVoucherTool tool = new QueryVoucherTool(voucherFeign, shopFeign);
    private final ToolContext ctx = ToolContext.builder().sessionId(1L).userId(10L).build();

    private Map<String, Object> voucher() {
        Map<String, Object> v = new HashMap<>();
        v.put("id", 8L);
        v.put("title", "满100减30券");
        v.put("rules", "满100元可用，限堂食");
        v.put("payValue", 7000L);
        v.put("actualValue", 10000L);
        v.put("status", 1);
        v.put("threshold", "100");
        v.put("applicableScope", "[3,4]");
        return v;
    }

    @Test
    void returns_voucher_with_rules_complete() {
        when(voucherFeign.queryVoucherById(8L)).thenReturn(Result.ok(voucher()));
        when(shopFeign.queryShopById(3L)).thenReturn(Result.ok(Map.of("id", 3L, "name", "A店")));
        when(shopFeign.queryShopById(4L)).thenReturn(Result.ok(Map.of("id", 4L, "name", "B店")));

        ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", 8L));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(Boolean.TRUE, data.get("rulesComplete"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shops = (List<Map<String, Object>>) data.get("applicableShops");
        assertEquals(2, shops.size());
        assertEquals("A店", shops.get(0).get("shopName"));
    }

    @Test
    void missing_rules_marks_incomplete_not_invented() {
        Map<String, Object> v = voucher();
        v.put("threshold", null);        // 运营未录入
        v.put("applicableScope", null);
        when(voucherFeign.queryVoucherById(8L)).thenReturn(Result.ok(v));

        ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", 8L));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(Boolean.FALSE, data.get("rulesComplete"));
        assertFalse(data.containsKey("applicableShops"));
        assertTrue(r.getSummary().contains("暂未录入"));
    }

    @Test
    void requires_voucher_id_with_orders_guidance() {
        ToolResult r = tool.queryVoucher(ctx, Map.of());
        assertFalse(r.isSuccess());
        assertEquals("VOUCHER_ID_REQUIRED", r.getErrorCode());
        assertTrue(r.getSummary().contains("query_my_orders"));
    }

    @Test
    void feign_failure_is_fail_result() {
        when(voucherFeign.queryVoucherById(8L)).thenThrow(new RuntimeException("timeout"));
        ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", 8L));
        assertFalse(r.isSuccess());
        assertEquals("VOUCHER_QUERY_FAIL", r.getErrorCode());
    }

    @Test
    void voucher_not_found() {
        when(voucherFeign.queryVoucherById(99L)).thenReturn(Result.fail("券不存在"));
        ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", 99L));
        assertFalse(r.isSuccess());
        assertEquals("VOUCHER_NOT_FOUND", r.getErrorCode());
    }

    @Test
    void applicable_scope_shop_lookup_failure_is_tolerated() {
        when(voucherFeign.queryVoucherById(8L)).thenReturn(Result.ok(voucher()));
        when(shopFeign.queryShopById(any())).thenThrow(new RuntimeException("down"));

        ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", 8L));
        assertTrue(r.isSuccess()); // 主链路不受店铺名查询影响
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertFalse(data.containsKey("applicableShops"));
    }
}
