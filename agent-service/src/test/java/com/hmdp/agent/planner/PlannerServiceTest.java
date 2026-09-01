package com.hmdp.agent.planner;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.metrics.TrackEventService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Planner 路由主流程单测（FR-03）：7 类分流 / 澄清与菜单 / 冲突 / 复合意图 / 解析失败降级
 */
class PlannerServiceTest {

    private final IntentClassifier classifier = mock(IntentClassifier.class);
    private final FlowStateService flowState = mock(FlowStateService.class);
    private final TrackEventService track = mock(TrackEventService.class);
    private final AgentProperties props = new AgentProperties();
    private final PlannerService planner = new PlannerService(classifier, flowState, track, props);

    private AgentSession session(String flowState) {
        return new AgentSession().setId(1L).setUserId(10L).setFlowState(flowState);
    }

    private ClassifyOutcome outcome(Intent intent, double confidence, List<String> subtasks) {
        return new ClassifyOutcome(
                new IntentResult(intent, Map.of(), confidence, subtasks), 100, 20);
    }

    @Test
    void classify_fail_degrades_to_menu() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(new ClassifyOutcome(null, 300, 60));
        PlanDecision d = planner.plan(session("IDLE"), "乱码消息", List.of());
        assertEquals(PlanDecision.PlanType.FALLBACK_MENU, d.type());
        verify(flowState).resetClarify(1L);
    }

    @Test
    void high_confidence_query_routes_to_react() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.ORDER_QUERY, 0.9, List.of()));
        PlanDecision d = planner.plan(session("IDLE"), "查订单", List.of());
        assertEquals(PlanDecision.PlanType.REACT, d.type());
        assertTrue(d.subtasks().isEmpty()); // 单任务：doChat 用原始消息
        verify(flowState).resetClarify(1L);
        verify(flowState, never()).incrClarify(any());
        // m5_intent 埋点（D1.8 #5）
        verify(track).track(eq("m5_intent"), eq(1L), eq(10L),
                argThat(p -> Boolean.FALSE.equals(p.get("isFallbackMenu"))));
    }

    @Test
    void composite_subtasks_preserved_in_order() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.ORDER_QUERY, 0.85, List.of("查询我的订单", "申请退款")));
        PlanDecision d = planner.plan(session("IDLE"), "查下我的单子，顺便把这个退了", List.of());
        assertEquals(PlanDecision.PlanType.REACT, d.type());
        assertEquals(List.of("查询我的订单", "申请退款"), d.subtasks());
    }

    @Test
    void low_confidence_clarifies_with_counter() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.VOUCHER_CONSULT, 0.4, List.of()));
        when(flowState.incrClarify(1L)).thenReturn(1);
        PlanDecision d = planner.plan(session("IDLE"), "那个东西呢", List.of());
        assertEquals(PlanDecision.PlanType.CLARIFY, d.type());
        assertNotNull(d.clarifyText());
        verify(track).track(eq("m5_intent"), any(), any(),
                argThat(p -> Integer.valueOf(1).equals(p.get("clarifyRound"))
                        && Boolean.FALSE.equals(p.get("isFallbackMenu"))));
    }

    @Test
    void third_clarify_round_degrades_to_menu() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.CHAT, 0.3, List.of()));
        when(flowState.incrClarify(1L)).thenReturn(3);
        PlanDecision d = planner.plan(session("IDLE"), "听不懂", List.of());
        assertEquals(PlanDecision.PlanType.FALLBACK_MENU, d.type());
        verify(flowState).resetClarify(1L);
        verify(track).track(eq("m5_intent"), any(), any(),
                argThat(p -> Boolean.TRUE.equals(p.get("isFallbackMenu"))));
    }

    @Test
    void refunding_state_with_new_intent_interrupts() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.SHOP_CONSULT, 0.9, List.of()));
        PlanDecision d = planner.plan(session("REFUNDING"), "XX店在哪", List.of());
        assertEquals(PlanDecision.PlanType.REACT, d.type());
        assertNotNull(d.interruptNotice());
        assertTrue(d.interruptNotice().contains("退款申请尚未提交"));
        verify(flowState).savePendingFlow(1L, "退款申请");
        verify(flowState).setFlowState(1L, "IDLE");
    }

    @Test
    void refunding_state_with_refund_intent_continues() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.REFUND, 0.9, List.of()));
        PlanDecision d = planner.plan(session("REFUNDING"), "我还是想退款", List.of());
        assertEquals(PlanDecision.PlanType.REFUND, d.type());
        assertNull(d.interruptNotice()); // 同流程不提示冲突
        verify(flowState, never()).savePendingFlow(any(), any());
    }

    @Test
    void refund_intent_sets_flow_state() {
        when(classifier.classify(any(), any(), any(), any()))
                .thenReturn(outcome(Intent.REFUND, 0.9, List.of()));
        PlanDecision d = planner.plan(session("IDLE"), "我要退款", List.of());
        assertEquals(PlanDecision.PlanType.REFUND, d.type());
        verify(flowState).setFlowState(1L, "REFUNDING");
        assertEquals(List.of("查询用户订单，定位可退款的订单"), d.subtasks());
    }

    @Test
    void chat_and_human_and_complaint_route() {
        when(classifier.classify(any(), any(), any(), any())).thenReturn(outcome(Intent.CHAT, 0.95, List.of()));
        assertEquals(PlanDecision.PlanType.CHAT_DIRECT, planner.plan(session("IDLE"), "你好", List.of()).type());

        when(classifier.classify(any(), any(), any(), any())).thenReturn(outcome(Intent.HUMAN_DEMAND, 0.95, List.of()));
        assertEquals(PlanDecision.PlanType.HUMAN_DEMAND, planner.plan(session("IDLE"), "转人工", List.of()).type());

        when(classifier.classify(any(), any(), any(), any())).thenReturn(outcome(Intent.COMPLAINT, 0.95, List.of()));
        assertEquals(PlanDecision.PlanType.COMPLAINT, planner.plan(session("IDLE"), "投诉", List.of()).type());
    }

    @Test
    void classify_tokens_carried_into_decision() {
        when(classifier.classify(any(), any(), any(), any())).thenReturn(outcome(Intent.CHAT, 0.95, List.of()));
        PlanDecision d = planner.plan(session("IDLE"), "你好", List.of());
        assertEquals(100, d.classifyPromptTokens());
        assertEquals(20, d.classifyCompletionTokens());
    }
}
