package com.hmdp.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆服务（FR-02）
 * Redis 存最近 N 轮历史 + 焦点对象，TTL 与会话生命周期绑定（30 分钟滑动续期）
 * 摘要压缩的编排由 ChatOrchestratorService 完成（异步，不阻塞对话）
 */
@Service
@Slf4j
public class ChatMemoryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String SSE_EXECUTOR_BEAN = "agentSseExecutor";

    private final RedissonClient redisson;
    private final AgentProperties props;

    public ChatMemoryService(RedissonClient redisson, AgentProperties props) {
        this.redisson = redisson;
        this.props = props;
    }

    private String historyKey(Long sessionId) {
        return "agent:session:" + sessionId + ":history";
    }

    private String focusKey(Long sessionId) {
        return "agent:session:" + sessionId + ":focus";
    }

    /** 卡片流水键（FR-13 回放快照：card 事件发出时由 SseSessionManager 写入） */
    public static String cardsKey(Long sessionId) {
        return "agent:session:" + sessionId + ":cards";
    }

    /** 记忆 TTL（分钟）——卡片流水等会话级键共用同一生命周期 */
    public long ttlMinutes() {
        return props.getSession().getMemoryTtlMinutes();
    }

    private long ttlSeconds() {
        return props.getSession().getMemoryTtlMinutes() * 60L;
    }

    /** 追加一条消息（role: user/assistant），滑动续期 */
    public void append(Long sessionId, String role, String content) {
        RList<String> list = redisson.getList(historyKey(sessionId));
        list.add(encode(role, content));
        list.expire(Duration.ofSeconds(ttlSeconds()));
    }

    /** 读取全部短期历史（旧→新） */
    public List<LlmTypesMsg> loadHistory(Long sessionId) {
        RList<String> list = redisson.getList(historyKey(sessionId));
        List<LlmTypesMsg> messages = new ArrayList<>();
        for (String json : list.readAll()) {
            try {
                HistoryEntry e = MAPPER.readValue(json, HistoryEntry.class);
                messages.add(new LlmTypesMsg(e.role(), e.content()));
            } catch (Exception ex) {
                log.warn("历史消息解析失败（跳过）: sessionId={}", sessionId);
            }
        }
        return messages;
    }

    /** 历史条目数（user + assistant 合计） */
    public int size(Long sessionId) {
        return redisson.getList(historyKey(sessionId)).size();
    }

    /** 原始历史 JSON（摘要压缩输入） */
    public String readRawJson(Long sessionId) {
        RList<String> list = redisson.getList(historyKey(sessionId));
        return String.join("\n", list.readAll());
    }

    /** 历史 JSON 数组（DEF-D8 修复：移交包消费端需要真正的 JSON 数组，readRawJson 的 JSONL 拼接会被 readTree 截断为首条） */
    public com.fasterxml.jackson.databind.JsonNode readHistoryArray(Long sessionId) {
        RList<String> list = redisson.getList(historyKey(sessionId));
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (String json : list.readAll()) {
            try {
                arr.add(MAPPER.readTree(json));
            } catch (Exception e) {
                log.warn("历史消息解析失败（跳过）: sessionId={}", sessionId);
            }
        }
        return arr;
    }

    /** 裁剪历史：仅保留最近 keep 条（摘要生效后 / 压缩失败降级） */
    public void trimKeepLast(Long sessionId, int keep) {
        RList<String> list = redisson.getList(historyKey(sessionId));
        int size = list.size();
        for (int i = 0; i < size - keep; i++) {
            list.remove(0);
        }
        list.expire(Duration.ofSeconds(ttlSeconds()));
    }

    /** 会话焦点对象（用户点选订单后聚焦，FR-05 交互 4） */
    public void setFocusOrder(Long sessionId, Long orderId) {
        if (orderId != null) {
            redisson.<String>getBucket(focusKey(sessionId)).set(String.valueOf(orderId),
                    Duration.ofSeconds(ttlSeconds()));
        }
    }

    public Long getFocusOrder(Long sessionId) {
        String v = redisson.<String>getBucket(focusKey(sessionId)).get();
        try {
            return v == null ? null : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 会话关闭后清理 Redis 键（FR-02 验收 3：无内存泄漏） */
    public void evict(Long sessionId) {
        redisson.getKeys().delete(historyKey(sessionId), focusKey(sessionId));
    }

    private String encode(String role, String content) {
        try {
            return MAPPER.writeValueAsString(new HistoryEntry(role, content));
        } catch (Exception e) {
            throw new IllegalStateException("历史消息序列化失败", e);
        }
    }

    public record HistoryEntry(String role, String content) {
    }

    /** 简化消息结构（role/content），供组装 LLM 请求 */
    public record LlmTypesMsg(String role, String content) {
    }
}
