package com.hmdp.gateway.filter;

import com.hmdp.gateway.config.RateLimitProperties;
import com.hmdp.gateway.limit.AgentTokenBucketLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网关路径级限流（SPEC-14 P0-1）
 *
 * <p>纯单元测试：不启 Spring、不连 Redis（limiter 为 mock），可入 CI。
 * loginId 维度依赖静态的 {@code StpUtil}，故「未登录时放行」用无 token 的请求覆盖。
 */
@ExtendWith(MockitoExtension.class)
// setUp 里对 meterRegistry.counter 的桩只在「有限流命中」的用例中被用到，
// STRICT_STUBS 会把未用到的桩判为 UnnecessaryStubbing 而让其余用例变红
@MockitoSettings(strictness = Strictness.LENIENT)
class PathRateLimitFilterTest {

    @Mock private AgentTokenBucketLimiter limiter;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;
    @Mock private GatewayFilterChain chain;

    private final List<RateLimitProperties.Rule> rules = new ArrayList<>();

    private PathRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setRules(rules);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(chain.filter(any())).thenReturn(Mono.empty());
        filter = new PathRateLimitFilter(limiter, props, meterRegistry);
    }

    private static RateLimitProperties.Rule rule(String prefix, String dimension, int capacity, int refill) {
        RateLimitProperties.Rule r = new RateLimitProperties.Rule();
        r.setPathPrefix(prefix);
        r.setDimension(dimension);
        r.setCapacity(capacity);
        r.setRefillPerSec(refill);
        r.setMessage(dimension + " 维度超限");
        return r;
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .remoteAddress(new InetSocketAddress("10.0.0.7", 51234)));
    }

    @Test
    void 无规则命中时直接放行() {
        rules.add(rule("/agent/", "ip", 10, 10));

        filter.filter(exchange("/voucher-order/seckill/1"), chain).block();

        verifyNoInteractions(limiter);
        verify(chain).filter(any());
    }

    @Test
    void IP维度超限返回429() {
        rules.add(rule("/voucher-order/seckill/", "ip", 20, 5));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        verify(counter).increment();
    }

    @Test
    void IP维度未超限时放行_且按规则参数调用限流器() {
        rules.add(rule("/voucher-order/seckill/", "ip", 20, 5));
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);

        filter.filter(exchange("/voucher-order/seckill/1"), chain).block();

        // 断言规则参数被真正透传，而不是用了 limiter 的构造期默认值（SPEC-14 §7 M1）
        verify(limiter).tryAcquire("gw:rl:/voucher-order/seckill/:ip:10.0.0.7", 20, 5);
        verify(chain).filter(any());
    }

    @Test
    void loginId维度无token时放行_不重复处理鉴权() {
        rules.add(rule("/voucher-order/seckill/", "loginId", 5, 1));

        filter.filter(exchange("/voucher-order/seckill/1"), chain).block();

        // 有意放行（SPEC-06 §1.8）：紧接着由 SaTokenGatewayConfig 拒绝
        verifyNoInteractions(limiter);
        verify(chain).filter(any());
    }

    @Test
    void 多规则任一超限即拦截() {
        rules.add(rule("/voucher-order/seckill/", "loginId", 5, 1));
        rules.add(rule("/voucher-order/seckill/", "ip", 20, 5));
        // loginId 规则因无 token 跳过；ip 规则拦截
        when(limiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
    }

    @Test
    void 过滤器顺序保持10_维持与SaToken过滤器的相对位置() {
        assertEquals(-10, filter.getOrder());
    }
}
