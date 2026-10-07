package com.hmdp.config;

import cn.dev33.satoken.stp.StpUtil;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 用户态身份透传（SPEC-07 §5.2）
 *
 * <p>把当前请求的 Sa-Token token 透传给下游业务服务，使其能识别登录态与做归属校验。
 * 参照 agent-service 既有的 FeignAuthConfig 实践下沉到 common，供所有服务复用。
 *
 * <p>与 {@link InternalTokenFeignConfig} 的区分：用户态调用（点赞榜、共同关注、
 * 订单查询）走本配置透传 Authorization；内部后台调用（扣库存、检索）走共享密钥。
 */
@Configuration
public class FeignTokenRelayConfig {

    @Bean
    public RequestInterceptor tokenRelayInterceptor() {
        return template -> {
            try {
                String token = StpUtil.getTokenValue();
                if (token != null && !token.isEmpty()) {
                    template.header("Authorization", token);
                }
            } catch (Exception ignored) {
                // MQ 消费线程等无 HTTP 请求上下文的场景：Sa-Token 会抛 SaTokenContextException。
                // 此时无登录态可透传，交由内部密钥路径（InternalTokenFeignConfig）处理。
                // 不兜住会污染秒杀链路。
            }
        };
    }
}
