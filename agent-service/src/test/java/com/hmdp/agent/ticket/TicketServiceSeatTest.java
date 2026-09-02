package com.hmdp.agent.ticket;

import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.mapper.AgentTicketMapper;
import com.hmdp.agent.mq.TicketNotifyProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D9 工作台：坐席侧状态流转（与用户侧同一状态机，非法跳转 100% 拦截；不校验工单归属）
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceSeatTest {

    @Mock private AgentTicketMapper ticketMapper;
    @Mock private RedissonClient redisson;
    @Mock private TicketNotifyProducer producer;

    private TicketService service() {
        TicketService s = new TicketService(redisson, producer);
        // ServiceImpl.baseMapper 为字段注入，单测用 ReflectionTestUtils 补
        ReflectionTestUtils.setField(s, "baseMapper", ticketMapper);
        return s;
    }

    private AgentTicket ticket(String status) {
        return new AgentTicket().setTicketNo("TK1").setStatus(status);
    }

    @Test
    void 坐席流转_OPEN到ROUTED_成功() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket("OPEN")));
        AgentTicket r = service().transitionBySeat("TK1", "ROUTED", null);
        assertEquals("ROUTED", r.getStatus());
    }

    @Test
    void 坐席流转_非法跳转OPEN直达RESOLVED_拒绝() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket("OPEN")));
        assertThrows(BusinessException.class, () -> service().transitionBySeat("TK1", "RESOLVED", "备注"));
    }

    @Test
    void 坐席流转_解决_记录处理结果与时间() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(ticket("ROUTED")));
        AgentTicket r = service().transitionBySeat("TK1", "RESOLVED", "已退款");
        assertEquals("RESOLVED", r.getStatus());
        assertEquals("已退款", r.getHandleResult());
        assertNotNull(r.getResolveTime());
    }
}
