package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.metrics.SeckillMetrics;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 死信合流（SPEC-14 P0-2 / D3 选 B / §7 M5）
 *
 * <p>关键点：死信单回写明细 Hash 时，{@code retryCount} 必须**继承已有值并递增**而非重置——
 * 否则「补偿器重投 → 消费再失败 → 再入 DLQ → 又重置」构成无限循环，收敛指标永不达成。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderDLQConsumerTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private SeckillMetrics seckillMetrics;
    @InjectMocks private SeckillOrderDLQConsumer consumer;

    private MessageExt message(SeckillOrderMessage order) throws Exception {
        MessageExt raw = new MessageExt();
        raw.setBody(new ObjectMapper().writeValueAsBytes(order));
        raw.setMsgId("msg-1");
        raw.setReconsumeTimes(3);
        return raw;
    }

    private ArgumentCaptor<String> consumeDetail() {
        return ArgumentCaptor.forClass(String.class);
    }

    @Test
    void 死信单写入明细Hash_而非pending列表() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        consumer.onMessage(message(new SeckillOrderMessage(9001L, 7L, 1L)));

        ArgumentCaptor<String> json = consumeDetail();
        verify(hashOperations).put(eq("seckill:order:detail:1"), eq("9001"), json.capture());
        assertTrue(json.getValue().contains("\"retryCount\":1"),
                "首次死信 retryCount 应为 1，实际=" + json.getValue());
        assertTrue(json.getValue().contains("\"voucherId\":\"1\""));
        // 不再写 pending：pending 现在只由补偿器「释放」时写入，语义是"需人工处置"
        verify(stringRedisTemplate, never()).opsForList();
        verify(seckillMetrics).incrementDlqConsumed();
    }

    @Test
    void 重复死信时retryCount继承已有值并递增_防止无限循环() throws Exception {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("seckill:order:detail:1", "9001"))
                .thenReturn("{\"voucherId\":\"1\",\"userId\":\"7\",\"orderId\":\"9001\",\"ts\":\"1\",\"retryCount\":1}");

        consumer.onMessage(message(new SeckillOrderMessage(9001L, 7L, 1L)));

        ArgumentCaptor<String> json = consumeDetail();
        verify(hashOperations).put(eq("seckill:order:detail:1"), eq("9001"), json.capture());
        assertTrue(json.getValue().contains("\"retryCount\":2"),
                "必须继承已有 retryCount(1) 并递增为 2；若重置为 1 则补偿器永不收敛（SPEC-14 §7 M5）。实际="
                        + json.getValue());
    }

    @Test
    void 无法解析的死信仍然留痕且计数() {
        MessageExt broken = new MessageExt();
        broken.setBody("not-json".getBytes());

        assertDoesNotThrow(() -> consumer.onMessage(broken));

        verify(seckillMetrics).incrementDlqConsumed();
        verifyNoInteractions(stringRedisTemplate);
    }
}
