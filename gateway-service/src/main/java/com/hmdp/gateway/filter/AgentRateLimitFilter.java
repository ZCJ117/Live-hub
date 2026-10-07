package com.hmdp.gateway.filter;

import com.hmdp.gateway.limit.AgentTokenBucketLimiter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
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

import java.nio.charset.StandardCharsets;

/**
 * Agent 路由独立限流（FR-11 T4.10，R6）
 * 仅作用于 /agent/**；维度=Sa-Token loginId；超出返回 429 + 友好 JSON（不封禁，次日自然恢复）
 * loginId 解析使用 StpUtil.getLoginIdByToken（同步 Redis 读）——与既有 SaTokenGatewayConfig
 * 在网关内同步调用 Sa-Token 的先例一致（本服务为低 QPS 客服入口，可接受）
 */
@Component
@RequiredArgsConstructor
public class AgentRateLimitFilter implements GlobalFilter, Ordered {

    private static final String AGENT_PREFIX = "/agent/";
    private static final String RATE_LIMIT_BODY =
            "{\"success\":false,\"errorMsg\":\"操作太频繁啦，请稍后再试\",\"code\":429}";

    private final AgentTokenBucketLimiter limiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(AGENT_PREFIX)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isBlank()) {
            // 有意放行（SPEC-06 §1.8）：未登录流量紧接着会被 SaTokenGatewayConfig 在本过滤器之后拒绝，
            // 此处不重复处理。属已知的纵深防御缺口，非漏洞。
            return chain.filter(exchange);
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            // 同上：无效 token 由 SaTokenGatewayConfig 拦截
            return chain.filter(exchange);
        }
        if (limiter.tryAcquire("agent:rl:" + loginId)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        DataBuffer buffer = response.bufferFactory().wrap(RATE_LIMIT_BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
