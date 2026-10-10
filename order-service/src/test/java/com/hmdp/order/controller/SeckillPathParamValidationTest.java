package com.hmdp.order.controller;

import com.hmdp.config.SaTokenConfig;
import com.hmdp.dto.Result;
import com.hmdp.order.handler.GlobalExceptionHandler;
import com.hmdp.order.handler.SeckillValidationExceptionHandler;
import com.hmdp.order.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀入参校验（SPEC-14 P0-4 / 验收 3）。
 *
 * <p>本用例走**整条 MVC 链路**：request → 路径变量转换 / {@code MethodValidationInterceptor}
 * → 异常 → advice → HTTP 状态码。异常必须由切片真的抛出来，断言里没有 mock 异常。
 *
 * <p><b>必须把 {@link GlobalExceptionHandler} 一并注册</b>：该兜底 advice 声明了
 * {@code @ExceptionHandler(Exception.class)}，同样"能处理"本链路的两类异常。
 * Spring 跨 advice 按顺序取第一个能处理的（不比较具体性），若
 * {@link SeckillValidationExceptionHandler} 丢了 {@code @Order}，本用例会退回
 * 200 +「系统繁忙，请稍后重试」而变红——这正是 2026-10-10 端到端实测暴露的真实缺陷，
 * 当时只断言"处理器声明了 400"的旧用例对它是盲的。
 *
 * <p>排除 {@link SaTokenConfig}：它对 {@code /**} 强制登录，否则请求会在参数校验之前
 * 先被鉴权拦截（同 {@code ShopControllerValidationTest}）。本用例检验的是参数校验链路。
 */
@WebMvcTest(controllers = VoucherOrderController.class,
        excludeAutoConfiguration = SaTokenConfig.class,
        properties = {
                "spring.cloud.bootstrap.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.discovery.enabled=false"
        })
@ContextConfiguration(classes = {
        VoucherOrderController.class,
        GlobalExceptionHandler.class,
        SeckillValidationExceptionHandler.class
})
@Import({GlobalExceptionHandler.class, SeckillValidationExceptionHandler.class})
class SeckillPathParamValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IVoucherOrderService voucherOrderService;

    @Test
    void 零voucherId返回400且零业务调用() throws Exception {
        mockMvc.perform(post("/voucher-order/seckill/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("券ID必须为正数"));

        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void 负数voucherId返回400且零业务调用() throws Exception {
        mockMvc.perform(post("/voucher-order/seckill/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMsg").value("券ID必须为正数"));

        verifyNoInteractions(voucherOrderService);
    }

    /** 超大值在路径变量转换阶段就失败，进不到 {@code @Positive}，是另一条映射。 */
    @Test
    void 超大voucherId返回400且零业务调用() throws Exception {
        mockMvc.perform(post("/voucher-order/seckill/99999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void 合法voucherId通过校验并进入业务() throws Exception {
        when(voucherOrderService.seckillVoucher(1L)).thenReturn(Result.ok(1L));

        mockMvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
