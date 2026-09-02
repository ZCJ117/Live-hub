package com.hmdp.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.memory.ChatMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接管理器
 * - 会话 emitter 注册/移除
 * - 统一 safeSend（推送失败即断连清理，避免泄漏）
 * - 心跳（15s ping，防网关空闲断连）
 * - card 事件流水记录（FR-13 回放快照，T5.3：集中拦截覆盖全部卡片发射点）
 */
@Component
@Slf4j
public class SseSessionManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AgentProperties props;
    private final RedissonClient redisson;

    public SseSessionManager(AgentProperties props, RedissonClient redisson) {
        this.props = props;
        this.redisson = redisson;
    }

    public SseEmitter createEmitter(Long sessionId) {
        SseEmitter emitter = new SseEmitter(props.getSse().getEmitterTimeoutMs());
        SseEmitter old = emitters.put(sessionId, emitter);
        if (old != null) {
            // 同会话重连：旧连接静默关闭（断线重连场景，FR-01 边界 3）
            try {
                old.complete();
            } catch (Exception ignored) {
            }
        }
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(e -> emitters.remove(sessionId));
        return emitter;
    }

    /** 线程安全推送；失败不抛出（推送失败不阻断业务流程，仅清理连接） */
    public void send(Long sessionId, String event, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            if ("card".equals(event)) {
                recordCard(sessionId, data); // 推送成功才入回放流水（与用户所见一致）
            }
        } catch (Exception e) {
            log.warn("SSE 推送失败，清理连接: sessionId={}, event={}", sessionId, event);
            emitters.remove(sessionId);
        }
    }

    /** 卡片流水入 Redis（FR-13 回放；记录失败仅告警，不影响推送语义） */
    private void recordCard(Long sessionId, Object data) {
        try {
            RList<String> list = redisson.getList(ChatMemoryService.cardsKey(sessionId));
            list.add(MAPPER.writeValueAsString(data));
            list.expire(Duration.ofMinutes(props.getSession().getMemoryTtlMinutes()));
        } catch (Exception e) {
            log.warn("卡片流水记录失败: sessionId={}", sessionId);
        }
    }

    public void complete(Long sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isOnline(Long sessionId) {
        return emitters.containsKey(sessionId);
    }

    public int onlineCount() {
        return emitters.size();
    }

    /** 心跳：注释行 ping（前端无需处理） */
    @Scheduled(fixedRateString = "${agent.sse.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        emitters.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                emitters.remove(sessionId);
            }
        });
    }
}
