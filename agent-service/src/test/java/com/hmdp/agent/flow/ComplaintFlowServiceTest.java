package com.hmdp.agent.flow;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.config.GlmProperties;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.6：要素收集状态机——首轮追问 / 二轮追问 / 超限定稿 OTHER / 涉资金 HIGH / 摘要模板兜底 / 建单失败转人工 */
@ExtendWith(MockitoExtension.class)
class ComplaintFlowServiceTest {

    @Mock private GlmClient glmClient;
    @Mock private TicketService ticketService;
    @Mock private FlowStateService flowStateService;
    @Mock private AgentSessionService sessionService;
    @Mock private TrackEventService trackEventService;
    @Mock private RedissonClient redisson;
    @Mock private RBucket<String> draftBucket;

    private ComplaintFlowService service() {
        return new ComplaintFlowService(glmClient, ticketService, flowStateService,
                sessionService, trackEventService, redisson,
                new AgentProperties(), new GlmProperties(),
                new TicketPriorityRules(new AgentProperties()));
    }

    private AgentSession session() {
        return new AgentSession().setId(1L).setUserId(100L).setStatus("ACTIVE").setFlowState("COMPLAINING");
    }

    private void stubBucket() {
        lenient().when(redisson.<String>getBucket(anyString())).thenReturn(draftBucket);
        lenient().when(draftBucket.get()).thenReturn(null);
    }

    private LlmTypes.Response resp(String content) {
        return LlmTypes.Response.builder().content(content).promptTokens(10L).completionTokens(10L).build();
    }

    /** 话术经 onDelta 统一流出（answer 累积由 orchestrator 的 onDelta 负责，勿手动 append） */
    private record Sink(List<String> deltas) {
        static Sink create() {
            return new Sink(new ArrayList<>());
        }

        Consumer<String> onDelta() {
            return deltas::add;
        }

        String text() {
            return String.join("", deltas);
        }
    }

