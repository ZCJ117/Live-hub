package com.hmdp.agent.flow;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private SseSessionManager sseManager;
    @Mock private RedissonClient redisson;
    @Mock private RBucket<String> draftBucket;

    private ComplaintFlowService service() {
        return new ComplaintFlowService(glmClient, ticketService, flowStateService,
                sessionService, trackEventService, sseManager, redisson, new AgentProperties());
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

    @Test
    void 首轮要素不全_追问不建单() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(
                resp("{\"demand\":\"态度差\"}"));
        service().handle(session(), "这家店态度太差");
        verify(ticketService, never()).create(any(), any(), any(TicketRequest.class));
        verify(sseManager).send(eq(1L), eq("delta"), any());
        verify(draftBucket).set(anyString(), any(Duration.class));
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

        service().handle(session(), "1号店店员今天态度很差，我要投诉要求道歉");

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        assertEquals("MERCHANT_SERVICE", captor.getValue().getCategory());
        verify(sseManager).send(eq(1L), eq("delta"),
                argThat(d -> String.valueOf(((Map<?, ?>) d).get("text")).contains("TK20260901000001")));
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

        service().handle(session(), "我的订单退款失败，一直不到账");

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

        service().handle(session(), "算了你看着办");

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

        service().handle(session(), "退款失败");

        ArgumentCaptor<TicketRequest> captor = ArgumentCaptor.forClass(TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        String summary = captor.getValue().getSummary();
        org.junit.jupiter.api.Assertions.assertTrue(summary != null && !summary.isBlank() && summary.contains("退款"));
    }

    @Test
    void 建单失败_重试一次后转人工话术_不静默丢失() {
        stubBucket();
        when(glmClient.complete(any(LlmTypes.Request.class)))
                .thenReturn(resp("{\"category\":\"ORDER\",\"demand\":\"退款失败\"}"))
                .thenReturn(resp("x"));
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down again"));

        service().handle(session(), "退款失败");

        verify(ticketService, times(2)).create(any(), any(), any());
        verify(sessionService).markTransferred(eq(1L), eq("TICKET_FAIL"));
        verify(sseManager).send(eq(1L), eq("delta"),
                argThat(d -> String.valueOf(((Map<?, ?>) d).get("text")).contains("人工")));
    }
}
