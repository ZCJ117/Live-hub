package com.hmdp.agent.tool;

import com.hmdp.agent.feign.ShopFeignClient;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商户查询/搜索工具单测（T3.10/FR-07）：结构化字段 + 营业状态推导 + 多候选不猜
 */
class ShopToolsTest {

    private final ShopFeignClient shopFeign = mock(ShopFeignClient.class);
    private final QueryShopTool queryShop = new QueryShopTool(shopFeign);
    private final SearchShopByNameTool searchShop = new SearchShopByNameTool(shopFeign);
    private final ToolContext ctx = ToolContext.builder().sessionId(1L).userId(10L).build();

    private Map<String, Object> shop(long id, String name) {
        return new HashMap<>(Map.of(
                "id", id, "name", name, "address", "xx路1号", "area", "大关",
                "openHours", "09:00-22:00", "score", 45, "avgPrice", 80L, "sold", 1000));
    }

    @Test
    void query_shop_returns_structured_fields_with_derived_business_status() {
        when(shopFeign.queryShopById(1L)).thenReturn(Result.ok(shop(1L, "星巴克")));
        ToolResult r = queryShop.queryShop(ctx, Map.of("shopId", 1L));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("星巴克", data.get("name"));
        assertEquals("xx路1号", data.get("address"));
        assertEquals(45, data.get("score"));
        // 营业状态由工具层推导（12 点在 09:00-22:00 内 → 营业中）
        assertEquals("营业中", data.get("businessStatus"));
    }

    @Test
    void query_shop_requires_shop_id() {
        ToolResult r = queryShop.queryShop(ctx, Map.of());
        assertFalse(r.isSuccess());
        assertEquals("SHOP_ID_REQUIRED", r.getErrorCode());
    }

    @Test
    void query_shop_fail_on_feign_error() {
        when(shopFeign.queryShopById(1L)).thenThrow(new RuntimeException("timeout"));
        ToolResult r = queryShop.queryShop(ctx, Map.of("shopId", 1L));
        assertFalse(r.isSuccess());
        assertEquals("SHOP_QUERY_FAIL", r.getErrorCode());
    }

    @Test
    void query_shop_not_found() {
        when(shopFeign.queryShopById(99L)).thenReturn(Result.fail("不存在"));
        ToolResult r = queryShop.queryShop(ctx, Map.of("shopId", 99L));
        assertFalse(r.isSuccess());
        assertEquals("SHOP_NOT_FOUND", r.getErrorCode());
    }

    @Test
    void search_multiple_results_lists_candidates_no_guess() {
        when(shopFeign.queryShopByName("星巴克", 1)).thenReturn(Result.ok(List.of(
                shop(1L, "星巴克（大关店）"), shop(2L, "星巴克（运河店）"))));
        ToolResult r = searchShop.searchShopByName(ctx, Map.of("name", "星巴克"));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.getCardPayload().get("SHOP_CANDIDATES");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shops = (List<Map<String, Object>>) payload.get("shops");
        assertEquals(2, shops.size());
        assertTrue(r.getSummary().contains("请"));
    }

    @Test
    void search_unique_exact_match_returns_detail() {
        when(shopFeign.queryShopByName("星巴克", 1)).thenReturn(Result.ok(List.of(
                shop(1L, "星巴克"), shop(2L, "星巴克烘焙工坊"))));
        ToolResult r = searchShop.searchShopByName(ctx, Map.of("name", "星巴克"));
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("星巴克", data.get("name")); // 唯一精确命中 → 直接详情
        assertNull(r.getCardPayload());
    }

    @Test
    void search_no_result_is_success_with_empty_hint() {
        when(shopFeign.queryShopByName("不存在店", 1)).thenReturn(Result.ok(List.of()));
        ToolResult r = searchShop.searchShopByName(ctx, Map.of("name", "不存在店"));
        assertTrue(r.isSuccess());
        assertTrue(r.getSummary().contains("未找到"));
    }

    @Test
    void search_requires_name() {
        ToolResult r = searchShop.searchShopByName(ctx, Map.of());
        assertFalse(r.isSuccess());
        assertEquals("NAME_REQUIRED", r.getErrorCode());
    }
}
