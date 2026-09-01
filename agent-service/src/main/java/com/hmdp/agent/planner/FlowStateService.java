package com.hmdp.agent.planner;

import com.hmdp.agent.service.AgentSessionService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 流程状态服务（D1.2 §4/§6）：flowState 持久化（agent_session.flow_state）
 * + Redis 澄清计数/冲突现场（agent:session:{id}:clarify / :pendingFlow）
 */
@Service
@RequiredArgsConstructor
public class FlowStateService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedissonClient redisson;
    private final AgentSessionService sessionService;

    private String clarifyKey(Long sessionId) {
        return "agent:session:" + sessionId + ":clarify";
    }

    private String pendingKey(Long sessionId) {
        return "agent:session:" + sessionId + ":pendingFlow";
    }

    /** 澄清计数 +1（T3.3），返回当前轮次 */
    public int incrClarify(Long sessionId) {
        RAtomicLong counter = redisson.getAtomicLong(clarifyKey(sessionId));
        long v = counter.incrementAndGet();
        counter.expire(TTL);
        return (int) v;
    }

    public int getClarify(Long sessionId) {
        return (int) Math.min(redisson.getAtomicLong(clarifyKey(sessionId)).get(), Integer.MAX_VALUE);
    }

    public void resetClarify(Long sessionId) {
        redisson.getAtomicLong(clarifyKey(sessionId)).delete();
    }

    /** 冲突现场保存（T3.5）：流程名。Phase 4 卡片机制续接"是否继续"询问 */
    public void savePendingFlow(Long sessionId, String flowName) {
        redisson.<String>getBucket(pendingKey(sessionId)).set(flowName, TTL);
    }

    public String popPendingFlow(Long sessionId) {
        RBucket<String> bucket = redisson.<String>getBucket(pendingKey(sessionId));
        String v = bucket.get();
        if (v != null) {
            bucket.delete();
        }
        return v;
    }

    /** flowState 持久化 */
    public void setFlowState(Long sessionId, String state) {
        sessionService.updateFlowState(sessionId, state);
    }

    /** 仅当当前为 REFUNDING 时复位 IDLE（confirm 长事务期间防覆盖 Planner 新流转） */
    public void resetRefundingIfNeeded(Long sessionId) {
        sessionService.casFlowState(sessionId, "REFUNDING", "IDLE");
    }
}
