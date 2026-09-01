package com.hmdp.agent.sse;

import com.hmdp.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接管理器
 * - 会话 emitter 注册/移除
 * - 统一 safeSend（推送失败即断连清理，避免泄漏）
 * - 心跳（15s ping，防网关空闲断连）
 */
@Component
@Slf4j
public class SseSessionManager {

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AgentProperties props;

    public SseSessionManager(AgentProperties props) {
        this.props = props;
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
        } catch (Exception e) {
            log.warn("SSE 推送失败，清理连接: sessionId={}, event={}", sessionId, event);
            emitters.remove(sessionId);
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
