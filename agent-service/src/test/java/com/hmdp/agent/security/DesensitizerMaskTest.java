package com.hmdp.agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T4.13 数据红线：手机号/支付凭证不进 LLM 上下文 */
class DesensitizerMaskTest {

    @Test
    void 手机号脱敏() {
        String masked = Desensitizer.mask("联系手机号 13812345678 请查收");
        assertFalse(masked.contains("13812345678"), "手机号明文不得出现在 masked 输出");
    }

    @Test
    void 无敏感信息原样保留() {
        assertTrue(Desensitizer.mask("订单 200 已支付，券名国庆5折券").contains("国庆5折券"));
    }

    @Test
    void null输入不抛异常() {
        assertDoesNotThrow(() -> Desensitizer.mask(null));
    }
}
