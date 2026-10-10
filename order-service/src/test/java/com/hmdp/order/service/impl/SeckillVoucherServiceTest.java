package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Spy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

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
    @Mock private SeckillOutboxMapper seckillOutboxMapper;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Spy
    @InjectMocks private VoucherOrderServiceImpl service;

    @BeforeEach
    void login() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(9001L);
        initSeckillOutboxTableInfo();
    }

    /**
     * 纯单测没有 MyBatis-Plus 自动装配，{@code SeckillOutbox} 不会被注册 TableInfo。
     * 而 {@code markOutboxDelivered} 用 {@code Wrappers.lambdaUpdate().eq(SeckillOutbox::getId, ...)}
     * 在**构造 wrapper 时**就要把方法引用解析成列名，缺 TableInfo 会抛
     * {@code MybatisPlusException: can not find lambda cache for this entity}——
     * 该异常被生产代码自己的 catch 吞掉，于是 mock 的 {@code update(...)} 根本不会被调用。
     * 这里补上与 Spring 启动时等价的注册，让断言能真正验证到「标记已投递」这一步。
     */
    private static void initSeckillOutboxTableInfo() throws Exception {
        Field helper = TableInfoHelper.class.getDeclaredField("TABLE_INFO_CACHE");
        helper.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Class<?>, TableInfo> cache = (Map<Class<?>, TableInfo>) helper.get(null);
        cache.put(SeckillOutbox.class, TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SeckillOutbox.class));
    }

    @AfterEach
    void logout() {
        UserHolder.removeUser();
    }

    private void scriptReturns(Long value) {
        // SPEC-14 §7 M4：ARGV 从 3 个增为 4 个（新增 ts），桩必须同步，否则全部落到 null 分支
        when(stringRedisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(value);
    }

    private static TransactionSendResult txResult(SendStatus sendStatus, LocalTransactionState state) {
        TransactionSendResult r = new TransactionSendResult();
        r.setSendStatus(sendStatus);
        r.setLocalTransactionState(state);
        return r;
    }

    private static TransactionSendResult committed() {
        return txResult(SendStatus.SEND_OK, LocalTransactionState.COMMIT_MESSAGE);
    }

    private static TransactionSendResult rolledBack() {
        return txResult(SendStatus.SEND_OK, LocalTransactionState.ROLLBACK_MESSAGE);
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
    void 事务提交_返回订单号_标记已投递且不回滚预扣() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(committed());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Result r = service.seckillVoucher(1L);

        assertTrue(r.getSuccess());
        assertEquals(9001L, r.getData());
        // evaluate：
        // 1. 事件行不再由入口 INSERT —— 它已移入 executeLocalTransaction，由 broker 回调触发。
        //    入口若还 INSERT，half message 与本地写入之间就没有任何绑定，是伪事务消息。
        verify(seckillOutboxMapper, never()).insert(any());
        // 2. 提交后要置为已投递，否则补投器 30s 后会把正常单再投一次（消费端幂等，无害但脏）
        verify(seckillOutboxMapper).update(any(), any());
        verify(valueOperations, never()).increment(anyString());
        verify(seckillMetrics).incrementSeckillSuccess();
        verify(seckillMetrics).incrementMqSendSuccess();
    }

    @Test
    void 事务回滚_返回失败且已回滚预扣_不计成功() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
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
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
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
        verify(seckillOrderProducer, never()).sendSeckillOrderMessageInTransaction(any());
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
        verify(seckillOrderProducer, never()).sendSeckillOrderMessageInTransaction(any());
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
        // 该输入由 parseEpochMillis 的 `raw == null` 早返回直接兜住（SPEC-14 P0-3 评审后改成显式解析）：
        // 不再依赖 String.valueOf(null) → 字面量 "null" → parseLong 抛异常这条偶然路径。
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

    @Test
    void window字段为非数字时放行() {
        clockIs(FIXED_NOW);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet("seckill:window:1", List.of("begin", "end")))
                .thenReturn(java.util.Arrays.asList("not-a-number", "123"));
        scriptReturns(1L);

        Result r = service.seckillVoucher(1L);

        assertEquals("库存不足", r.getErrorMsg(), "不可解析的窗口字段应按「无窗口」放行，不得误拒");
    }

    // ---------- SPEC-14 P0-2 / B1：明细 JSON 增加 ts ----------

    @Test
    void 调用脚本时传入写入时刻作为ARGV4() {
        scriptReturns(1L);

        service.seckillVoucher(1L);

        ArgumentCaptor<String> tsCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).execute(any(), anyList(), eq("1"), eq("7"), eq("9001"), tsCaptor.capture());

        long ts = Long.parseLong(tsCaptor.getValue());
        long now = System.currentTimeMillis();
        // 允许 5 秒时钟裕度：这是"当前时刻"而不是任何硬编码值
        assertTrue(Math.abs(now - ts) < 5_000,
                "ARGV[4] 必须是当前 epoch 毫秒（在途补偿器据此判龄），实际=" + ts + " now=" + now);
    }

    // ---------- SPEC-16：入口接入事务消息 ----------

    @Test
    void 事务回滚时先删事件行再回滚预扣() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        assertEquals("订单提交繁忙，请稍后重试", r.getErrorMsg());

        // 顺序即正确性：先删行、后回滚，崩溃时停在"行已删 + 预扣仍在"的少卖侧；
        // 反过来会停在"预扣已释放 + 行仍待投递"，补投出去就是超卖。
        InOrder order = inOrder(seckillOutboxMapper, valueOperations);
        order.verify(seckillOutboxMapper).deleteById(9001L);
        order.verify(valueOperations).increment("seckill:stock:1");
    }

    @Test
    void 事务消息发送抛异常_回滚预扣且不删事件行() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any()))
                .thenThrow(new org.springframework.messaging.MessagingException("broker 不可达"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        verify(valueOperations).increment("seckill:stock:1");
        // 抛异常 ⇒ half message 未落盘 ⇒ executeLocalTransaction 根本没跑 ⇒ 不可能有事件行。
        // 这里若调 deleteById 会掩盖"异常发生在发送阶段"这个诊断信息。
        verify(seckillOutboxMapper, never()).deleteById(any());
        verify(seckillMetrics).incrementMqSendFail();
    }

    @Test
    void half_message未落盘_sendStatus非OK_按失败处理并回滚() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any()))
                .thenReturn(txResult(SendStatus.FLUSH_DISK_TIMEOUT, LocalTransactionState.COMMIT_MESSAGE));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess(),
                "sendStatus 非 SEND_OK 时 half message 未确认落盘，即便本地状态是 COMMIT 也不能算成功");
        verify(valueOperations).increment("seckill:stock:1");
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }

    @Test
    void 事务发送返回null_按失败处理不抛NPE() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }

    @Test
    void 事件行删除失败时保留预扣不回滚() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
        when(seckillOutboxMapper.deleteById(9001L)).thenThrow(new RuntimeException("DB 不可用"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        // 行删不掉 → 该单仍会被补投器投递 → 预扣必须保留。
        // 若这里回滚了，补投出去的消息会在已释放的预扣上重新建单，Redis 库存比 DB 多 1 = 超卖。
        verify(valueOperations, never()).increment(anyString());
        verify(setOperations, never()).remove(anyString(), anyString());
        verify(hashOperations, never()).delete(anyString(), any());
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }
}
