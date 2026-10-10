package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderTransactionListenerTest {

    @Mock private SeckillOutboxMapper seckillOutboxMapper;
    @Mock private SeckillMetrics seckillMetrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private SeckillOrderTransactionListener listener;

    /**
     * 必须喂**真字节** payload：rocketmq-spring 的 RocketMQUtil.convertToSpringMessage
     * 用 msg.getBody() 作为 payload，即原始 byte[]。塞一个 mock 对象进去会绕过反序列化链路，
     * 让"回查取不到 orderId"这类真故障在单测里隐形。
     */
    private Message<byte[]> messageOf(Long orderId) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(new SeckillOrderMessage(orderId, 7L, 1L));
        return MessageBuilder.withPayload(body).build();
    }

    @Test
    void 本地事务成功_落一条待投递事件行并提交() throws Exception {
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(messageOf(9001L), null);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);

        ArgumentCaptor<SeckillOutbox> row = ArgumentCaptor.forClass(SeckillOutbox.class);
        verify(seckillOutboxMapper).insert(row.capture());
        assertEquals(9001L, row.getValue().getId(), "orderId 必须来自消息体，不能来自 arg");
        assertEquals(7L, row.getValue().getUserId());
        assertEquals(1L, row.getValue().getVoucherId());
        assertEquals(SeckillOutbox.STATUS_PENDING, row.getValue().getStatus());
        assertEquals(0, row.getValue().getRetryCount());
    }

    @Test
    void 本地事务落库失败_返回回滚而非提交() throws Exception {
        when(seckillOutboxMapper.insert(any())).thenThrow(new RuntimeException("DB 不可用"));

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(messageOf(9001L), null));
    }

    @Test
    void 回查_事件行存在_判为已提交并计入commit指标() throws Exception {
        when(seckillOutboxMapper.selectById(9001L)).thenReturn(
                new SeckillOutbox().setId(9001L).setStatus(SeckillOutbox.STATUS_PENDING));

        assertEquals(RocketMQLocalTransactionState.COMMIT, listener.checkLocalTransaction(messageOf(9001L)));
        verify(seckillMetrics).incrementTxCheckCommit();
        verify(seckillMetrics, never()).incrementTxCheckRollback();
    }

    @Test
    void 回查_事件行不存在_判为回滚并计入rollback指标() throws Exception {
        when(seckillOutboxMapper.selectById(9001L)).thenReturn(null);

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.checkLocalTransaction(messageOf(9001L)));
        verify(seckillMetrics).incrementTxCheckRollback();
        verify(seckillMetrics, never()).incrementTxCheckCommit();
    }

    @Test
    void 回查_消息体不可解析_返回UNKNOWN交由broker下一轮再问() {
        Message<byte[]> broken = MessageBuilder.withPayload("not-json".getBytes()).build();

        // 解析不出来时**绝不能**猜 ROLLBACK：那会把一个可能已提交的本地事务永久丢弃（少卖），
        // 而 UNKNOWN 只是让 broker 下一轮再问一次。
        assertEquals(RocketMQLocalTransactionState.UNKNOWN, listener.checkLocalTransaction(broken));
        verify(seckillMetrics, never()).incrementTxCheckCommit();
        verify(seckillMetrics, never()).incrementTxCheckRollback();
    }
}