    @Test
    void 首轮要素不全_追问不建单() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(
                resp("{\"demand\":\"态度差\"}"));
        Sink sink = Sink.create();
        service().handle(session(), "这家店态度太差", sink.onDelta());
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        assertEquals(1, sink.deltas().size());
        assertTrue(sink.text().contains("请补充"));
        // 防回归：话术只经 onDelta 流出一次，answer 中不得双写
        assertEquals(sink.text().indexOf("请补充"), sink.text().lastIndexOf("请补充"));
        verify(draftBucket).set(anyString(), any(Duration.class));
    }

    @Test
    void 泛泛投诉_demand被原文自填_仍追问不建单() {
        // DEF-B2：LLM 把"我要投诉"原文填进 demand 时，category 为 null → demand 视为未收集，继续追问（PRD 3.9 边界1）
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(
                resp("{\"demand\":\"我要投诉\"}"));
        Sink sink = Sink.create();
        service().handle(session(), "我要投诉", sink.onDelta());
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        assertTrue(sink.text().contains("请补充"));
    }

    @Test
    void dedup命中_提示已有工单不新建() {
        // DEF-B3d：去重命中话术区分"已有工单在处理中"，不误报"已为您登记新工单"
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(
                resp("{\"category\":\"ORDER\",\"refs\":{\"orderId\":9003},\"demand\":\"要求补发\"}"));
        when(ticketService.findExistingByDedup(any(), any())).thenReturn(
                java.util.Optional.of(new AgentTicket().setTicketNo("TK20260903000011")));
        Sink sink = Sink.create();
        service().handle(session(), "投诉订单9003漏发货要求补发", sink.onDelta());
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        assertTrue(sink.text().contains("已有工单 TK20260903000011"));
    }

    @Test
    void 要素齐_建单_返回工单号与SLA() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"category\":\"MERCHANT_SERVICE\",\"refs\":{\"shopId\":1},\"time\":\"今天\",\"demand\":\"店员态度差，需要道歉\"}"))
                .thenReturn(resp("用户反馈商户服务问题。")); // 摘要
        AgentTicket ticket = new AgentTicket().setTicketNo("TK20260901000001").setCategory("MERCHANT_SERVICE")
                .setPriority("MEDIUM").setExpectedSla("24h");
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class))).thenReturn(ticket);

        Sink sink = Sink.create();
        service().handle(session(), "1号店店员今天态度很差，我要投诉要求道歉", sink.onDelta());

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        assertEquals("MERCHANT_SERVICE", captor.getValue().getCategory());
        assertTrue(sink.text().contains("TK20260901000001"));
        verify(flowStateService).setFlowState(1L, "IDLE");
    }

    @Test
    void 诉求涉资金_priority_HIGH() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"category\":\"ORDER\",\"demand\":\"退款失败一直不到账\"}"))
                .thenReturn(resp("x"));
        AgentTicket ticket = new AgentTicket().setTicketNo("TK1").setCategory("ORDER").setPriority("HIGH").setExpectedSla("4h");
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class))).thenReturn(ticket);

        Sink sink = Sink.create();
        service().handle(session(), "我的订单退款失败，一直不到账", sink.onDelta());

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        assertEquals("HIGH", captor.getValue().getPriority());
    }

    @Test
    void 超过追问轮数_按OTHER定稿() {
        stubBucket();
        ComplaintDraft old = new ComplaintDraft();
        old.setRounds(2);
        old.setDemand("说不清楚");
        when(draftBucket.get()).thenReturn(ComplaintFlowService.toJson(old));
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"demand\":\"还是说不清\"}"))
                .thenReturn(resp("x"));
        AgentTicket ticket = new AgentTicket().setTicketNo("TK2").setCategory("OTHER").setPriority("MEDIUM").setExpectedSla("72h");
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class))).thenReturn(ticket);

        Sink sink = Sink.create();
        service().handle(session(), "算了你看着办", sink.onDelta());

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        assertEquals("OTHER", captor.getValue().getCategory());
    }

    @Test
    void 摘要LLM失败_模板兜底() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"category\":\"ORDER\",\"demand\":\"退款失败\"}"))
                .thenThrow(new LlmTypes.LlmException("LLM 挂了"));
        AgentTicket ticket = new AgentTicket().setTicketNo("TK3").setCategory("ORDER").setPriority("HIGH").setExpectedSla("4h");
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class))).thenReturn(ticket);

        Sink sink = Sink.create();
        service().handle(session(), "退款失败", sink.onDelta());

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        String summary = captor.getValue().getSummary();
        assertTrue(summary != null && !summary.isBlank() && summary.contains("退款"));
    }

    @Test
    void 建单失败_重试一次后转人工话术_清理草稿与状态() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"category\":\"ORDER\",\"demand\":\"退款失败\"}"))
                .thenReturn(resp("x"));
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down again"));

        Sink sink = Sink.create();
        service().handle(session(), "退款失败", sink.onDelta());

        verify(ticketService, times(2)).create(any(), any(), any());
        verify(sessionService).markTransferred(eq(1L), eq("TICKET_FAIL"));
        assertTrue(sink.text().contains("人工"));
        // 建单失败残留清理：flowState 复位 + 本服务创建的草稿自删
        verify(flowStateService).setFlowState(1L, "IDLE");
        verify(draftBucket).delete();
    }

    @Test
    void 草稿JSON损坏_重新收集() {
        stubBucket();
        when(draftBucket.get()).thenReturn("not-json");
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(
                resp("{\"demand\":\"态度差\"}"));
        Sink sink = Sink.create();
        service().handle(session(), "这家店态度太差", sink.onDelta());

        // 行为等同新草稿：首轮追问（要素仍不全），不建单
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        assertTrue(sink.text().contains("请补充"));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(draftBucket).set(saved.capture(), any(Duration.class));
        assertTrue(saved.getValue().contains("\"rounds\":1"));
    }

    @Test
    void 抽取连续失败_按空要素追问() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenThrow(new LlmTypes.LlmException("e1"))
                .thenThrow(new LlmTypes.LlmException("e2"))
                .thenThrow(new LlmTypes.LlmException("e3"));
        Sink sink = Sink.create();
        service().handle(session(), "哼", sink.onDelta());

        // 连续 3 次失败 → 空要素 → 首轮追问话术，不建单
        verify(glmClient, times(3)).complete(any(LlmTypes.Request.class));
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        assertTrue(sink.text().contains("请补充"));
        assertTrue(sink.text().contains("诉求"));
    }
}
