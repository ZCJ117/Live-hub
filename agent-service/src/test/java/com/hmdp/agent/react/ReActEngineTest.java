package com.hmdp.agent.react;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.config.GlmProperties;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.tool.ToolExecutor;
import com.hmdp.agent.tool.ToolRegistry;
import com.hmdp.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ReAct 引擎单测（T3.12/T3.13）：模型分流 / 步数预算 / 连续失败中断 / 幻觉嫌疑标记
 */
class ReActEngineTest {

    private final GlmClient glmClient = mock(GlmClient.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final TrackEventService track = mock(TrackEventService.class);
    private final AgentProperties props = new AgentProperties();
    private final GlmProperties glmProps = new GlmProperties();
    private final ReActEngine engine = new ReActEngine(glmClient, toolRegistry, toolExecutor, props, glmProps, track,
            new com.hmdp.agent.security.OutputFilter(
                    new com.hmdp.agent.security.SensitiveWordService(props, new org.springframework.mock.env.MockEnvironment()), props));

    private final ToolContext ctx = ToolContext.builder().sessionId(1L).userId(10L).build();

    /** 决策队列辅助：每个用例先 Decisions.set(...) 再 stubDecisions() */
    private void stubDecisions() {
        org.mockito.Mockito.when(glmClient.complete(any(LlmTypes.Request.class))).thenAnswer(inv ->
                LlmTypes.Response.builder().content(Decisions.pop()).build());
    }

    static class Decisions {
        private static Deque<String> queue = new ArrayDeque<>();

        static void set(String... items) {
            queue = new ArrayDeque<>(List.of(items));
        }

        static String pop() {
            return queue.isEmpty() ? "{\"action\":\"ANSWER\"}" : queue.poll();
        }
    }

    @Test
    void immediate_answer_uses_main_model_for_stream_and_light_for_decide() {
        Decisions.set("{\"action\":\"ANSWER\"}");
        stubDecisions();
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("您的订单已支付", 50, 30));

        ReActEngine.ReactResult r = engine.run(ctx, List.of(), null, "查订单",
                props.getReact().getMaxSteps(), s -> {}, null);

        assertEquals("您的订单已支付", r.answer());
        assertEquals(1, r.stepsUsed());
        assertEquals(50, r.promptTokens());
        // 决策步走 lightModel（T3.13）
        ArgumentCaptor<LlmTypes.Request> decideCaptor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient).complete(decideCaptor.capture());
        assertEquals(glmProps.getLightModel(), decideCaptor.getValue().getModel());
        // 最终回答走 mainModel（T3.13）
        ArgumentCaptor<LlmTypes.Request> answerCaptor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient).streamChat(answerCaptor.capture(), any());
        assertEquals(glmProps.getMainModel(), answerCaptor.getValue().getModel());
    }

    @Test
    void two_consecutive_tool_failures_interrupt_with_ticket_fallback() {
        Decisions.set(
                "{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}",
                "{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}");
        stubDecisions();
        org.mockito.Mockito.when(toolExecutor.execute(anyString(), any(), any(), any()))
                .thenReturn(ToolResult.fail("ORDER_TIMEOUT", "订单服务暂时繁忙"));
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("抱歉，查询遇到问题", 50, 30));

        ReActEngine.ReactResult r = engine.run(ctx, List.of(), null, "查订单",
                props.getReact().getMaxSteps(), s -> {}, null);

        assertTrue(r.needTicketFallback()); // FR-05：建议建单或转人工
        assertEquals("TOOL_CONSECUTIVE_FAIL", r.reason());
        assertEquals(2, r.stepsUsed());
    }

    @Test
    void max_steps_budget_respected() {
        Decisions.set(
                "{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}",
                "{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}",
                "{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}");
        stubDecisions();
        org.mockito.Mockito.when(toolExecutor.execute(anyString(), any(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).data(List.of()).summary("ok").build());
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("ok", 10, 10));

        ReActEngine.ReactResult r = engine.run(ctx, List.of(), null, "查订单",
                3, s -> {}, null); // 预算 3

        assertEquals(3, r.stepsUsed()); // 步数耗尽强制收敛
        assertEquals("MAX_STEPS", r.reason());
    }

    @Test
    void observations_injected_as_data_blocks() {
        Decisions.set("{\"action\":{\"tool\":\"query_my_orders\",\"args\":{}}}");
        stubDecisions();
        org.mockito.Mockito.when(toolExecutor.execute(anyString(), any(), any(), any())).thenReturn(
                ToolResult.builder().success(true).data(List.of(Map.of("orderId", 1L))).summary("1 条").build());
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("您的订单已支付", 10, 10));

        engine.run(ctx, List.of(), null, "查订单", props.getReact().getMaxSteps(), s -> {}, null);

        // Observation 以 [DATA] 数据格式注入（T3.12/R1 数据指令隔离）
        ArgumentCaptor<LlmTypes.Request> answerCaptor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient).streamChat(answerCaptor.capture(), any());
        assertTrue(answerCaptor.getValue().getMessages().get(0).getContent().contains("[DATA]"));
        // system prompt 硬约束（T3.12）
        assertTrue(answerCaptor.getValue().getMessages().get(0).getContent().contains("不得陈述事实"));
    }

    @Test
    void status_assertion_without_tool_data_tracked_as_suspect() {
        Decisions.set("{\"action\":\"ANSWER\"}");
        stubDecisions();
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("您的订单已取消，可以申请退款", 10, 10));

        engine.run(ctx, List.of(), null, "查订单", props.getReact().getMaxSteps(), s -> {}, null);

        // R1：无工具数据却含状态断言 → hallucination_suspect 标记（不拦截）
        verify(track).track(eq("m5_hallucination_suspect"), eq(1L), eq(10L), any());
    }

    @Test
    void chat_direct_uses_light_model() {
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenReturn(
                new GlmClient.StreamResult("您好，请问有什么可以帮您？", 10, 10));

        ReActEngine.ReactResult r = engine.chatDirect(List.of(), null, "你好", s -> {});

        assertEquals("您好，请问有什么可以帮您？", r.answer());
        ArgumentCaptor<LlmTypes.Request> captor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient).streamChat(captor.capture(), any());
        assertEquals(glmProps.getLightModel(), captor.getValue().getModel()); // T3.13：CHAT 走 light 档
    }

    /** 输出防护用例：自定义词表（默认词表不含测试词） */
    private ReActEngine guardEngine() throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSensitiveWords(List.of("违禁词"));
        com.hmdp.agent.security.SensitiveWordService sw = new com.hmdp.agent.security.SensitiveWordService(p, new org.springframework.mock.env.MockEnvironment());
        // rebuild() 为包私有，跨包测试用反射触发启动构建
        java.lang.reflect.Method rebuild = com.hmdp.agent.security.SensitiveWordService.class.getDeclaredMethod("rebuild");
        rebuild.setAccessible(true);
        rebuild.invoke(sw);
        return new ReActEngine(glmClient, toolRegistry, toolExecutor, p, glmProps, track,
                new com.hmdp.agent.security.OutputFilter(sw, p));
    }

    private void stubStream(java.util.Deque<String> contents) {
        org.mockito.Mockito.when(glmClient.streamChat(any(LlmTypes.Request.class), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> sink = inv.getArgument(1);
            String content = contents.poll();
            sink.accept(content);
            return new GlmClient.StreamResult(content, 20, 15);
        });
    }

    @Test
    void blocked_重生成一次成功() throws Exception {
        ReActEngine guard = guardEngine();
        stubStream(new java.util.ArrayDeque<>(List.of("回复含违禁词了", "这是干净的重生内容")));
        StringBuilder deltas = new StringBuilder();
        List<String> events = new java.util.ArrayList<>();
        ReActEngine.ReactResult r = guard.chatDirect(List.of(), null, "你好", deltas::append,
                (event, data) -> events.add(event));

        assertEquals("这是干净的重生内容", r.answer());
        assertFalse(r.outputBlocked());
        assertTrue(deltas.toString().contains("这是干净的重生内容"));
        assertTrue(events.contains("content_reset"), "content_reset 事件未发出: " + events);
        // token 记账 = 首次尝试 + 重试
        assertEquals(40, r.promptTokens());
        assertEquals(30, r.completionTokens());
    }

    @Test
    void blocked_重生成仍命中_兜底话术() throws Exception {
        ReActEngine guard = guardEngine();
        stubStream(new java.util.ArrayDeque<>(List.of("第一次含违禁词", "第二次还是违禁词")));
        StringBuilder deltas = new StringBuilder();
        List<String> events = new java.util.ArrayList<>();
        ReActEngine.ReactResult r = guard.chatDirect(List.of(), null, "你好", deltas::append,
                (event, data) -> events.add(event));

        assertEquals(ReActEngine.OUTPUT_BLOCKED_FALLBACK, r.answer());
        assertTrue(r.outputBlocked());
        assertTrue(deltas.toString().contains(ReActEngine.OUTPUT_BLOCKED_FALLBACK));
        assertTrue(events.contains("content_reset"), "content_reset 事件未发出: " + events);
        assertEquals("OUTPUT_BLOCKED", r.reason());
        assertEquals(40, r.promptTokens());
        assertEquals(30, r.completionTokens());
    }
}
