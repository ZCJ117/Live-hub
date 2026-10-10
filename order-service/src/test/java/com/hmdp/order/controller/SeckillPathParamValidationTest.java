package com.hmdp.order.controller;

import com.hmdp.dto.Result;
import com.hmdp.order.service.IVoucherOrderService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀入参校验（SPEC-14 P0-4）
 *
 * <p>只装载 ValidationAutoConfiguration + 被测 Controller，不启 Nacos/MySQL/Redis——
 * 方法级校验由 {@code MethodValidationPostProcessor} 对 {@code @Validated} 的 bean 做代理实现，
 * 故这里注册的 Controller 会被 CGLIB 代理（该类不实现任何接口），{@code @Positive} 才真正生效。
 *
 * <p>{@code voucherOrderService} 是 ApplicationContext 级单例 mock，跨测试方法不会自动重置；
 * 故每个用例前 clearInvocations，否则 {@code verifyNoInteractions} 会看到上一个用例遗留的调用。
 */
@SpringJUnitConfig(classes = {
        ValidationAutoConfiguration.class,
        VoucherOrderController.class,
        SeckillPathParamValidationTest.TestConfig.class
})
class SeckillPathParamValidationTest {

    @Configuration
    static class TestConfig {
        @Bean
        IVoucherOrderService voucherOrderService() {
            return mock(IVoucherOrderService.class);
        }
    }

    @Autowired
    private VoucherOrderController controller;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @BeforeEach
    void resetMock() {
        clearInvocations(voucherOrderService);
    }

    @Test
    void 负数voucherId被拒且零业务调用() {
        assertThrows(ConstraintViolationException.class, () -> controller.seckillVoucher(-1L));
        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void 零voucherId被拒且零业务调用() {
        assertThrows(ConstraintViolationException.class, () -> controller.seckillVoucher(0L));
        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void 正数voucherId通过校验并进入业务() {
        when(voucherOrderService.seckillVoucher(1L)).thenReturn(Result.ok(1L));

        Result r = controller.seckillVoucher(1L);

        assertTrue(r.getSuccess());
        verify(voucherOrderService).seckillVoucher(1L);
    }
}
