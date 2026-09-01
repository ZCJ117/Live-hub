package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * agent-service 配置（application.yaml agent.* / glm.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Sse sse = new Sse();
    private Session session = new Session();
    private React react = new React();
    private Planner planner = new Planner();
    private Message message = new Message();

    @Data
    public static class Sse {
        private int corePoolSize = 100;
        private int maxPoolSize = 200;
        private int queueCapacity = 200;
        /** SseEmitter 单轮超时 */
        private long emitterTimeoutMs = 60000;
        private long heartbeatIntervalMs = 15000;
    }

    @Data
    public static class Session {
        private int idleCloseMinutes = 30;
        private int memoryTtlMinutes = 30;
        private int maxHistoryRounds = 10;
        /** 摘要压缩失败降级保留轮数（PRD FR-02 边界） */
        private int degradedHistoryRounds = 6;
        private int maxDailySessions = 20;
        private int maxMsgCount = 100;
    }

    @Data
    public static class React {
        private int maxSteps = 8;
    }

    @Data
    public static class Planner {
        /** 澄清阈值（FR-03：confidence < 0.6 → 澄清） */
        private double clarifyThreshold = 0.6;
        /** 最多连续澄清轮数，第 3 轮降级菜单 */
        private int maxClarifyRounds = 2;
    }

    @Data
    public static class Message {
        private int maxLength = 500;
    }
}
