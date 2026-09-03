package com.hmdp.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.dto.ChatRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.mapper.AgentSessionMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.snapshot.SessionSnapshotService;
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
import java.util.List;
import java.util.Map;

/**
 * 会话生命周期管理（FR-01）
 * 创建/复用/关闭 + 日会话数频控 + 空闲自动关闭调度 + 90 天归档（FR-13 T5.3）
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
    private final SessionSnapshotService snapshotService;

    /**
     * 创建或复用会话
     * - 显式携带 resumeSessionId → 跳过复用新建会话并携带旧摘要（FR-13「基于此会话继续咨询」）
     * - 同用户已有 ACTIVE 会话 → 自动复用（FR-01 边界 3：复用未过期会话）
     * - 日会话数超限 → 拒绝（FR-11：单用户日会话数上限 20）
     */
    public AgentSession createOrReuse(UserDTO user, ChatRequest req) {
        AgentSession resumed = req.getResumeSessionId() == null ? null
                : getOwned(req.getResumeSessionId(), user.getId());

        // 日会话频控（新会话才计数）
        String dailyKey = "agent:user:daily:" + user.getId() + ":" + LocalDate.now();

        AgentSession existing = resumed == null ? findActive(user.getId()) : null;
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
                .setContextJson(resumed == null ? toJson(req.getContext())
                        : "{\"resumedFrom\":" + resumed.getId() + "}")
                .setSummary(resumed == null ? null : resumed.getSummary())
                .setFlowState("IDLE")
                .setMsgCount(0)
                .setTokenCost(java.math.BigDecimal.ZERO);
        save(session);

        // m5_session_start 埋点（服务端直写，D1.8）
        Map<String, Object> startProps = resumed == null
                ? Map.of("entry", session.getEntry())
                : Map.of("entry", session.getEntry(), "resumedFrom", resumed.getId());
        trackEventService.track("m5_session_start", session.getId(), user.getId(), startProps);
        return session;
    }

    /** 用户当前 ACTIVE 会话（无则 null）。DEF-A4 修复：controller 的复用提示以此为准，不再用 msgCount 推断 */
    public AgentSession findActive(Long userId) {
        return getOne(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getUserId, userId)
                .eq(AgentSession::getStatus, "ACTIVE")
                .orderByDesc(AgentSession::getCreateTime)
                .last("LIMIT 1"));
    }

    /** 归属校验（4.2：sessionId 与 userId 强绑定） */
    public AgentSession getOwned(Long sessionId, Long userId) {
        AgentSession session = getById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return session;
    }

    /** FR-13：近 N 天会话列表（「我的 → 客服记录」，含状态与评价标记） */
    public List<AgentSession> listRecent(Long userId, int days) {
        return list(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getUserId, userId)
                .ge(AgentSession::getCreateTime, LocalDateTime.now().minusDays(days))
                .orderByDesc(AgentSession::getCreateTime));
    }

    /** D9 工作台：转人工会话队列（TRANSFERRED 且移交包在 Redis 的会话，FR-14 流程 D 接管面板） */
    public List<AgentSession> listTransferQueue() {
        return list(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getStatus, "TRANSFERRED")
                .likeRight(AgentSession::getSnapshotUri, "redis://agent:transfer:"));
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
        boolean userClosed = "USER".equals(reason);
        session.setStatus("CLOSED").setCloseReason(reason);
        updateById(session);
        snapshotService.saveSnapshot(session); // FR-13：先固化回放快照再清理 Redis
        // m5_session_end（T5.5）：resolved 口径——用户主动关闭=推断解决；超时关闭=未确认解决
        trackEventService.track("m5_session_end", sessionId, session.getUserId(), Map.of(
                "closeReason", reason,
                "msgCount", session.getMsgCount() == null ? 0 : session.getMsgCount(),
                "durationMinutes", session.getCreateTime() == null ? 0
                        : Duration.between(session.getCreateTime(), LocalDateTime.now()).toMinutes(),
                "resolved", userClosed));
        // FR-12：会话结束推送评价卡片（一次性推送；无提醒调度，忽略即不再提醒）
        sseSessionManager.send(sessionId, "card", RatingService.ratingCard(sessionId));
        sseSessionManager.send(sessionId, "done",
                Map.of("finishReason", "SESSION_CLOSED"));
        memoryService.evict(sessionId);
        snapshotService.evictCards(sessionId);
        sseSessionManager.complete(sessionId);
    }

    /** FR-12：评价落库（每会话仅一次——rating is null 条件更新，重复评价返回 false） */
    public boolean rate(Long sessionId, Long userId, int score, String tagsJson) {
        return lambdaUpdate()
                .eq(AgentSession::getId, sessionId)
                .eq(AgentSession::getUserId, userId)
                .isNull(AgentSession::getRating)
                .set(AgentSession::getRating, score)
                .set(AgentSession::getRatingTags, tagsJson)
                .update();
    }

    /** FR-13：90 天归档（T5.3）——CLOSED/TRANSFERRED 且 90 天未更新 → ARCHIVED（数据保留在库，物理归档不在本阶段范围） */
    public boolean archiveExpiredSessions() {
        return lambdaUpdate()
                .in(AgentSession::getStatus, List.of("CLOSED", "TRANSFERRED"))
                .lt(AgentSession::getUpdateTime, LocalDateTime.now().minusDays(90))
                .set(AgentSession::getStatus, "ARCHIVED")
                .update();
    }

    /** 每日归档调度（幂等，启动即执行一次） */
    @Scheduled(fixedRate = 86400000)
    public void archiveDaily() {
        if (archiveExpiredSessions()) {
            log.info("90 天会话归档完成");
        }
    }

    /** 触发转人工状态锁（CAS：仅 ACTIVE→TRANSFERRED，防并发双触发；@return 是否本次胜出） */
    public boolean markTransferred(Long sessionId, String transferReason) {
        return lambdaUpdate()
                .eq(AgentSession::getId, sessionId)
                .eq(AgentSession::getStatus, "ACTIVE")
                .set(AgentSession::getStatus, "TRANSFERRED")
                .set(AgentSession::getTransferReason, transferReason)
                .update();
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

    /** T4.9：移交包快照引用（P2 工作台按此取 Redis 移交包） */
    public void updateSnapshotUri(Long sessionId, String snapshotUri) {
        lambdaUpdate().eq(AgentSession::getId, sessionId)
                .set(AgentSession::getSnapshotUri, snapshotUri)
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
