package com.hmdp.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "glm")
public class GlmApiConfig {
    private String apiKey;
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    private String llmModel = "glm-4-flash";
    private String embeddingModel = "embedding-2";
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration readTimeout = Duration.ofSeconds(120);
    private int maxRetries = 3;
}
