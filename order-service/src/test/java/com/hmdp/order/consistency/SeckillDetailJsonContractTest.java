package com.hmdp.order.consistency;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 明细 Hash 值契约（SPEC-14 §7 M6）
 *
 * <p>在途补偿器（{@code SeckillInFlightCompensator}）按 {@code ts} 判龄、按 {@code retryCount}
 * 决定是重投还是释放。这两个字段由 Lua 与 DLQ 消费者**共同**写入，任一侧漏写都会让补偿器
 * 静默跳过该条目（ts 缺失 → 不判龄），泄漏因此不可见。故用脚本文本断言把契约钉住。
 */
class SeckillDetailJsonContractTest {

    @Test
    void lua写入的明细JSON必须含ts与retryCount字段() throws IOException {
        String lua = new String(new ClassPathResource("seckill.lua").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(lua.contains("ts = ts"),
                "seckill.lua 的明细 JSON 必须写入 ts（在途补偿器据此判龄），当前脚本缺少该字段");
        assertTrue(lua.contains("retryCount"),
                "seckill.lua 的明细 JSON 必须写入 retryCount（补偿器据此决定重投还是释放）");
        assertTrue(lua.contains("ARGV[4]"),
                "ts 必须来自 Java 侧传入的 ARGV[4]：Lua 沙箱无可靠的 os.time");
    }
}
