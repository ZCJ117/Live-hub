package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.FeignFailureRateMonitor;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.RedisConstants;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderConsumerTest {

    @Mock private VoucherOrderMapper voucherOrderMapper;
    @Mock private VoucherFeignClient voucherFeignClient;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;
    @Mock private SeckillMetrics seckillMetrics;
    @Mock private FeignFailureRateMonitor feignFailureRateMonitor;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private ValueOperations<String, String> valueOperations;
    @InjectMocks private SeckillOrderConsumer consumer;

    private final SeckillOrderMessage msg = new SeckillOrderMessage(9001L, 7L, 1L);

    @Test
    void 获取锁失败时抛出异常触发重试_而非静默ACK丢弃() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 0));
    }

    @Test
    void 扣库存抛Feign异常时抛出触发重试_不当作业务失败丢弃() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(voucherFeignClient.deductStock(1L, 9001L))
                .thenThrow(new RuntimeException("voucher-service 不可达"));

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 0));
    }

    @Test
    void 扣库存返回库存不足时_移除用户标记但不恢复库存_且不重试() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.fail("库存不足"));

        assertDoesNotThrow(() -> consumer.handleOrder(msg, 0));

        verify(setOperations).remove("seckill:order:1", "7");
        // 关键：不得恢复库存——原实现的无条件 INCR 会凭空造出库存（超卖源）
        verify(valueOperations, never()).increment(RedisConstants.SECKILL_STOCK_KEY + "1");
    }

    @Test
    void 一人一单冲突时_只移除用户标记不恢复库存() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(1L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        assertDoesNotThrow(() -> consumer.handleOrder(msg, 0));

        verify(setOperations).remove("seckill:order:1", "7");
        verify(stringRedisTemplate, never()).opsForValue();
        verify(voucherFeignClient, never()).deductStock(anyLong(), anyLong());
    }

    @Test
    void 插入撞唯一索引时幂等跳过不重试() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());
        when(voucherOrderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new DuplicateKeyException("uk_user_voucher"));

        assertDoesNotThrow(() -> consumer.handleOrder(msg, 0));

        verify(seckillMetrics).incrementMqConsumeSuccess();
    }

    @Test
    void 订单已存在时幂等跳过() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(new VoucherOrder());

        assertDoesNotThrow(() -> consumer.handleOrder(msg, 0));

        verify(voucherFeignClient, never()).deductStock(anyLong(), anyLong());
        verify(seckillMetrics).incrementMqConsumeSuccess();
    }

    @Test
    void 成功路径删除的明细键与脚本写入端一致() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        consumer.handleOrder(msg, 0);

        // seckill.lua:16 写的是 seckill:order:detail:{voucherId}；
        // 原实现用 SECKILL_STOCK_KEY + "order:detail:" 拼成 seckill:stock:order:detail:，
        // 删的是永不存在的 key，明细 hash 永久残留（SPEC-03 §A7）
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
    }

    @Test
    void 命中释放墓碑_不扣库存不建单直接ACK() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(stringRedisTemplate.hasKey("seckill:released:9001")).thenReturn(true);

        // 不抛异常 == 已 ACK 丢弃；抛异常会被 RocketMQ 重投
        assertDoesNotThrow(() -> consumer.handleOrder(msg, 0));

        // 该单已被安全释放：照常消费会在一份已回滚的预扣上重新建单，Redis 库存凭空多 1
        verify(voucherFeignClient, never()).deductStock(any(), any());
        verify(voucherOrderMapper, never()).insert(any());
        verify(seckillMetrics).incrementMqConsumeReleased();
    }

    // ---------- SPEC-04 §5.4 / SPEC-08 §5.5：真实重试次数 ----------

    private void failingDelivery() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(voucherFeignClient.deductStock(1L, 9001L))
                .thenThrow(new RuntimeException("voucher-service 不可达"));
    }

    @Test
    void 重试次数达上限时必须告警并计指标() throws Exception {
        failingDelivery();

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 3));

        // 原实现读业务字段 retryCount（恒为 0），该指标与告警永不触发
        verify(seckillMetrics).incrementRetryExhausted();
    }

    @Test
    void 重试次数未达上限时不计上限告警() throws Exception {
        failingDelivery();

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 2));

        verify(seckillMetrics, never()).incrementRetryExhausted();
    }

    @Test
    void onMessage从MessageExt解析出业务消息并透传真实重试次数() throws Exception {
        failingDelivery();
        MessageExt raw = new MessageExt();
        raw.setBody(new ObjectMapper().writeValueAsBytes(new SeckillOrderMessage(9001L, 7L, 1L)));
        raw.setReconsumeTimes(3);

        assertThrows(RuntimeException.class, () -> consumer.onMessage(raw));

        // 只有 MessageExt 能带来真实重试次数；若仍用业务字段，这里恒为 0、告警不触发
        verify(seckillMetrics).incrementRetryExhausted();
    }

    @Test
    void 扣库存成功时记录Feign成功样本() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherOrderMapper.insert(any())).thenReturn(1);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());

        consumer.handleOrder(msg, 0);

        verify(feignFailureRateMonitor).record(true);
    }

    @Test
    void 扣库存异常时记录Feign失败样本() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherFeignClient.deductStock(1L, 9001L))
                .thenThrow(new RuntimeException("voucher-service 不可达"));

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 0));

        verify(feignFailureRateMonitor).record(false);
    }
}
