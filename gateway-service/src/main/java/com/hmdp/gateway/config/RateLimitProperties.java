package com.hmdp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关路径级限流规则（SPEC-14 P0-1）。
 *
 * <p>规则由配置驱动而非硬编码：新增受保护路径只需加一条 yaml，无需改代码。
 * 原 {@code AgentRateLimitFilter} 的 {@code /agent/} 行为已迁入规则表首条承载。
 */
@Component
@ConfigurationProperties(prefix = "hmdp.rate-limit")
@Data
public class RateLimitProperties {

    /** 限流规则；按声明顺序逐条判定，任一失败即 429 */
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {
        /** 路径前缀，如 /voucher-order/seckill/ */
        private String pathPrefix;
        /** 限流维度：{@code loginId}（登录用户）或 {@code ip} */
        private String dimension;
        /** 令牌桶容量（突发上限） */
        private int capacity;
        /** 每秒补充的令牌数 */
        private int refillPerSec;
        /** 超限返回给调用方的文案 */
        private String message = "操作太频繁啦，请稍后再试";
    }
}
