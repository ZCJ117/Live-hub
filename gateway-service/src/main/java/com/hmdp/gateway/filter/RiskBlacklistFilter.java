package com.hmdp.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.gateway.config.RiskBlacklistProperties;
import com.hmdp.utils.RedisConstants;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关风控黑名单（SPEC-15 P2-2）
 *
 * <p>在**限流判定之前**（{@code order = -20}，早于 {@code PathRateLimitFilter} 的 -10）
 * 做一次黑名单 {@code SISMEMBER}：黑名单是"已知恶意"的确定性结论，
 * 让它先于概率性的令牌桶生效，被拦截的原因在日志里才不含糊。
 *
 * <p>维度：IP 与登录用户。IP 维度不依赖 token，因此未登录的恶意流量也能被拦下
 * （这是限流器做不到的——它身份不可得时有意放行）。
 *
 * <p><b>fail-open</b>：Redis 查询异常一律放行（与 P0-1 限流同口径，SPEC-14 §6 验收 7）。
 * 风控是加固手段，不是主链路闸门；它的故障不得演变成秒杀入口 403 雪崩。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiskBlacklistFilter implements GlobalFilter, Ordered {

    private final StringRedisTemplate stringRedisTemplate;
    private final RiskBlacklistProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String matchedPrefix = matchPrefix(path);
        if (matchedPrefix == null) {
            return chain.filter(exchange);
        }

        try {
            String ip = resolveIp(exchange);
            if (ip != null && isBlacklisted(RedisConstants.RISK_BLACKLIST_IP_KEY, ip)) {
                return reject(exchange, matchedPrefix, "ip", ip);
            }
            String loginId = resolveLoginId(exchange);
            if (loginId != null && isBlacklisted(RedisConstants.RISK_BLACKLIST_USER_KEY, loginId)) {
                return reject(exchange, matchedPrefix, "user", loginId);
            }
        } catch (Exception e) {
            log.warn("风控黑名单查询异常，fail-open 放行: path={}", path, e);
        }
        return chain.filter(exchange);
    }

    /** @return 命中的受保护前缀；未命中返回 null */
    private String matchPrefix(String path) {
        List<String> prefixes = properties.getPathPrefixes();
        if (prefixes == null) {
            return null;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && path.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private boolean isBlacklisted(String key, String identity) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, identity));
    }

    /**
     * 取请求来源 IP，用于黑名单查询。
     *
     * <p><b>取的是传输层对端地址</b>（{@code getRemoteAddress()}），**不读
     * {@code X-Forwarded-For}**。这与 {@code PathRateLimitFilter} 同口径，
     * 两者的前提都是：**网关本身就是流量入口**（本仓部署形态如此，见 CLAUDE.md
     * 「所有请求通过网关入口」）。
     *
     * <p>⚠️ 若将来把网关部署到 LB / Nginx 之后，本方法返回的将是**代理的 IP**，
     * 后果不是"少拦"而是"多杀"——任何一个用户被误判拉黑该 IP，全部用户一起 403
     * （SPEC-15 §2.6「误杀率」）。届时必须改为
     * {@code XForwardedRemoteAddressResolver.maxTrustedIndex(n)}（{@code n} = 可信代理跳数），
     * 而不是在这里直接取 XFF 首项：后者可被客户端伪造，等于把黑名单的
     * **封禁权**交给攻击者（伪造他人 IP 即可拉黑之），也会让 IP 维度失效。
     */
    private String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    /**
     * 从 Authorization 解析 loginId。
     *
     * <p>与 {@code PathRateLimitFilter#resolveIdentity} 同口径：身份不可得返回 null，
     * 由 {@code SaTokenGatewayConfig} 在更后面对未登录流量统一处理，此处不重复鉴权。
     */
    private String resolveLoginId(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isBlank()) {
            return null;
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        return loginId == null ? null : loginId.toString();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String prefix, String dimension, String identity) {
        meterRegistry.counter("gateway.risk.blacklist.blocked",
                "path", prefix, "dimension", dimension).increment();
        log.warn("风控黑名单拦截: path={}, dimension={}, identity={}",
                exchange.getRequest().getPath().value(), dimension, identity);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String body = "{\"success\":false,\"errorMsg\":\"" + properties.getMessage() + "\",\"code\":403}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -20;
    }
}
