package com.hmdp.agent.confirm;

import com.hmdp.agent.confirm.ConfirmService.ConfirmOutcome;
import com.hmdp.agent.dto.ConfirmRequest;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.feign.OrderFeignClient;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.3/4.4/4.5：确认编排——幂等/过期/越权/取消/退款失败回滚/联动建单 */
@ExtendWith(MockitoExtension.class)
class ConfirmServiceTest {

    @Mock private AgentSessionService sessionService;
    @Mock private ConfirmTaskService confirmTaskService;
    @Mock private OrderFeignClient orderFeignClient;
    @Mock private TicketService ticketService;
    @Mock private FlowStateService flowStateService;
    @Mock private TicketPriorityRules priorityRules;

    private ConfirmService service() {
        return new ConfirmService(sessionService, confirmTaskService, orderFeignClient,
                ticketService, flowStateService, priorityRules);
    }

    private AgentSession session() {
        return new AgentSession().setId(1L).setUserId(100L).setStatus("ACTIVE").setFlowState("REFUNDING");
    }

    private AgentTask pendingTask() {
        return new AgentTask().setId(9L).setActionId("a1").setSessionId(1L).setUserId(100L)
                .setTaskType("REFUND_REQUEST").setStatus("PENDING_CONFIRM")
                .setBizOrderId(200L).setExpireTime(LocalDateTime.now().plusMinutes(5));
    }

    private ConfirmRequest confirmReq() {
        ConfirmRequest r = new ConfirmRequest();
        r.setActionId("a1");
        r.setDecision("CONFIRM");
        r.setReason("不要了");
        return r;
    }

    @Test
    void 归属校验失败_拦截() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.empty());
        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());
        assertFalse(o.success());
        verify(orderFeignClient, never()).refund(any());
    }

    @Test
    void 跨会话使用actionId_拦截() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask otherSession = pendingTask().setSessionId(999L);
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(otherSession));
        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());
        assertFalse(o.success());
        verify(orderFeignClient, never()).refund(any());
    }

    @Test
    void 过期卡片_置EXPIRED_提示重新发起() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask expired = pendingTask().setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(expired));
        when(confirmTaskService.expireIfOverdue(expired)).thenReturn(true);
        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());
        assertFalse(o.success());
        assertTrue(o.message().contains("过期"));
    }

    @Test
    void 取消_卡片作废_flowState回IDLE() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        ConfirmRequest req = confirmReq();
        req.setDecision("CANCEL");
        ConfirmOutcome o = service().confirm(100L, 1L, req);
        assertTrue(o.success());
        verify(confirmTaskService).reject(task);
        verify(flowStateService).setFlowState(1L, "IDLE");
        verify(orderFeignClient, never()).refund(any());
    }

    @Test
    void 确认成功_受理编号RF_联动建单绑定_ticketId() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(List.of());
        when(confirmTaskService.tryAdopt("a1")).thenReturn(true);
        when(orderFeignClient.refund(any())).thenReturn(Result.ok(Map.of("orderId", 200L)));
        AgentTicket ticket = new AgentTicket().setId(77L).setTicketNo("TK9").setExpectedSla("24h");
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class))).thenReturn(ticket);
        when(priorityRules.fundRelated(any())).thenReturn(false);

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertTrue(o.success());
        assertEquals("RF9", o.refundNo());
        assertEquals("TK9", o.ticketNo());
        verify(confirmTaskService).bindTicket(9L, 77L);
        verify(flowStateService).setFlowState(1L, "IDLE");
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(orderFeignClient).refund(body.capture());
        assertEquals(200L, body.getValue().get("orderId"));
    }

    @Test
    void 退款业务失败_卡片作废_返回具体原因() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(List.of());
        when(confirmTaskService.tryAdopt("a1")).thenReturn(true);
        when(orderFeignClient.refund(any())).thenReturn(Result.fail("订单状态已变更，请刷新后查看"));

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertFalse(o.success());
        assertTrue(o.message().contains("订单状态已变更"));
        verify(confirmTaskService).rejectAfterAdopt(task);
        verify(ticketService, never()).create(any(), any(), any());
    }

    @Test
    void 订单已在退款中_返回既有受理编号() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(List.of());
        when(confirmTaskService.tryAdopt("a1")).thenReturn(true);
        when(orderFeignClient.refund(any())).thenReturn(Result.fail("该订单已有进行中的退款申请"));
        when(confirmTaskService.findAdoptedByOrder(100L, 200L)).thenReturn(
                Optional.of(new AgentTask().setId(5L).setStatus("ADOPTED")));

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertTrue(o.success());
        assertEquals("RF5", o.refundNo());
    }

    @Test
    void 同订单其他PENDING卡片未消费_先拦截() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(
                List.of(new AgentTask().setId(5L).setStatus("PENDING_CONFIRM")));

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertFalse(o.success());
        verify(orderFeignClient, never()).refund(any());
    }

    @Test
    void 并发败者_重读到ADOPTED_幂等返回相同RF() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(List.of());
        when(confirmTaskService.tryAdopt("a1")).thenReturn(false);
        // 连续 stub：第 1 次（步骤2 归属查询）PENDING → 第 2 次（败者重读）ADOPTED
        when(confirmTaskService.findByActionId(100L, "a1"))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(pendingTask().setStatus("ADOPTED")));

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertTrue(o.success(), "败者应幂等返回成功结果而非报错");
        assertEquals("RF9", o.refundNo());
        verify(orderFeignClient, never()).refund(any());
    }

    @Test
    void 建单失败_退款仍受理成功_提示补录() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session());
        AgentTask task = pendingTask();
        when(confirmTaskService.findByActionId(100L, "a1")).thenReturn(Optional.of(task));
        when(confirmTaskService.findActiveByOrder(100L, 200L, 9L)).thenReturn(List.of());
        when(confirmTaskService.tryAdopt("a1")).thenReturn(true);
        when(orderFeignClient.refund(any())).thenReturn(Result.ok(Map.of("orderId", 200L)));
        when(ticketService.create(eq(100L), eq(1L), any(TicketRequest.class)))
                .thenThrow(new RuntimeException("db"))
                .thenThrow(new RuntimeException("db"));

        ConfirmOutcome o = service().confirm(100L, 1L, confirmReq());

        assertTrue(o.success(), "退款受理是事实源，建单失败不改变受理结果（D-4）");
        assertEquals("RF9", o.refundNo());
        assertEquals(null, o.ticketNo());
        assertTrue(o.message().contains("复核工单"));
        verify(flowStateService).setFlowState(1L, "IDLE");
    }
}
