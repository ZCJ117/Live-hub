package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 异常 advice 必须真正被加载且优先于通用 Exception handler（BUG-02 / BUG-04 回归锁）
 *
 * <p><b>背景</b>：{@link SaTokenExceptionHandler} 声明了"未登录 401、无权限 403"，但它既不在任何服务的
 * 组件扫描路径下（服务启动类都在 {@code com.hmdp.<module>}），也**没有登记进
 * {@code AutoConfiguration.imports}**——于是在 5 个服务里有 4 个根本没生效：shop/social/user 返回
 * Tomcat 默认 500 错误体，voucher/order 被自家 {@code GlobalExceptionHandler.handleException(Exception.class)}
 * 吞成 200 + "系统繁忙，请稍后重试"。SPEC-06 §1.7 的 401/403 实际为零。
 *
 * <p>两个断言分别锁住两件事：<b>被加载</b>（登记进 imports）与<b>被优先</b>（{@code @Order} 最高优先级，
 * 否则通用 {@code Exception} handler 会因 advice 顺序抢先命中）。
 */
class SaTokenExceptionHandlerContractTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static String importsFile() throws IOException {
        return new String(new ClassPathResource(IMPORTS_RESOURCE).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void 异常处理器必须登记进自动配置_否则5个服务里有4个加载不到() throws IOException {
        String imports = importsFile();
        assertTrue(imports.lines().map(String::trim).anyMatch("com.hmdp.config.SaTokenExceptionHandler"::equals),
                "SaTokenExceptionHandler 未登记进 " + IMPORTS_RESOURCE
                        + "，而 5 个服务的启动类都在 com.hmdp.<module> 包下、扫不到 com.hmdp.config，"
                        + "鉴权失败会退化成 500/200 而不是 401/403 (BUG-02)。当前登记项：\n" + imports);
    }

    @Test
    void 必须是ControllerAdvice() {
        assertNotNull(AnnotatedElementUtils.findMergedAnnotation(
                        SaTokenExceptionHandler.class, RestControllerAdvice.class),
                "SaTokenExceptionHandler 必须是 @RestControllerAdvice");
    }

    @Test
    void 必须优先于服务的通用Exception处理() {
        Order order = AnnotatedElementUtils.findMergedAnnotation(SaTokenExceptionHandler.class, Order.class);
        assertNotNull(order,
                "SaTokenExceptionHandler 必须标 @Order：order-service 的 "
                        + "GlobalExceptionHandler.handleException(Exception.class) 能匹配一切异常，"
                        + "advice 顺序不确定时它会抢先把 NotRoleException 吞成「系统繁忙，请稍后重试」(BUG-04)");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value(),
                "SaTokenExceptionHandler 必须取最高优先级，否则通用 Exception handler 仍可能抢先命中 (BUG-04)");
    }
}
