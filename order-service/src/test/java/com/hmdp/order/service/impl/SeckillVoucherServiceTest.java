package com.hmdp.order.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Spy;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillVoucherServiceTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisIdWorker redisIdWorker;
    @Mock private SeckillOrderProducer seckillOrderProducer;
    @Mock private SeckillMetrics seckillMetrics;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Spy
    @InjectMocks private VoucherOrderServiceImpl service;

    @BeforeEach
    void login() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(9001L);
    }

    @AfterEach
    void logout() {
        UserHolder.removeUser();
    }

    private void scriptReturns(Long value) {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(value);
    }

    @Test
    void 脚本返回1_库存不足() {
        scriptReturns(1L);
        Result r = service.seckillVoucher(1L);
        assertFalse(r.getSuccess());
        assertEquals("库存不足", r.getErrorMsg());
        verify(seckillMetrics).incrementStockInsufficient();
    }

    @Test
    void 脚本返回2_重复下单() {
        scriptReturns(2L);
        Result r = service.seckillVoucher(1L);
        assertFalse(r.getSuccess());
        assertEquals("不能重复下单", r.getErrorMsg());
        verify(seckillMetrics).incrementDuplicateOrder();
    }

    @Test
    void 脚本返回3_key缺失_走专门指标且不与库存不足混淆() {
        scriptReturns(3L);
        Result r = service.seckillVoucher(1L);
        assertFalse(r.getSuccess());
        verify(seckillMetrics).incrementRedisStockMissing();
        verify(seckillMetrics, never()).incrementStockInsufficient();
    }

    @Test
    void 脚本返回null_按key缺失处理不抛NPE() {
        scriptReturns(null);
        Result r = service.seckillVoucher(1L);
        assertFalse(r.getSuccess());
        verify(seckillMetrics).incrementRedisStockMissing();
    }

    @Test
    void 脚本返回0_消息发送成功_返回订单号且计成功() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);

        Result r = service.seckillVoucher(1L);

        assertTrue(r.getSuccess());
        assertEquals(9001L, r.getData());
        verify(seckillMetrics).incrementSeckillSuccess();
        verify(seckillMetrics).incrementMqSendSuccess();
    }

    @Test
    void 脚本返回0_消息发送失败_返回失败且回滚预扣_不计成功() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        // 回滚三件事：库存 +1、移除用户标记、删除明细。
        // 只断言"取过 handles"不足以证明回滚发生——取了不用照样能通过，
        // 而静默失败会让用户被永久标记"已购买"且库存凭空少 1，故必须锁定具体键与参数。
        verify(valueOperations).increment("seckill:stock:1");
        verify(setOperations).remove("seckill:order:1", "7");
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillMetrics).incrementMqSendFail();
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }

    @Test
    void 未登录时返回失败而非NPE() {
        UserHolder.removeUser();
        Result r = service.seckillVoucher(1L);
        assertFalse(r.getSuccess());
        assertEquals("未登录，请先登录", r.getErrorMsg());
    }

    @Test
    void 库存key缺失与MQ发送失败必须给出可区分的文案() {
        // SPEC-03 §9 A8：Result 没有 code 字段，错误文案就是错误码。
        // 两类失败的处置动作完全不同（去预热库存 vs 去查 broker），不能共用「系统繁忙」。
        scriptReturns(3L);
        String stockKeyMissing = service.seckillVoucher(1L).getErrorMsg();

        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        String mqSendFailed = service.seckillVoucher(1L).getErrorMsg();

        assertNotEquals(stockKeyMissing, mqSendFailed,
                "SPEC-03 A8 要求可区分的错误码：「秒杀通道未就绪」与「订单提交繁忙」不能是同一句文案");
    }

    // ---------- SPEC-14 P0-3：活动时间窗 ----------

    private static final long WINDOW_KEY_VID = 1L;

    /** 所有时间窗用例共用的冻结时刻（2026-01-01T00:00:00Z），与真实墙钟无关。 */
    private static final long FIXED_NOW = 1_767_225_600_000L;

    /**
     * 冻结入口读取的时刻。必须用 {@code doReturn(...).when(...)}：
     * {@code when(service.nowMillis())} 会在打桩阶段就调一次真实方法，seam 形同虚设。
     */
    private void clockIs(long fixedNow) {
        doReturn(fixedNow).when(service).nowMillis();
    }

    /** 让入口读到指定的活动时间窗（epoch millis）。传 null 表示模拟 key 缺失。 */
    private void windowIs(Long beginMs, Long endMs) {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        if (beginMs == null && endMs == null) {
            when(hashOperations.multiGet("seckill:window:1", List.of("begin", "end")))
                    .thenReturn(java.util.Collections.emptyList());
            return;
        }
        when(hashOperations.multiGet("seckill:window:1", List.of("begin", "end")))
                .thenReturn(java.util.Arrays.asList(String.valueOf(beginMs), String.valueOf(endMs)));
    }

    @Test
    void 活动开始前被拒_文案可区分且计入专门指标() {
        clockIs(FIXED_NOW);
        long begin = FIXED_NOW + 60_000;
        windowIs(begin, begin + 3600_000);
        scriptReturns(0L);

        Result r = service.seckillVoucher(WINDOW_KEY_VID);

        assertFalse(r.getSuccess());
        assertEquals("秒杀活动尚未开始", r.getErrorMsg());
        verify(seckillMetrics).incrementSeckillNotStarted();
        // 关键：窗口外必须在任何 Redis 脚本调用**之前**就返回。
        // 断言 nextId（时间窗判定后的第一条语句）未被调用，而不是去 verify execute 的 varargs 参数个数——
        // Mockito 的 varargs 匹配是位置式的，用 N+1 个匹配器去 verify 一个 N 参调用会**恒真**（假绿）。
        verify(redisIdWorker, never()).nextId(anyString());
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
    }

    @Test
    void 活动结束后被拒_文案可区分且计入专门指标() {
        clockIs(FIXED_NOW);
        long end = FIXED_NOW - 1_000;
        windowIs(end - 3600_000, end);
        scriptReturns(0L);

        Result r = service.seckillVoucher(WINDOW_KEY_VID);

        assertFalse(r.getSuccess());
        assertEquals("秒杀活动已结束", r.getErrorMsg());
        verify(seckillMetrics).incrementSeckillEnded();
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
    }

    @Test
    void 恰好等于开始时刻_放行_与活跃券闭区间口径一致() {
        clockIs(FIXED_NOW);
        windowIs(FIXED_NOW, FIXED_NOW + 3600_000);
        scriptReturns(1L);

        Result r = service.seckillVoucher(WINDOW_KEY_VID);

        // 放行的证据 = 没被判成窗口外。时钟已冻结，闭区间两端均可原子断言。
        assertFalse(r.getSuccess());
        assertEquals("库存不足", r.getErrorMsg());
    }

    @Test
    void 恰好等于结束时刻_放行_与活跃券闭区间口径一致() {
        clockIs(FIXED_NOW);
        windowIs(FIXED_NOW - 3600_000, FIXED_NOW);
        scriptReturns(1L);

        Result r = service.seckillVoucher(WINDOW_KEY_VID);

        // 冻结时钟后 end == now 属闭区间内，必须在 Lua 之后才谈得上拒绝
        assertFalse(r.getSuccess());
        assertEquals("库存不足", r.getErrorMsg());
    }

    @Test
    void 开始前1毫秒被拒() {
        clockIs(FIXED_NOW);
        windowIs(FIXED_NOW + 1, FIXED_NOW + 3600_000);
        scriptReturns(0L);

        assertEquals("秒杀活动尚未开始", service.seckillVoucher(WINDOW_KEY_VID).getErrorMsg());
    }

    @Test
    void 结束后1毫秒被拒() {
        clockIs(FIXED_NOW);
        windowIs(FIXED_NOW - 3600_000, FIXED_NOW - 1);
        scriptReturns(0L);

        assertEquals("秒杀活动已结束", service.seckillVoucher(WINDOW_KEY_VID).getErrorMsg());
    }

    @Test
    void window_key缺失时放行_历史券行为不变() {
        clockIs(FIXED_NOW);
        windowIs(null, null);
        scriptReturns(1L);

        Result r = service.seckillVoucher(WINDOW_KEY_VID);

        assertEquals("库存不足", r.getErrorMsg());
    }

    @Test
    void window字段为null时放行_键存在但值缺失() {
        // 真实 Redis 对不存在的 field 返回 null 而非缺项：multiGet 得到 [null, null]。
        // 实测（变异验证）：删掉 checkSeckillWindow 里的 `window.get(i) == null` 守卫，本用例**仍绿**——
        // String.valueOf(null Object) 返回字面量 "null"，Long.parseLong("null") 抛 NumberFormatException，
        // 被下方「格式非法→放行」分支兜住，结果同为放行。即 null 守卫对本输入是冗余的第二道闸，
        // 断言只能锁住「最终放行」这一可观测行为，锁不住具体走哪条分支。
        clockIs(FIXED_NOW);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet("seckill:window:1", List.of("begin", "end")))
                .thenReturn(java.util.Arrays.asList(null, null));
        scriptReturns(1L);

        Result r = service.seckillVoucher(1L);

        assertEquals("库存不足", r.getErrorMsg(), "字段为 null 应按「无窗口」放行，不得 NPE 也不得误拒");
        verify(seckillMetrics, never()).incrementSeckillNotStarted();
        verify(seckillMetrics, never()).incrementSeckillEnded();
    }
}
