package com.hmdp.agent.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.AgentTokenHolder;
import com.hmdp.agent.tool.QueryMyOrdersTool;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.tool.ToolResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FR-05 验收 1：订单查询与数据库直查 100% 一致（自动化对拍 100 条）
 */
@SpringBootTest
@Tag("parity")
@EnabledIf(value = "com.hmdp.agent.parity.ParityTestBase#middlewareReachable",
        disabledReason = "Nacos 未启动，跳过对拍（待环境）")
class OrderParityTest extends ParityTestBase {

    private static final ObjectMapper M = new ObjectMapper();

    @Autowired private QueryMyOrdersTool tool;
    @Autowired private RedissonClient redisson;

    @Test
    void order_parity_100() throws Exception {
        // 取订单数最多的用户（对拍主体）
        List<Map<String, Object>> users = rows(
                "SELECT user_id, COUNT(*) c FROM tb_voucher_order GROUP BY user_id ORDER BY c DESC LIMIT 1");
        assumeTrue(!users.isEmpty(), "业务库无订单数据，跳过（可先播种）");
        long userId = ((Number) users.get(0).get("user_id")).longValue();
        String phone = String.valueOf(rows("SELECT phone FROM tb_user WHERE id = " + userId)
                .get(0).get("phone"));

        // 登录（user-service 验证码登录：code 发送后从 Redis 读取，hmdp 约定 key: user:code:{phone}）
        httpGet("/user/code?phone=" + phone);
        String code = redisson.<String>getBucket("user:code:" + phone).get();
        assumeTrue(code != null, "验证码未写入 Redis，核对 user-service 登录实现后调整");
        String loginResp = httpPost("/user/login",
                M.writeValueAsString(Map.of("phone", phone, "code", code)));
        JsonNode loginNode = M.readTree(loginResp);
        assertTrue(loginNode.path("success").asBoolean(), "登录失败: " + loginResp);
        String token = loginNode.path("data").asText();
        AgentTokenHolder.set(token);
        try {
            List<Map<String, Object>> orders = rows(
                    "SELECT id, voucher_id, voucher_title, pay_value, actual_value, status " +
                    "FROM tb_voucher_order WHERE user_id = " + userId + " ORDER BY id LIMIT 100");
            assertFalse(orders.isEmpty());
            ToolContext ctx = ToolContext.builder().sessionId(-1L).userId(userId).build();

            for (Map<String, Object> row : orders) {
                long orderId = ((Number) row.get("id")).longValue();
                // 逐单走工具全链路（焦点指定该订单）
                ToolResult r = tool.queryMyOrders(ctx, Map.of("orderId", orderId));
                assertTrue(r.isSuccess(), "订单 " + orderId + " 工具调用失败: " + r.getSummary());
                @SuppressWarnings("unchecked")
                List<com.hmdp.agent.dto.OrderCardDTO> cards =
                        (List<com.hmdp.agent.dto.OrderCardDTO>) r.getData();
                assertEquals(1, cards.size(), "订单 " + orderId + " 应精确返回 1 条");
                com.hmdp.agent.dto.OrderCardDTO card = cards.get(0);
                assertFieldEquals(card.getOrderId(), orderId, "orderId", orderId);
                assertFieldEquals(card.getVoucherId(), row.get("voucher_id"), "voucherId", orderId);
                assertFieldEquals(card.getVoucherTitle(), row.get("voucher_title"), "voucherTitle", orderId);
                assertFieldEquals(card.getPayValue(), row.get("pay_value"), "payValue", orderId);
                assertFieldEquals(card.getActualValue(), row.get("actual_value"), "actualValue", orderId);
                assertFieldEquals(card.getStatusCode(), row.get("status"), "status", orderId);
            }
        } finally {
            AgentTokenHolder.clear();
        }
    }
}
