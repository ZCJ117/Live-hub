package com.hmdp.agent.transfer;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.mapper.AgentToolCallMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.8/T4.9：触发器幂等 / 状态锁落库 / 卡片推送 / 移交包组装 / 无人值守建单 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private AgentSessionService sessionService;
    @Mock private FlowStateService flowStateService;
    @Mock private ChatMemoryService memoryService;
    @Mock private AgentToolCallMapper toolCallMapper;
    @Mock private TicketService ticketService;
    @Mock private TrackEventService trackEventService;
    @Mock private SseSessionManager sseManager;
    @Mock private RedissonClient redisson;
    @Mock private RBucket<String> bucket;

    private TransferService service() {
        return service(new AgentProperties());
    }

    private TransferService service(AgentProperties props) {
        return new TransferService(sessionService, flowStateService, memoryService,
                toolCallMapper, ticketService, trackEventService, sseManager, redisson,
                props, new TicketPriorityRules(props));
    }

    private AgentSession active() {
        return new AgentSession().setId(1L).setUserId(100L).setStatus("ACTIVE").setFlowState("IDLE");
    }

    @Test
    void 触发_状态TRANSFERRED_埋点_卡片() {
        AgentSession s = active();
        when(sessionService.markTransferred(1L, "HUMAN_DEMAND")).thenReturn(true);
        service().trigger(s, "HUMAN_DEMAND");
        verify(flowStateService).setFlowState(1L, "IDLE");
        verify(trackEventService).track(eq("m5_transfer_human"), eq(1L), eq(100L), any());
        verify(sseManager).send(eq(1L), eq("card"), any());
        assertEquals("TRANSFERRED", s.getStatus(), "本地状态须同步，防本轮后续误判");
    }

    @Test
    void 并发败者_CAS失败不推卡() {
        AgentSession s = active();
        when(sessionService.markTransferred(1L, "HUMAN_DEMAND")).thenReturn(false);
        service().trigger(s, "HUMAN_DEMAND");
        verify(trackEventService, never()).track(any(), any(), any(), any());
        verify(flowStateService, never()).setFlowState(any(), any());
        verify(sseManager, never()).send(any(), any(), any());
        assertEquals("TRANSFERRED", s.getStatus(), "败者也同步本地状态，本轮后续按锁语义走");
    }

    @Test
    void 重复触发幂等_已TRANSFERRED不再执行() {
        AgentSession s = active().setStatus("TRANSFERRED");
        service().trigger(s, "TOOL_FAIL");
        verify(sessionService, never()).markTransferred(any(), any());
        verify(sseManager, never()).send(any(), any(), any());
    }

    @Test
    void 移交包_四要素快照_Redis写入_snapshotUri回填() {
        AgentSession s = active().setStatus("TRANSFERRED").setTransferReason("HUMAN_DEMAND")
                .setSummary("用户张三，诉求：退款；已查事实：订单200已支付；未解决：退款未提交");
        when(memoryService.readRawJson(1L)).thenReturn("[{\"role\":\"user\",\"content\":\"退款\"}]");
        when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);

        String key = service().buildHandoverPackage(s);

        assertTrue(key.startsWith("agent:transfer:1"));
        verify(bucket).set(contains("退款"), any(java.time.Duration.class));
        verify(sessionService).updateSnapshotUri(eq(1L), contains("agent:transfer:1"));
    }

    @Test
    void 无人值守确认_建单_返回工单号与SLA() {
        AgentSession s = active().setStatus("TRANSFERRED").setTransferReason("HUMAN_DEMAND")
                .setSummary("用户申请转人工：订单退款问题");
        when(sessionService.getOwned(1L, 100L)).thenReturn(s);
        lenient().when(memoryService.readRawJson(1L)).thenReturn("[]");
        lenient().when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        stubCasAcquired();
        com.hmdp.agent.entity.AgentTicket ticket = new com.hmdp.agent.entity.AgentTicket()
                .setTicketNo("TK88").setExpectedSla("24h");
        when(ticketService.create(eq(100L), eq(1L), any())).thenReturn(ticket);

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals("TK88", o.ticketNo());
        // send 第三参是 Map，contains 对 Map 恒不中 → 用 argThat 取 text 字段断言（Task 7 踩坑）
        verify(sseManager).send(eq(1L), eq("delta"), argThat(d ->
                String.valueOf(((Map<?, ?>) d).get("text")).contains("TK88")));
    }

    @Test
    void 会话摘要为空_模板兜底摘要仍可建单() {
        AgentSession s = active().setStatus("TRANSFERRED").setTransferReason("NEGATIVE_EMOTION");
        when(sessionService.getOwned(1L, 100L)).thenReturn(s);
        lenient().when(memoryService.readRawJson(1L)).thenReturn("[]");
        lenient().when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        stubCasAcquired();
        com.hmdp.agent.entity.AgentTicket ticket = new com.hmdp.agent.entity.AgentTicket()
                .setTicketNo("TK99").setExpectedSla("24h");
        when(ticketService.create(eq(100L), eq(1L), any())).thenReturn(ticket);

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        org.mockito.ArgumentCaptor<com.hmdp.agent.dto.TicketRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.hmdp.agent.dto.TicketRequest.class);
        verify(ticketService).create(eq(100L), eq(1L), captor.capture());
        assertTrue(captor.getValue().getSummary().contains("转人工"));
    }

    // ===== T4.9 加固：一次性确认守卫 / 移交包健壮性 / 占位分支 =====

    private AgentSession transferred() {
        return active().setStatus("TRANSFERRED").setTransferReason("HUMAN_DEMAND")
                .setSummary("用户申请转人工：订单退款问题");
    }

    private void stubHappyCreate() {
        when(memoryService.readRawJson(1L)).thenReturn("[]");
        when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(ticketService.create(eq(100L), eq(1L), any())).thenReturn(
                new com.hmdp.agent.entity.AgentTicket().setTicketNo("TK88").setExpectedSla("24h"));
    }

    /** CAS 占位成功路径：flag.get → null，compareAndSet → true */
    private void stubCasAcquired() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.compareAndSet(isNull(), eq("PENDING"))).thenReturn(true);
    }

    @Test
    void 重复确认_返回已有工单号不重复建单() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn("TK88");

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals("TK88", o.ticketNo());
        assertTrue(o.message().contains("TK88"));
        verify(ticketService, never()).create(any(), any(), any());
        verify(bucket, never()).compareAndSet(any(), any());
        verify(bucket, never()).expire(any(java.time.Duration.class));
    }

    @Test
    void 并发占位_处理中() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn("PENDING");

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals(null, o.ticketNo());
        verify(ticketService, never()).create(any(), any(), any());
        verify(sseManager, never()).send(any(), any(), any());
    }

    @Test
    void compareAndSet竞态失败_返回处理中() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.compareAndSet(isNull(), eq("PENDING"))).thenReturn(false);

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals(null, o.ticketNo());
        verify(ticketService, never()).create(any(), any(), any());
        verify(sseManager, never()).send(any(), any(), any());
    }

    @Test
    void 建单失败_标记清除可重试() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        stubCasAcquired();
        when(memoryService.readRawJson(1L)).thenReturn("[]");
        when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        when(ticketService.create(eq(100L), eq(1L), any()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(new com.hmdp.agent.entity.AgentTicket()
                        .setTicketNo("TK77").setExpectedSla("24h"));

        TransferService.TransferOutcome first = service().confirmTransfer(100L, 1L);
        assertTrue(first.success());
        verify(bucket).expire(java.time.Duration.ofSeconds(60));
        verify(bucket).delete();

        TransferService.TransferOutcome second = service().confirmTransfer(100L, 1L);
        assertTrue(second.success());
        assertEquals("TK77", second.ticketNo());
    }

    @Test
    void 无人值守确认_建单_标记回填工单号() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        stubCasAcquired();
        stubHappyCreate();

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals("TK88", o.ticketNo());
        verify(bucket).expire(java.time.Duration.ofSeconds(60));
        verify(bucket).set(eq("TK88"), any(java.time.Duration.class));
    }

    @Test
    void 建单成功后SSE失败_标记保留() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        stubCasAcquired();
        stubHappyCreate();
        org.mockito.Mockito.doThrow(new RuntimeException("sse down"))
                .when(sseManager).send(eq(1L), eq("delta"), any());

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals("TK88", o.ticketNo());
        verify(bucket, never()).delete();
        verify(bucket).set(eq("TK88"), any(java.time.Duration.class));
    }

    @Test
    void 移交包Redis失败_不写snapshotUri仍建单() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());
        stubCasAcquired();
        when(memoryService.readRawJson(1L)).thenReturn("[]");
        when(toolCallMapper.selectList(any())).thenReturn(java.util.List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .doNothing().when(bucket).set(anyString(), any(java.time.Duration.class));
        when(ticketService.create(eq(100L), eq(1L), any())).thenReturn(
                new com.hmdp.agent.entity.AgentTicket().setTicketNo("TK66").setExpectedSla("24h"));

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertEquals("TK66", o.ticketNo());
        verify(sessionService, never()).updateSnapshotUri(any(), any());
    }

    @Test
    void seatOnline占位分支_不建单不写移交包() {
        AgentProperties props = new AgentProperties();
        props.getTransfer().setSeatOnline(true);
        when(sessionService.getOwned(1L, 100L)).thenReturn(transferred());

        TransferService.TransferOutcome o = service(props).confirmTransfer(100L, 1L);

        assertTrue(o.success());
        assertTrue(o.message().contains("坐席"));
        verify(ticketService, never()).create(any(), any(), any());
        verify(redisson, never()).getBucket(anyString());
        verify(memoryService, never()).readRawJson(any());
    }

    @Test
    void 非TRANSFERRED状态拒绝() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(active());

        TransferService.TransferOutcome o = service().confirmTransfer(100L, 1L);

        assertTrue(!o.success());
        verify(ticketService, never()).create(any(), any(), any());
    }
}
