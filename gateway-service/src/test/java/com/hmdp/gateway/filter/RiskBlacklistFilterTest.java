package com.hmdp.gateway.filter;

import com.hmdp.gateway.config.RiskBlacklistProperties;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网关风控黑名单（SPEC-15 P2-2）
 *
 * <p>纯单元测试：不启 Spring、不连 Redis（SetOperations 为 mock），可入 CI。
 * loginId 维度依赖静态 {@code StpUtil}，故用"无 token"覆盖"身份不可得"分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskBlacklistFilterTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;
    @Mock private GatewayFilterChain chain;

    private RiskBlacklistProperties properties;
    private RiskBlacklistFilter filter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(chain.filter(any())).thenReturn(Mono.empty());

        properties = new RiskBlacklistProperties();
        properties.setPathPrefixes(List.of("/voucher-order/seckill/"));
        properties.setMessage("该账号已被风控拦截");
        filter = new RiskBlacklistFilter(stringRedisTemplate, properties, meterRegistry);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress("10.0.0.9", 51234)));
    }

    @Test
    void 非保护路径直接放行_不查Redis() {
        filter.filter(exchange("/shop/1"), chain).block();

        verifyNoInteractions(stringRedisTemplate);
        verify(chain).filter(any());
    }

    @Test
    void IP命中黑名单时返回403并计数() {
        when(setOperations.isMember("risk:blacklist:ip", "10.0.0.9")).thenReturn(true);

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        verify(counter).increment();
    }

    @Test
    void 未命中黑名单时直通() {
        // 显式 any(Object.class)：SetOperations.isMember 有 (K,Object) 与 (K,Object...) 两个重载，
        // 裸 any() 会让编译器选中可变参重载（返回 Map），导致 thenReturn 不匹配
        when(setOperations.isMember(anyString(), any(Object.class))).thenReturn(false);

        filter.filter(exchange("/voucher-order/seckill/1"), chain).block();

        verify(chain).filter(any());
        verify(counter, never()).increment();
    }

    @Test
    void Redis异常时fail_open放行() {
        when(stringRedisTemplate.opsForSet())
                .thenThrow(new RedisConnectionFailureException("redis down"));

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        assertDoesNotThrow(() -> filter.filter(ex, chain).block());

        assertNull(ex.getResponse().getStatusCode(), "风控组件故障不得阻断下单");
        verify(chain).filter(any());
    }

    @Test
    void 过滤器顺序必须先于限流器() {
        assertEquals(-20, filter.getOrder(),
                "SPEC-15 §2.6 明确要求在限流判定**之前**做黑名单判定");
    }
}
