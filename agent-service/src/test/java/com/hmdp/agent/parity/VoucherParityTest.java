package com.hmdp.agent.parity;

import com.hmdp.agent.tool.QueryVoucherTool;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.tool.ToolResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FR-06 验收 1：券规则解释与数据库规则一致率 100%（对拍，含 C2 新字段 threshold/applicable_scope）
 */
@SpringBootTest
@Tag("parity")
@EnabledIf(value = "com.hmdp.agent.parity.ParityTestBase#middlewareReachable",
        disabledReason = "Nacos 未启动，跳过对拍（待环境）")
class VoucherParityTest extends ParityTestBase {

    @Autowired private QueryVoucherTool tool;

    @Test
    void voucher_parity_100() throws Exception {
        List<Map<String, Object>> vouchers = rows(
                "SELECT id, title, rules, threshold, applicable_scope, status, pay_value " +
                "FROM tb_voucher ORDER BY id LIMIT 100");
        assumeTrue(!vouchers.isEmpty(), "业务库无券数据，跳过（可先播种）");
        ToolContext ctx = ToolContext.builder().sessionId(-1L).userId(-1L).build();

        for (Map<String, Object> row : vouchers) {
            long vid = ((Number) row.get("id")).longValue();
            ToolResult r = tool.queryVoucher(ctx, Map.of("voucherId", vid));
            assertTrue(r.isSuccess(), "券 " + vid + " 工具调用失败: " + r.getSummary());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertFieldEquals(data.get("title"), row.get("title"), "title", vid);
            assertFieldEquals(data.get("rules"), row.get("rules"), "rules", vid);
            // C2 字段：缺失=null 语义保持（不默认值编造）；DECIMAL 经 JSON 反序列化为 Double（100.00→100.0），按数值比较
            assertNumericEquals(data.get("threshold"), row.get("threshold"), "threshold", vid);
            assertFieldEquals(data.get("applicableScope"), row.get("applicable_scope"), "applicableScope", vid);
            assertFieldEquals(data.get("status"), row.get("status"), "status", vid);
            assertFieldEquals(data.get("payValue"), row.get("pay_value"), "payValue", vid);
        }
    }
}
