package com.hmdp.agent.planner;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Planner：意图识别与路由（FR-03，D1.2 §4）
 * 冲突检查 → 意图分类 → 澄清/菜单 → 7 类分流
 * m5_intent 埋点：intent / confidence / clarifyRound / isFallbackMenu（D1.8 #5）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlannerService {

    private final IntentClassifier classifier;
    private final FlowStateService flowStateService;
    private final TrackEventService trackEventService;
    private final AgentProperties props;

    public PlanDecision plan(AgentSession session, String message,
                             List<ChatMemoryService.LlmTypesMsg> history) {
        Long sessionId = session.getId();
        String flowState = session.getFlowState() == null ? "IDLE" : session.getFlowState();

        ClassifyOutcome outcome = classifier.classify(message, history, sessionId, session.getUserId());

        // R2：3 次尝试均失败 → 降级菜单（验收 13）
        if (outcome.failed()) {
            log.warn("意图分类降级菜单: sessionId={}", sessionId);
            flowStateService.resetClarify(sessionId);
            return PlanDecision.menu(null, 0, outcome);
        }
        IntentResult result = outcome.result();

        // T3.5 上下文冲突：REFUNDING 流程中来了非退款意图 → 中断 + 提示 + 现场保存
        if ("REFUNDING".equals(flowState) && result.intent() != Intent.REFUND) {
            flowStateService.savePendingFlow(sessionId, "退款申请");
            flowStateService.setFlowState(sessionId, "IDLE");
            PlanDecision routed = route(session, result, outcome);
            log.info("上下文冲突中断: sessionId={}, flowState={}, newIntent={}",
                    sessionId, flowState, result.intent());
            return new PlanDecision(routed.type(), routed.intent(), routed.confidence(), routed.subtasks(),
                    routed.clarifyText(), "您的退款申请尚未提交，已为您先回答当前问题。",
                    outcome.promptTokens(), outcome.completionTokens());
        }

        // T3.3 低置信度：澄清 ≤2 轮，第 3 轮降级菜单
        if (result.confidence() < props.getPlanner().getClarifyThreshold()) {
            int round = flowStateService.incrClarify(sessionId);
            boolean isMenu = round > props.getPlanner().getMaxClarifyRounds();
            trackEventService.track("m5_intent", sessionId, session.getUserId(), Map.of(
                    "intent", result.intent().name(), "confidence", result.confidence(),
                    "clarifyRound", round, "isFallbackMenu", isMenu));
            if (isMenu) {
                flowStateService.resetClarify(sessionId);
                return PlanDecision.menu(result.intent(), result.confidence(), outcome);
            }
            return new PlanDecision(PlanDecision.PlanType.CLARIFY, result.intent(), result.confidence(),
                    List.of(), clarifyText(result.intent()), null,
                    outcome.promptTokens(), outcome.completionTokens());
        }

        // 正常分流
        flowStateService.resetClarify(sessionId);
        trackEventService.track("m5_intent", sessionId, session.getUserId(), Map.of(
                "intent", result.intent().name(), "confidence", result.confidence(),
                "clarifyRound", 0, "isFallbackMenu", false));
        return route(session, result, outcome);
    }

    private PlanDecision route(AgentSession session, IntentResult result, ClassifyOutcome outcome) {
        return switch (result.intent()) {
            case ORDER_QUERY, VOUCHER_CONSULT, SHOP_CONSULT ->
                    new PlanDecision(PlanDecision.PlanType.REACT, result.intent(), result.confidence(),
                            result.subtasks(), null, null,
                            outcome.promptTokens(), outcome.completionTokens());
            case CHAT -> new PlanDecision(PlanDecision.PlanType.CHAT_DIRECT, result.intent(),
                    result.confidence(), List.of(), null, null,
                    outcome.promptTokens(), outcome.completionTokens());
            case REFUND -> {
                // T3.1/T3.5：退款流程框架（查证订单）；确认卡片与提交 Phase 4 接入
                flowStateService.setFlowState(session.getId(), "REFUNDING");
                yield new PlanDecision(PlanDecision.PlanType.REFUND, result.intent(), result.confidence(),
                        List.of("查询用户订单，定位可退款的订单"), null, null,
                        outcome.promptTokens(), outcome.completionTokens());
            }
            case COMPLAINT -> new PlanDecision(PlanDecision.PlanType.COMPLAINT, result.intent(),
                    result.confidence(), List.of(), null, null,
                    outcome.promptTokens(), outcome.completionTokens());
            case HUMAN_DEMAND -> new PlanDecision(PlanDecision.PlanType.HUMAN_DEMAND, result.intent(),
                    result.confidence(), List.of(), null, null,
                    outcome.promptTokens(), outcome.completionTokens());
        };
    }

    private String clarifyText(Intent intent) {
        return switch (intent) {
            case ORDER_QUERY -> "您是想查询订单状态，还是咨询订单相关的问题？可以补充订单号或描述更具体一些。";
            case VOUCHER_CONSULT -> "您是想查询优惠券的使用规则，还是查询券订单？";
            case SHOP_CONSULT -> "您想咨询哪家商户呢？可以告诉我店名。";
            default -> "抱歉，我没完全理解您的意思。您是想：查订单、查优惠券、退款、投诉，还是转人工？";
        };
    }
}
