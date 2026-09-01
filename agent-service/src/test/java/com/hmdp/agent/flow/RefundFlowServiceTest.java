package com.hmdp.agent.flow;

import com.hmdp.agent.confirm.ConfirmTaskService;
import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.mapper.AgentTaskMapper;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.tool.QueryMyOrdersTool;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4.2：退款编排——0 单说明 / 多单选择 / 单单直接生成卡片 / 已有进行中返回编号 / 已核销不可退
 */
@ExtendWith(MockitoExtension.class)
class RefundFlowServiceTest {

    @Mock private QueryMyOrdersTool queryTool;
    @Mock private ConfirmTaskService confirmTaskService;
    @Mock private TrackEventService trackEventService;
    @Mock private SseSessionManager sseManager;
    @Mock private AgentTaskMapper taskMapper;

    private RefundFlowService service() {
        return new RefundFlowService(queryTool, confirmTaskService, trackEventService, sseManager, taskMapper);
    }

    private ToolContext ctx() {
        return ToolContext.builder().sessionId(1L).userId(100L).traceId("t").build();
    }

    private AgentSession session() {
        return new AgentSession().setId(1L).setUserId(100L).setStatus("ACTIVE").setFlowState("REFUNDING");
    }

    private OrderCardDTO paid(long id, String title) {
        return OrderCardDTO.from(id, 300L, title, 5000L, 10000L, 2, LocalDateTime.now());
    }

    private void stubOrders(OrderCardDTO... cards) {
        ToolResult r = ToolResult.builder().success(true).data(List.of(cards))
                .summary("ok").build();
        when(queryTool.queryMyOrders(any(), anyMap())).thenReturn(r);
    }

    @Test
    void 单单可退_生成卡片并推送REFUND_CONFIRM() {
        stubOrders(paid(200L, "国庆5折券"));
        AgentTask task = new AgentTask().setId(9L).setActionId("act-1").setBizOrderId(200L)
                .setStatus("PENDING_CONFIRM").setExpireTime(LocalDateTime.now().plusMinutes(10));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(confirmTaskService.createRefundTask(any(), any())).thenReturn(task);

        service().handle(session(), ctx());

        verify(confirmTaskService).createRefundTask(any(), any());
        verify(sseManager).send(eq(1L), eq("card"), any());
        verify(trackEventService).track(eq("m5_refund_card_show"), eq(1L), eq(100L), any());
    }

    @Test
    void 多单可退_推送订单选择卡片_不生成任务() {
        stubOrders(paid(200L, "券A"), paid(201L, "券B"), paid(202L, "券C"));
        service().handle(session(), ctx());
        verify(confirmTaskService, never()).createRefundTask(any(), any());
        verify(sseManager).send(eq(1L), eq("card"), any());
        verify(trackEventService, never()).track(eq("m5_refund_card_show"), any(), any(), any());
    }

    @Test
    void 无可退订单_仅话术说明_不生成卡片() {
        // 全部已核销（status=3）
        OrderCardDTO used = OrderCardDTO.from(200L, 300L, "已用券", 5000L, 10000L, 3, LocalDateTime.now());
        stubOrders(used);
        service().handle(session(), ctx());
        verify(confirmTaskService, never()).createRefundTask(any(), any());
        verify(sseManager, never()).send(eq(1L), eq("card"), any());
    }

    @Test
    void 已有进行中ADOPTED_返回受理编号_不重复建卡() {
        stubOrders(paid(200L, "券A"));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                new AgentTask().setId(5L).setStatus("ADOPTED").setBizOrderId(200L)));
        service().handle(session(), ctx());
        verify(confirmTaskService, never()).createRefundTask(any(), any());
        verify(sseManager, never()).send(eq(1L), eq("card"), any());
    }

    @Test
    void 已有PENDING卡片_重新推送卡片() {
        stubOrders(paid(200L, "券A"));
        AgentTask pending = new AgentTask().setId(5L).setActionId("act-9")
                .setStatus("PENDING_CONFIRM").setBizOrderId(200L)
                .setExpireTime(LocalDateTime.now().plusMinutes(3));
        when(taskMapper.selectList(any())).thenReturn(List.of(pending));
        service().handle(session(), ctx());
        verify(confirmTaskService, never()).createRefundTask(any(), any());
        verify(sseManager).send(eq(1L), eq("card"), any());
        verify(trackEventService).track(eq("m5_refund_card_show"), eq(1L), eq(100L), any());
    }

    @Test
    void 查询工具失败_降级话术() {
        when(queryTool.queryMyOrders(any(), anyMap())).thenReturn(
                ToolResult.fail("ORDER_TIMEOUT", "订单服务暂时繁忙"));
        service().handle(session(), ctx());
        verify(sseManager, never()).send(eq(1L), eq("card"), any());
    }
}
