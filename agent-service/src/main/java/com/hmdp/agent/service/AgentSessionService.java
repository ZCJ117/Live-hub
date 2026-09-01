package com.hmdp.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.dto.ChatRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.mapper.AgentSessionMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.sse.SseSessionManager;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 会话生命周期管理（FR-01）
 * 创建/复用/关闭 + 日会话数频控 + 空闲自动关闭调度
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentSessionService extends ServiceImpl<AgentSessionMapper, AgentSession> {

    private final AgentProperties props;
    private final RedissonClient redisson;
    private final ChatMemoryService memoryService;
    private final SseSessionManager sseSessionManager;
    private final TrackEventService trackEventService;

    /**
     * 创建或复用会话
     * - 同用户已有 ACTIVE 会话 → 自动复用（FR-01 边界 3：复用未过期会话）
     * - 日会话数超限 → 拒绝（FR-11：单用户日会话数上限 20）
     */
    public AgentSession createOrReuse(UserDTO user, ChatRequest req) {
        // 日会话频控（新会话才计数）
        String dailyKey = "agent:user:daily:" + user.getId() + ":" + LocalDate.now();

        AgentSession existing = getOne(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getUserId, user.getId())
                .eq(AgentSession::getStatus, "ACTIVE")
                .orderByDesc(AgentSession::getCreateTime)
                .last("LIMIT 1"));
        if (existing != null) {
            // 复用未过期会话，不重复计数
            touch(existing);
            return existing;
        }

        RAtomicLong counter = redisson.getAtomicLong(dailyKey);
        long count = counter.incrementAndGet();
        counter.expire(Duration.ofHours(25));
        if (count > props.getSession().getMaxDailySessions()) {
            throw new BusinessException("今日咨询次数已达上限（20次），请明日再试");
        }

        AgentSession session = new AgentSession()
                .setUserId(user.getId())
                .setModule("M5")
                .setStatus("ACTIVE")
                .setEntry(entryOf(req))
                .setContextJson(toJson(req.getContext()))
                .setFlowState("IDLE")
                .setMsgCount(0)
                .setTokenCost(java.math.BigDecimal.ZERO);
        save(session);

        // m5_session_start 埋点（服务端直写，D1.8）
        trackEventService.track("m5_session_start", session.getId(), user.getId(),
                Map.of("entry", session.getEntry()));
        return session;
    }

    /** 归属校验（4.2：sessionId 与 userId 强绑定） */
    public AgentSession getOwned(Long sessionId, Long userId) {
        AgentSession session = getById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return session;
    }

    /** 消息计数（单会话上限 100，FR-11） */
    public void checkAndIncrMsg(AgentSession session) {
        if (session.getMsgCount() >= props.getSession().getMaxMsgCount()) {
            throw new BusinessException("本会话消息数已达上限，请结束会话后重新咨询");
        }
        session.setMsgCount(session.getMsgCount() + 1);
        touch(session);
    }

    public void close(Long sessionId, String reason) {
        AgentSession session = getById(sessionId);
        if (session == null || !"ACTIVE".equals(session.getStatus())) {
            return;
        }
        session.setStatus("CLOSED").setCloseReason(reason);
        updateById(session);
        memoryService.evict(sessionId);
        sseSessionManager.send(sessionId, "done",
                Map.of("finishReason", "SESSION_CLOSED"));
        sseSessionManager.complete(sessionId);
    }

    /** 触发转人工状态锁（Phase 4 使用，本阶段预留状态流转） */
    public void markTransferred(Long sessionId, String transferReason) {
        AgentSession session = getById(sessionId);
        if (session == null) return;
        session.setStatus("TRANSFERRED").setTransferReason(transferReason);
        updateById(session);
    }

    public void updateSummary(Long sessionId, String summary) {
        if (summary == null || summary.isBlank()) return;
        lambdaUpdate()
                .eq(AgentSession::getId, sessionId)
                .set(AgentSession::getSummary, summary)
                .update();
    }

    /** Phase 3：flowState 流转（Planner 状态机，T3.3/T3.5） */
    public void updateFlowState(Long sessionId, String flowState) {
        lambdaUpdate().eq(AgentSession::getId, sessionId)
                .set(AgentSession::getFlowState, flowState)
                .update();
    }

    /** 条件流转：仅当 flowState=expect 时置为 target（防并发覆盖） */
    public void casFlowState(Long sessionId, String expect, String target) {
        lambdaUpdate().eq(AgentSession::getId, sessionId)
                .eq(AgentSession::getFlowState, expect)
                .set(AgentSession::getFlowState, target)
                .update();
    }

    /** Phase 3：会话 token 成本累加（T3.13/R5），单位=token 数 */
    public void addTokenCost(Long sessionId, long promptTokens, long completionTokens) {
        if (promptTokens + completionTokens <= 0) {
            return;
        }
        lambdaUpdate().eq(AgentSession::getId, sessionId)
                .setSql("token_cost = IFNULL(token_cost, 0) + " + (promptTokens + completionTokens))
                .update();
    }

    /** 空闲会话自动关闭（FR-01 边界 4：30 分钟置 CLOSED 并推结束语） */
    @Scheduled(fixedRate = 60000)
    public void closeIdleSessions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(props.getSession().getIdleCloseMinutes());
        list(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getStatus, "ACTIVE")
                .lt(AgentSession::getUpdateTime, threshold)
                .last("LIMIT 100"))
                .forEach(s -> {
                    log.info("空闲会话自动关闭: sessionId={}", s.getId());
                    close(s.getId(), "TIMEOUT");
                });
    }

    private void touch(AgentSession session) {
        session.setUpdateTime(LocalDateTime.now());
        updateById(session);
    }

    private String entryOf(ChatRequest req) {
        return req.getEntry() == null ? "my" : req.getEntry();
    }

    private String toJson(ChatRequest.ContextPayload ctx) {
        if (ctx == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ctx);
        } catch (Exception e) {
            return null;
        }
    }
}
