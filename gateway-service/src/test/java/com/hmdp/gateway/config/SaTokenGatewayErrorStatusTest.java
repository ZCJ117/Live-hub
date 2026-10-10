package com.hmdp.gateway.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.filter.SaFilterErrorStrategy;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关鉴权失败必须返回 401，而不是 200（BUG-03 回归锁）
 *
 * <p><b>背景</b>：{@code SaReactorFilter.setError} 的返回值会被
 * {@code SaReactorOperateUtil.writeResult} 用 {@code String.valueOf(...)} 只写进**响应体**，
 * 完全不碰状态码。因此网关未登录时对任意路径都返回
 * {@code HTTP 200 {"success":false,"errorMsg":"未登录，请先登录"}}——前端与监控无法按状态码识别鉴权失败，
 * 而且掩盖了路由缺失（404 只在带 token 时才浮现）。
 *
 * <p>本测试直接驱动真实的失败策略：构造一个 mock 的 {@code ServerWebExchange} 放进 Sa-Token 的
 * reactor 上下文，调用 {@code SaReactorFilter.error}，断言响应状态码。
 * 不启 Spring、不连 Nacos/Redis。
 */
class SaTokenGatewayErrorStatusTest {

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        // 非 Spring 环境下 Sa-Token 没有上下文实现，需手动装 reactor 版（否则 SaHolder.getResponse() 不可用）
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
        exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/seckill/consistency/pending").build());
        SaReactorSyncHolder.setContext(exchange);
    }

    @AfterEach
    void tearDown() {
        SaReactorSyncHolder.clearContext();
    }

    private Object invokeAuthError() {
        SaReactorFilter filter = new SaTokenGatewayConfig().saReactorFilter();
        SaFilterErrorStrategy error = filter.error;
        assertNotNull(error, "SaReactorFilter 必须配置 setError，否则鉴权失败会裸抛异常");
        return error.run(new RuntimeException("未登录，请先登录"));
    }

    @Test
    void 鉴权失败必须返回401而不是200() {
        invokeAuthError();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(),
                "网关鉴权失败返回了 " + exchange.getResponse().getStatusCode()
                        + "。sa-token 的 setError 返回值只写响应体不动状态码，必须显式设置 401，"
                        + "否则前端与监控无法按状态码识别鉴权失败，且会掩盖路由缺失 (BUG-03)");
    }

    @Test
    void 响应体仍是统一的Result结构() {
        Object body = invokeAuthError();

        assertNotNull(body, "鉴权失败必须返回响应体");
        assertTrue(body.toString().contains("\"success\":false"),
                "响应体应保持 Result 统一结构，实际=" + body);
    }
}
