package com.hmdp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关风控黑名单配置（SPEC-15 P2-2）。
 *
 * <p>与 {@code RateLimitProperties} 同风格：路径由配置驱动，运维加一条 yaml 即可扩大范围。
 * 黑名单**数据**在 Redis Set 里（运维用 {@code SADD} 维护），配置只声明"哪些路径受保护"。
 */
@Component
@ConfigurationProperties(prefix = "hmdp.risk.blacklist")
@Data
public class RiskBlacklistProperties {

    /** 受黑名单保护的路由前缀；为空则过滤器整体不生效 */
    private List<String> pathPrefixes = new ArrayList<>();

    /** 命中黑名单时返回给调用方的文案 */
    private String message = "当前账号或网络环境存在风险，已被限制访问";
}
