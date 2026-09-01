package com.hmdp.agent.ticket;

import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.mapper.AgentTicketMapper;
import com.hmdp.agent.mq.TicketNotifyProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.7：TicketService.create 集成 MQ 通知（发送成功 SENT / 失败 FAILED / MQ 缺席 PENDING） */
@ExtendWith(MockitoExtension.class)
class TicketServiceNotifyTest {

    @Mock private AgentTicketMapper ticketMapper;
    @Mock private RedissonClient redisson;
    @Mock private RAtomicLong seq;
    @Mock private TicketNotifyProducer producer;

    private TicketService service(boolean mqPresent) {
        TicketService s = new TicketService(redisson, mqPresent ? producer : null);
        // ServiceImpl.baseMapper 为字段注入，单测用 ReflectionTestUtils 补（spring-test 随 starter-test 提供）
        org.springframework.test.util.ReflectionTestUtils.setField(s, "baseMapper", ticketMapper);
        // 工单号 seq（默认值风格：mock 默认 0L→incrementAndGet 返回 1L 即可）
        lenient().when(redisson.getAtomicLong(org.mockito.ArgumentMatchers.anyString())).thenReturn(seq);
        lenient().when(seq.incrementAndGet()).thenReturn(1L);
        return s;
    }

    private TicketRequest req() {
        return TicketRequest.of("ORDER", "HIGH", "退款复核工单", Map.of("orderId", 200L));
    }

    @Test
    void 发送成功_notifyStatus_SENT() {
        when(producer.sendRouteNotify(any())).thenReturn(true);
        AgentTicket t = service(true).create(100L, 1L, req());
        assertEquals("SENT", t.getNotifyStatus());
    }

    @Test
    void 发送失败_notifyStatus_FAILED_工单不回滚() {
        when(producer.sendRouteNotify(any())).thenThrow(new RuntimeException("mq down"));
        AgentTicket t = service(true).create(100L, 1L, req());
        assertEquals("FAILED", t.getNotifyStatus());
    }

    @Test
    void MQ缺席_notifyStatus_PENDING_工单正常创建() {
        AgentTicket t = service(false).create(100L, 1L, req());
        assertEquals("PENDING", t.getNotifyStatus());
        verify(producer, never()).sendRouteNotify(any());
    }

    @Test
    void dedup命中_返回已有工单_不发通知() {
        TicketService s = service(true);
        // MP ServiceImpl.getOne 实际调用 selectOne(Wrapper, boolean) 两参 default 方法
        lenient().when(ticketMapper.selectOne(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(
                new AgentTicket().setTicketNo("TK-OLD").setDedupKey("x").setNotifyStatus("SENT"));
        AgentTicket t = s.create(100L, 1L, req());
        assertEquals("TK-OLD", t.getTicketNo());
        verify(producer, never()).sendRouteNotify(any());
    }
}
