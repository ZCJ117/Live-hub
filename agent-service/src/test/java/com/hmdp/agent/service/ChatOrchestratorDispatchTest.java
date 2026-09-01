package com.hmdp.agent.service;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.flow.RefundFlowService;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.Intent;
import com.hmdp.agent.planner.PlanDecision;
import com.hmdp.agent.planner.PlannerService;
import com.hmdp.agent.react.ReActEngine;
import com.hmdp.agent.sse.SseSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排器分发单测（FR-03 收口）：CLARIFY/REACT/REFUND/FALLBACK_MENU/HUMAN_DEMAND 分支 + token 记账
 */
class ChatOrchestratorDispatchTest {

    private final AgentSessionService sessionService = mock(AgentSessionService.class);
    private final ChatMemoryService memoryService = mock(ChatMemoryService.class);
    private final ReActEngine reActEngine = mock(ReActEngine.class);
    private final SseSessionManager sseManager = mock(SseSessionManager.class);
    private final TrackEventService track = mock(TrackEventService.class);
    private final GlmClient glmClient = mock(GlmClient.class);
    private final AgentProperties props = new AgentProperties();
    private final PlannerService planner = mock(PlannerService.class);
    private final RefundFlowService refundFlowService = mock(RefundFlowService.class);
    private final com.hmdp.agent.flow.ComplaintFlowService complaintFlowService =
            mock(com.hmdp.agent.flow.ComplaintFlowService.class);
    private final com.hmdp.agent.transfer.TransferService transferService =
            mock(com.hmdp.agent.transfer.TransferService.class);

    private ChatOrchestratorService service(Executor executor) {
        // 安全组件用真实实例（默认空敏感词表/规则不命中，不干扰分发用例）
        return new ChatOrchestratorService(sessionService, memoryService, reActEngine,
                sseManager, track, glmClient, props, planner,
                new com.hmdp.agent.security.InjectionDetector(),
                new com.hmdp.agent.security.SensitiveWordService(props),
                new com.hmdp.agent.security.EmotionDetector(props),
                mock(com.hmdp.agent.audit.ToolCallAuditService.class),
                refundFlowService,
                complaintFlowService,
                transferService,
                executor);
    }

    private AgentSession session() {
        return new AgentSession().setId(1L).setUserId(10L).setFlowState("IDLE").setMsgCount(1);
    }

    private void commonStubs() {
        when(memoryService.loadHistory(1L)).thenReturn(List.of());
        when(memoryService.getFocusOrder(1L)).thenReturn(null);
    }

    private PlanDecision decision(PlanDecision.PlanType type, Intent intent, List<String> subtasks) {
        return new PlanDecision(type, intent, 0.95, subtasks, null, null, 100, 20);
    }

    private ReActEngine.ReactResult reactResult(String answer) {
        return new ReActEngine.ReactResult(answer, false, null, 2, 50, 30);
    }

    @Test
    void clarify_decision_sends_text_without_react() {
        commonStubs();
        when(planner.plan(any(), any(), any()))
                .thenReturn(new PlanDecision(PlanDecision.PlanType.CLARIFY, Intent.CHAT, 0.4,
                        List.of(), "您是想查询订单还是咨询优惠券？", null, 100, 20));

        service(Runnable::run).handleChat(session(), "那个东西呢", "token");

        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
        verify(sseManager, atLeastOnce()).send(eq(1L), eq("delta"),
                argThat(d -> String.valueOf(((Map<?, ?>) d).get("text")).contains("您是想查询订单")));
        verify(sseManager).send(eq(1L), eq("done"), any());
    }

