package com.hmdp.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.gateway.config.RateLimitProperties;
import com.hmdp.gateway.limit.AgentTokenBucketLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 路径级令牌桶限流（SPEC-14 P0-1；由 {@code AgentRateLimitFilter} 泛化而来）
 *
 * <p>按 {@code hmdp.rate-limit.rules} 声明顺序逐条判定，**任一规则超限即返回 429**。
 * 维度支持 {@code loginId}（登录用户）与 {@code ip}；秒杀入口两层都配，构成"用户 + IP"双层防刷。
 *
 * <p>身份不可得时**有意放行**（SPEC-06 §1.8）：未登录/无效 token 的流量紧接着会被
 * {@code SaTokenGatewayConfig} 拒绝，此处不重复处理。属已知的纵深防御缺口，非漏洞。
 *
 * <p>getOrder 保持 -10，维持与 SaToken 过滤器的相对位置不变。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PathRateLimitFilter implements GlobalFilter, Ordered {

    /** 桶 key 前缀：与业务限流 key 隔离，便于排障时区分 */
    private static final String BUCKET_KEY_PREFIX = "gw:rl:";

    private final AgentTokenBucketLimiter limiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");

        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (rule.getPathPrefix() == null || rule.getCapacity() <= 0 || !path.startsWith(rule.getPathPrefix())) {
                continue;
            }
            String identity = resolveIdentity(rule, exchange, token);
            if (identity == null) {
                continue;
            }
            String key = BUCKET_KEY_PREFIX + rule.getPathPrefix() + ":" + rule.getDimension() + ":" + identity;
            if (!limiter.tryAcquire(key, rule.getCapacity(), rule.getRefillPerSec())) {
                meterRegistry.counter("gateway.ratelimit.blocked",
                        "path", rule.getPathPrefix(), "dimension", rule.getDimension()).increment();
                log.warn("网关限流触发: path={}, dimension={}, identity={}", path, rule.getDimension(), identity);
                return reject(exchange, rule.getMessage());
            }
        }
        return chain.filter(exchange);
    }

    /** @return 限流身份；无法确定时返回 null（调用方据此跳过该规则） */
    private String resolveIdentity(RateLimitProperties.Rule rule, ServerWebExchange exchange, String token) {
        if ("ip".equalsIgnoreCase(rule.getDimension())) {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            if (remote == null || remote.getAddress() == null) {
                return null;
            }
            return remote.getAddress().getHostAddress();
        }
        // 默认 loginId 维度
        if (token == null || token.isBlank()) {
            return null;
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        return loginId == null ? null : loginId.toString();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String body = "{\"success\":false,\"errorMsg\":\"" + message + "\",\"code\":429}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
