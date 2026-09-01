package com.hmdp.agent.parity;

import com.hmdp.agent.tool.QueryShopTool;
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
 * FR-07 验收 1：商户字段准确率 100%（对拍）
 */
@SpringBootTest
@Tag("parity")
@EnabledIf(value = "com.hmdp.agent.parity.ParityTestBase#middlewareReachable",
        disabledReason = "Nacos 未启动，跳过对拍（待环境）")
class ShopParityTest extends ParityTestBase {

    @Autowired private QueryShopTool tool;

    @Test
    void shop_parity_100() throws Exception {
        List<Map<String, Object>> shops = rows(
                "SELECT id, name, address, open_hours, score, avg_price FROM tb_shop ORDER BY id LIMIT 100");
        assumeTrue(!shops.isEmpty(), "业务库无商户数据，跳过（可先播种）");
        ToolContext ctx = ToolContext.builder().sessionId(-1L).userId(-1L).build();

        for (Map<String, Object> row : shops) {
            long sid = ((Number) row.get("id")).longValue();
            ToolResult r = tool.queryShop(ctx, Map.of("shopId", sid));
            assertTrue(r.isSuccess(), "商户 " + sid + " 工具调用失败: " + r.getSummary());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertFieldEquals(data.get("name"), row.get("name"), "name", sid);
            assertFieldEquals(data.get("address"), row.get("address"), "address", sid);
            assertFieldEquals(data.get("openHours"), row.get("open_hours"), "openHours", sid);
            assertFieldEquals(data.get("score"), row.get("score"), "score", sid);
            assertFieldEquals(data.get("avgPrice"), row.get("avg_price"), "avgPrice", sid);
        }
    }
}
