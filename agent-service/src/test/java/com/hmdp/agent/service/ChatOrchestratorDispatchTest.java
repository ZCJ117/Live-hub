package com.hmdp.agent.service;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    private ChatOrchestratorService service(Executor executor) {
        return new ChatOrchestratorService(sessionService, memoryService, reActEngine,
                sseManager, track, glmClient, props, planner, executor);
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
    void refund_decision_appends_pending_notice() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.REFUND, Intent.REFUND,
                        List.of("查询用户订单，定位可退款的订单")));
        when(reActEngine.run(any(), any(), any(), any(), anyInt(), any(Consumer.class), any()))
                .thenReturn(reactResult("已定位您的订单"));

        service(Runnable::run).handleChat(session(), "我要退款", "token");

        ArgumentCaptor<Object> texts = ArgumentCaptor.forClass(Object.class);
        verify(sseManager, atLeastOnce()).send(eq(1L), eq("delta"), texts.capture());
        boolean hasNotice = texts.getAllValues().stream()
                .anyMatch(d -> String.valueOf(((Map<?, ?>) d).get("text")).contains("退款申请尚未提交"));
        assertTrue(hasNotice); // T3.5 提示框架
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
    void human_demand_tracks_transfer_and_replies() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.HUMAN_DEMAND, Intent.HUMAN_DEMAND, List.of()));

        service(Runnable::run).handleChat(session(), "转人工", "token");

        verify(track).track(eq("m5_transfer_human"), eq(1L), eq(10L),
                argThat(p -> "HUMAN_DEMAND".equals(p.get("transferReason"))));
    }

    @Test
    void chat_direct_uses_engine_chat_direct() {
        commonStubs();
        when(planner.plan(any(), any(), any())).thenReturn(
                decision(PlanDecision.PlanType.CHAT_DIRECT, Intent.CHAT, List.of()));
        when(reActEngine.chatDirect(any(), any(), any(), any(Consumer.class)))
                .thenReturn(new ReActEngine.ReactResult("您好", false, null, 0, 10, 10));

        service(Runnable::run).handleChat(session(), "你好", "token");

        verify(reActEngine).chatDirect(any(), any(), eq("你好"), any(Consumer.class));
        verify(reActEngine, never()).run(any(), any(), any(), any(), anyInt(), any(), any());
    }
}
