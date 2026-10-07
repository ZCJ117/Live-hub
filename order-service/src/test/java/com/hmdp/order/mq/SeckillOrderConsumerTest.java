package com.hmdp.order.mq;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
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
    @Mock private SetOperations<String, String> setOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @InjectMocks private SeckillOrderConsumer consumer;

    private final SeckillOrderMessage msg = new SeckillOrderMessage(9001L, 7L, 1L);

    @Test
    void 获取锁失败时抛出异常触发重试_而非静默ACK丢弃() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThrows(RuntimeException.class, () -> consumer.onMessage(msg));
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

        assertThrows(RuntimeException.class, () -> consumer.onMessage(msg));
    }

    @Test
    void 扣库存返回库存不足时_移除用户标记但不恢复库存_且不重试() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.fail("库存不足"));

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(setOperations).remove("seckill:order:1", "7");
        // 关键：不得恢复库存——原实现的无条件 INCR 会凭空造出库存（超卖源）
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void 一人一单冲突时_只移除用户标记不恢复库存() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(1L);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        assertDoesNotThrow(() -> consumer.onMessage(msg));

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

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(seckillMetrics).incrementMqConsumeSuccess();
    }

    @Test
    void 订单已存在时幂等跳过() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(new VoucherOrder());

        assertDoesNotThrow(() -> consumer.onMessage(msg));

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

        consumer.onMessage(msg);

        // seckill.lua:16 写的是 seckill:order:detail:{voucherId}；
        // 原实现用 SECKILL_STOCK_KEY + "order:detail:" 拼成 seckill:stock:order:detail:，
        // 删的是永不存在的 key，明细 hash 永久残留（SPEC-03 §A7）
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
    }
}
