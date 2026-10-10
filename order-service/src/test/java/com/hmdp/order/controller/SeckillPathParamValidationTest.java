package com.hmdp.order.controller;

import com.hmdp.dto.Result;
import com.hmdp.order.handler.SeckillExceptionHandler;
import com.hmdp.order.service.IVoucherOrderService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;
import java.util.Set;

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

    @Autowired
    private Validator validator;

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

    /**
     * 归档处理器的真实行为：控制器实际产生的 violation 经 {@link SeckillExceptionHandler} 映射为 {@code Result.fail("券ID必须为正数")}。
     *
     * <p>与用例层断言串联：非法入参确实携带「券ID必须为正数」这一条 violation message，
     * 且该消息被归档处理器原样暴露给调用方（而非被吞成通用文案）。
     * 注：真实 HTTP 400 需要完整 MVC 上下文，见 {@link #非法入参必须声明返回400状态} 的说明。
     */
    @Test
    void 校验违规经归档处理器映射为400结果() throws NoSuchMethodException {
        Set<ConstraintViolation<VoucherOrderController>> violations = validator.forExecutables()
                .validateParameters(controller,
                        VoucherOrderController.class.getMethod("seckillVoucher", Long.class),
                        new Object[]{-1L});

        Result r = new SeckillExceptionHandler()
                .handleConstraintViolation(new ConstraintViolationException(violations));

        assertEquals("券ID必须为正数", r.getErrorMsg());
        assertFalse(r.getSuccess());
    }

    /**
     * 契约断言：归档处理器必须声明 {@code @ResponseStatus(BAD_REQUEST)}。
     *
     * <p>这是本仓既有的契约测试口径（cf. {@code SeckillSchedulingContractTest} 断言 {@code @Scheduled.fixedRate()}）：
     * 处理器**声明** 400，由 Spring 的 {@code ExceptionHandlerExceptionResolver} 在运行时兑现该状态码。
     * 显式断言声明，可让后续重构若丢掉该注解时测试变红。
     *
     * <p><b>延后项：</b>真正的端到端 HTTP 状态码需要完整 MVC 上下文（MockMvc/WebMvcTest）或起服务验证；
     * 本 plan 本轮的验收层级为单元 + 契约测试，端到端 400 验证列为延后验证项。
     */
    @Test
    void 非法入参必须声明返回400状态() throws NoSuchMethodException {
        Method handler = SeckillExceptionHandler.class.getMethod(
                "handleConstraintViolation", ConstraintViolationException.class);
        ResponseStatus status = handler.getAnnotation(ResponseStatus.class);

        assertNotNull(status, "缺少 @ResponseStatus：非法入参会落到 500 兜底");
        assertEquals(HttpStatus.BAD_REQUEST, status.value());
    }
}
