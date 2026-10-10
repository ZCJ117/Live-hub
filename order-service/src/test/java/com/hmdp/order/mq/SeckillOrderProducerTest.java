package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillOrderProducerTest {

    @Mock private RocketMQTemplate rocketMQTemplate;
    @InjectMocks private SeckillOrderProducer producer;

    @Test
    void 事务发送走秒杀主题且arg传null_本地事务靠消息体取参不带外参数() {
        TransactionSendResult expected = new TransactionSendResult();
        expected.setLocalTransactionState(LocalTransactionState.COMMIT_MESSAGE);
        when(rocketMQTemplate.sendMessageInTransaction(eq("seckill-order-topic"), any(), isNull()))
                .thenReturn(expected);

        TransactionSendResult actual =
                producer.sendSeckillOrderMessageInTransaction(new SeckillOrderMessage(9001L, 7L, 1L));

        assertSame(expected, actual, "必须把 template 返回的事务结果原样交给调用方，入口据此分档");

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(
                eq("seckill-order-topic"), captor.capture(), isNull());
        // 回查路径拿不到 arg（broker 只回传消息体），故本地事务也必须能从消息体取参。
        // 这里锁定 arg 为 null，防止后续"顺手"把 orderId 挪进 arg 导致回查判定失效。
        assertInstanceOf(SeckillOrderMessage.class, captor.getValue().getPayload());
    }
}
