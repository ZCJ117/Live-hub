package com.hmdp.order.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 在途订单补偿器（SPEC-14 P0-2）
 *
 * <p>核心断言是「**误释放数为 0**」：DB 已有该订单时必须只清理明细，
 * 绝不能 INCR 库存 / SREM 用户标记——那会凭空造出库存（超卖）或让用户被永久误标。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillInFlightCompensatorTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private VoucherOrderMapper voucherOrderMapper;
    @Mock private VoucherFeignClient voucherFeignClient;
    @Mock private RedissonClient redissonClient;
    @Mock private SeckillOrderProducer seckillOrderProducer;
    @Mock private SeckillMetrics seckillMetrics;
    @Mock private RLock compensateLock;
    @Mock private RLock orderLock;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    @InjectMocks private SeckillInFlightCompensator compensator;

    private static final Long VID = 1L;
    private static final Long ORDER_ID = 9001L;
    private static final Long USER_ID = 7L;
    /** 远超默认阈值 120s 的在途时长 */
    private static final long STALE_TS = System.currentTimeMillis() - 600_000;
    private static final long FRESH_TS = System.currentTimeMillis() - 1_000;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(compensator, "timeoutSeconds", 120L);
        ReflectionTestUtils.setField(compensator, "maxResend", 2);

        when(redissonClient.getLock("lock:compensate:seckill")).thenReturn(compensateLock);
        when(compensateLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(compensateLock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getLock("lock:order:" + ORDER_ID)).thenReturn(orderLock);
        when(orderLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(orderLock.isHeldByCurrentThread()).thenReturn(true);

        when(voucherFeignClient.getActiveSeckillVoucherIds()).thenReturn(Result.ok(List.of(VID)));
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private void detailIs(long ts, int retryCount) {
        String json = String.format(
                "{\"voucherId\":\"%d\",\"userId\":\"%d\",\"orderId\":\"%d\",\"ts\":\"%d\",\"retryCount\":%d}",
                VID, USER_ID, ORDER_ID, ts, retryCount);
        when(hashOperations.entries("seckill:order:detail:1"))
                .thenReturn(Map.of(ORDER_ID.toString(), json));
    }

    @Test
    void 超时未落库_重投MQ并递增retryCount() {
        detailIs(STALE_TS, 0);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);

        compensator.compensateInFlightOrders();

        verify(seckillOrderProducer).sendSeckillOrderMessage(argThat(m ->
                ORDER_ID.equals(m.getOrderId()) && VID.equals(m.getVoucherId()) && USER_ID.equals(m.getUserId())));
        // retryCount 必须递增，否则重投次数无上界
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq("seckill:order:detail:1"), eq("9001"), json.capture());
        assertTrue(json.getValue().contains("\"retryCount\":1"),
                "重投后 retryCount 必须由 0 递增为 1，实际=" + json.getValue());
        verify(seckillMetrics).incrementCompensateResend();
        verify(seckillMetrics, never()).incrementCompensateRelease();
    }

    @Test
    void 未超时的在途单被跳过() {
        detailIs(FRESH_TS, 0);

        compensator.compensateInFlightOrders();

        verifyNoInteractions(seckillOrderProducer);
        verify(voucherOrderMapper, never()).selectById(anyLong());
    }

    @Test
    void 明细缺ts时跳过_不判龄也不释放() {
        String noTs = String.format(
                "{\"voucherId\":\"%d\",\"userId\":\"%d\",\"orderId\":\"%d\"}", VID, USER_ID, ORDER_ID);
        when(hashOperations.entries("seckill:order:detail:1"))
                .thenReturn(Map.of(ORDER_ID.toString(), noTs));

        compensator.compensateInFlightOrders();

        verifyNoInteractions(seckillOrderProducer);
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMetrics, never()).incrementCompensateRelease();
    }

    @Test
    void 误释放数为0_DB已有订单时仅清理明细() {
        detailIs(STALE_TS, 0);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(new VoucherOrder());

        compensator.compensateInFlightOrders();

        // 核心断言：绝不回滚预扣
        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate, never()).opsForSet();
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillMetrics).incrementCompensateCleanup();
        verify(seckillMetrics, never()).incrementCompensateWrongRelease();
    }

    @Test
    void 重投耗尽_安全释放预扣并落pending() {
        detailIs(STALE_TS, 2);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        // 释放前置：明细仍在
        when(hashOperations.hasKey("seckill:order:detail:1", "9001")).thenReturn(true);

        compensator.compensateInFlightOrders();

        verify(valueOperations).increment("seckill:stock:1");
        verify(setOperations).remove("seckill:order:1", "7");
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(listOperations).rightPush(eq("seckill:order:pending"), anyString());
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
        verify(seckillMetrics).incrementCompensateRelease();
        // 释放墓碑：先立碑再回滚，迟到的 MQ 消息与死信回写无法让该单复活
        verify(valueOperations).set(eq("seckill:released:9001"), eq("1"), any(java.time.Duration.class));
    }

    @Test
    void 释放前明细已消失则跳过_不误释放() {
        detailIs(STALE_TS, 2);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        // hasKey 默认 false：明细已被消费端删除，说明订单刚刚落库成功
        when(hashOperations.hasKey("seckill:order:detail:1", "9001")).thenReturn(false);

        compensator.compensateInFlightOrders();

        // 核心断言：此时回滚预扣会直接造成超卖
        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate, never()).opsForSet();
        verify(seckillMetrics, never()).incrementCompensateRelease();
    }

    @Test
    void 重投耗尽时若已落库_走清理路径而非释放() {
        detailIs(STALE_TS, 2);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(new VoucherOrder());

        compensator.compensateInFlightOrders();

        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate, never()).opsForSet();
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillMetrics).incrementCompensateCleanup();
        verify(seckillMetrics, never()).incrementCompensateRelease();
    }

    @Test
    void 已释放订单的明细重新出现_只清理不二次回滚() {
        detailIs(STALE_TS, 2);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(stringRedisTemplate.hasKey("seckill:released:9001")).thenReturn(true);

        compensator.compensateInFlightOrders();

        // 墓碑命中：只清理明细，**绝不再次回滚**——二次 INCR 会凭空多出库存（超卖）
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillMetrics).incrementCompensateCleanup();
        verify(seckillMetrics, never()).incrementCompensateRelease();
        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate, never()).opsForSet();
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
    }

    @Test
    void 重投发送失败_计入失败指标且不重复计数() {
        detailIs(STALE_TS, 0);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);

        compensator.compensateInFlightOrders();

        // 发送失败不留痕 → "用户被静默取消"完全不可观测
        verify(seckillMetrics).incrementCompensateResendFail();
        verify(seckillMetrics, never()).incrementCompensateResend();
    }

    @Test
    void 已释放订单又出现在DB_计入误释放指标() {
        detailIs(STALE_TS, 0);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(new VoucherOrder());
        when(stringRedisTemplate.hasKey("seckill:released:9001")).thenReturn(true);

        compensator.compensateInFlightOrders();

        // 已释放、DB 却有单 —— 当初的释放判断有误，SPEC-14 §6 验收 5 要求该数恒为 0
        verify(seckillMetrics).incrementCompensateWrongRelease();
        verify(seckillMetrics).incrementCompensateCleanup();
        verify(seckillMetrics, never()).incrementCompensateRelease();
    }

    @Test
    void 持有全局补偿锁失败时直接返回_多实例互斥() throws Exception {
        when(compensateLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        compensator.compensateInFlightOrders();

        verify(voucherFeignClient, never()).getActiveSeckillVoucherIds();
    }

    @Test
    void 补偿任务是每分钟执行一次的定时任务() throws Exception {
        var m = SeckillInFlightCompensator.class.getMethod("compensateInFlightOrders");
        var scheduled = m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertNotNull(scheduled, "compensateInFlightOrders 必须标 @Scheduled");
        assertEquals(60000L, scheduled.fixedDelay(), "SPEC-14 P0-2 要求 60s 周期（收敛时间 ≤ T+60s）");
    }
}
