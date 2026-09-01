package com.hmdp.agent.ticket;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 工单去重键单测（PRD 6.1 补充字段 dedup_key）
 */
class TicketDedupKeyTest {

    @Test
    void sameRefs_sameKey_regardless_of_order() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("orderId", 1001L);
        a.put("shopId", 8L);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("shopId", 8L);
        b.put("orderId", 1001L);
        assertEquals(TicketService.buildDedupKey(1L, "MERCHANT_SERVICE", a),
                TicketService.buildDedupKey(1L, "MERCHANT_SERVICE", b));
    }

    @Test
    void differentCategory_or_session_differentKey() {
        Map<String, Object> refs = Map.of("orderId", 1001L);
        assertNotEquals(TicketService.buildDedupKey(1L, "ORDER", refs),
                TicketService.buildDedupKey(1L, "VOUCHER", refs));
        assertNotEquals(TicketService.buildDedupKey(1L, "ORDER", refs),
                TicketService.buildDedupKey(2L, "ORDER", refs));
    }
}
