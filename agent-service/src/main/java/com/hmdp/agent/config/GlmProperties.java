package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * GLM LLM 配置（OpenAI 协议兼容，PRD 1.5：不自研模型，保持可替换）
 */
@Data
@Component
@ConfigurationProperties(prefix = "glm")
public class GlmProperties {

    private String apiKey;
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    /** 主模型：查询类回答生成 */
    private String mainModel = "glm-4-flash";
    /** 轻量模型：CHAT 分流 / 摘要（控成本 R5） */
    private String lightModel = "glm-4-flash";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(120);
}