    @Test
    void react_decision_runs_with_full_budget_and_records_tokens() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REACT, Intent.ORDER_QUERY, List.of()));
        when(reActEngine.run(any(), any(), any(), any(), anyInt(), any(Consumer.class), any()))
                .thenReturn(reactResult("已为您查到 1 条订单"));

        service(Runnable::run).handleChat(session(), "查订单", "token");

        // 预算 = react.maxSteps(8)
        verify(reActEngine).run(any(), any(), any(), eq("查订单"), eq(8), any(Consumer.class), any());
        // token 记账（T3.13）：回答 50+30 + 分类 100+20
        verify(sessionService).addTokenCost(1L, 50, 150);
    }

    @Test
    void composite_subtasks_run_sequentially_with_shared_budget() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REACT, Intent.ORDER_QUERY,
                        List.of("查询我的订单", "申请退款")));
        when(reActEngine.run(any(), any(), any(), any(), anyInt(), any(Consumer.class), any()))
                .thenReturn(reactResult("子任务完成"));

        service(Runnable::run).handleChat(session(), "查单子顺便退款", "token");

        verify(reActEngine, times(2)).run(any(), any(), any(),
                argThat((String m) -> m.equals("查询我的订单") || m.equals("申请退款")),
                anyInt(), any(Consumer.class), any());
    }

    @Test
    void refund_decision_dispatches_to_refund_flow_without_react() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REFUND, Intent.REFUND,
                        List.of("查询用户订单，定位可退款的订单")));
        // 退款编排话术经 onDelta 统一流出（delta 事件 + answer 累积），勿手动 append answer
        doAnswer(inv -> {
            ((Consumer<String>) inv.getArgument(2)).accept("已为您生成退款申请（10 分钟内有效）。");
            return null;
        }).when(refundFlowService).handle(any(), any(), any());

        service(Runnable::run).handleChat(session(), "我要退款", "token");

        // T4.2：REFUND 不再走 ReAct + "即将开放"后缀，改为退款编排服务（话术经 onDelta）
        verify(refundFlowService).handle(any(), any(), any());
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
        verify(sseManager, never()).send(eq(1L), eq("delta"),
                argThat(d -> String.valueOf(((Map<?, ?>) d).get("text")).contains("退款申请尚未提交")));
    }

    @Test
    void refund_flow_result_is_accounted_without_answer_tokens() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REFUND, Intent.REFUND, List.of()));
        doAnswer(inv -> {
            ((Consumer<String>) inv.getArgument(2)).accept("已为您生成退款申请（10 分钟内有效）。");
            return null;
        }).when(refundFlowService).handle(any(), any(), any());

        service(Runnable::run).handleChat(session(), "我要退款", "token");

        // 分类 token 记账（回答/完成 token 为 0），记忆回写为真实话术而非 "REFUND_FLOW" 字面量
        verify(sessionService).addTokenCost(eq(1L), eq(0L), eq(120L));
        ArgumentCaptor<String> mem = ArgumentCaptor.forClass(String.class);
        verify(memoryService).append(eq(1L), eq("assistant"), mem.capture());
        assertTrue(mem.getValue().contains("退款申请"));
        // 防回归：话术只经 onDelta 累积一次，记忆中不得双写
        assertEquals(mem.getValue().indexOf("退款申请"), mem.getValue().lastIndexOf("退款申请"));
        assertFalse(mem.getValue().contains("REFUND_FLOW"));
        assertFalse(mem.getValue().contains("退款申请尚未提交"));
    }

    @Test
    void complaint_flow_speaks_through_on_delta_and_persists_real_prompt_to_memory() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.COMPLAINT, Intent.COMPLAINT, List.of()));
        doAnswer(inv -> {
            ((Consumer<String>) inv.getArgument(2)).accept("非常抱歉给您带来不便。为了准确登记工单，请补充：");
            return null;
        }).when(complaintFlowService).handle(any(), anyString(), any());

        service(Runnable::run).handleChat(session(), "我要投诉", "token");

        verify(complaintFlowService).handle(any(), eq("我要投诉"), any());
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
        ArgumentCaptor<String> mem = ArgumentCaptor.forClass(String.class);
        verify(memoryService).append(eq(1L), eq("assistant"), mem.capture());
        assertTrue(mem.getValue().contains("请补充"));
        // 防回归：话术只经 onDelta 累积一次，记忆中不得双写
        assertEquals(mem.getValue().indexOf("请补充"), mem.getValue().lastIndexOf("请补充"));
        assertFalse(mem.getValue().contains("COMPLAINT_FLOW"));
    }

    @Test
    void blank_answer_fallback_is_persisted_to_assistant_memory() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REACT, Intent.ORDER_QUERY, List.of()));
        when(reActEngine.run(any(), any(), any(), any(), anyInt(), any(Consumer.class), any()))
                .thenReturn(reactResult(" "));

        service(Runnable::run).handleChat(session(), "查订单", "token");

        ArgumentCaptor<String> mem = ArgumentCaptor.forClass(String.class);
        verify(memoryService).append(eq(1L), eq("assistant"), mem.capture());
        assertTrue(mem.getValue().contains("请描述您的问题"));
    }

    @Test
    void fallback_menu_pushes_card() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.FALLBACK_MENU, null, List.of()));

        service(Runnable::run).handleChat(session(), "???", "token");

        verify(sseManager).send(eq(1L), eq("card"),
                argThat(d -> "CLARIFY_MENU".equals(((Map<?, ?>) d).get("cardType"))));
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void human_demand_triggers_transfer_without_llm() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.HUMAN_DEMAND, Intent.HUMAN_DEMAND, List.of()));

        service(Runnable::run).handleChat(session(), "转人工", "token");

        // T4.8：转人工收口到 TransferService（埋点/卡片在 trigger 内），不再进 LLM
        verify(transferService).trigger(any(), eq("HUMAN_DEMAND"));
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
        verify(sseManager).send(eq(1L), eq("done"), any()); // done 恰好一次
    }

    @Test
    void chat_direct_uses_engine_chat_direct() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.CHAT_DIRECT, Intent.CHAT, List.of()));
        when(reActEngine.chatDirect(any(), any(), any(), any(Consumer.class), any()))
                .thenReturn(new ReActEngine.ReactResult("您好", false, null, 0, 10, 10));

        service(Runnable::run).handleChat(session(), "你好", "token");

        verify(reActEngine).chatDirect(any(), any(), eq("你好"), any(Consumer.class), any());
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
    }
}
