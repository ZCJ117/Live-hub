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
    private Security security = new Security();
    private Transfer transfer = new Transfer();
    private Ticket ticket = new Ticket();

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

    @Data
    public static class Security {
        /** 敏感词表（初始兜底值，Nacos agent-service.yaml 可覆盖热更新，D-3） */
        private java.util.List<String> sensitiveWords = java.util.List.of(
                "枪支", "毒品", "赌博网站", "色情", "洗钱", "代开发票");
        /** 情绪检测：强负面词命中数 ≥ 该值才触发转人工（R8 高置信） */
        private int emotionMinHits = 3;
        /** 输出过滤流式尾部长度下限（≥ 最长敏感词，T4.12） */
        private int outputFilterTailHold = 32;
    }

    @Data
    public static class Transfer {
        /** 是否有坐席在线（D-5：本阶段固定 false，仅无人值守路径；P2 工作台接入后开启） */
        private boolean seatOnline = false;
        /** 移交包 Redis TTL（天） */
        private int handoverTtlDays = 7;
    }

    @Data
    public static class Ticket {
        /** 涉资金关键词（命中 → priority=HIGH，FR-09 验收 6） */
        private java.util.List<String> fundKeywords = java.util.List.of(
                "退款失败", "重复扣款", "多扣", "少扣", "扣款", "退款未到账", "资金");
        /** 投诉要素收集最多追问轮数（PRD FR-09：最多 2 轮） */
        private int maxCollectRounds = 2;
    }
}
