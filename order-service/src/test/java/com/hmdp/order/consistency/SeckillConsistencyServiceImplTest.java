package com.hmdp.order.consistency;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.entity.SeckillConsistencyAudit;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.SeckillConsistencyAuditMapper;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.service.impl.SeckillConsistencyServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-04 §5.5 对账与修复（验收 A2 / A3 / A4）
 *
 * <p>这三个接口原先是"空壳三兄弟"：对账恒判一致、修复只读统计、同步零写入却报成功。
 * 本测试锁定它们**真的读写**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillConsistencyServiceImplTest {

    private static final Long VOUCHER_ID = 1L;
    private static final Long ORDER_ID = 123L;

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private ListOperations<String, String> listOps;
    @Mock private VoucherOrderMapper voucherOrderMapper;
    @Mock private SeckillConsistencyAuditMapper auditMapper;
    @Mock private VoucherFeignClient voucherFeignClient;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @InjectMocks private SeckillConsistencyServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
    }

    /** 无在途订单：Redis 可售数应等于 DB 余量 */
    private void noInFlight() {
        when(setOps.size(RedisConstants.orderKey(VOUCHER_ID))).thenReturn(0L);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
    }

    // ---------------------------------------------------------------- A2

    @Test
    void 库存分歧时必须判为不一致() {
        // SPEC-04 §8.2 的构造用例：DB 库存 10，Redis 被人为改成 7
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(hashOps.get(RedisConstants.detailKey(VOUCHER_ID), ORDER_ID.toString())).thenReturn(null);

        Result r = service.checkOrderConsistency(ORDER_ID, VOUCHER_ID);

        assertFalse(Boolean.TRUE.equals(r.getSuccess()),
                "Redis 7 / DB 10 必须判为不一致；原实现 key 缺 voucherId 后缀 + 用 entries() 读整表，"
                        + "恒判「订单一致性正常」，实际=" + r);
        assertTrue(r.getErrorMsg() != null && r.getErrorMsg().contains("不一致"),
                "不一致必须体现在错误文案里，实际=" + r.getErrorMsg());
    }

    @Test
    void 库存一致时必须判为一致() {
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(7));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(hashOps.get(RedisConstants.detailKey(VOUCHER_ID), ORDER_ID.toString())).thenReturn(null);

        assertTrue(Boolean.TRUE.equals(service.checkOrderConsistency(ORDER_ID, VOUCHER_ID).getSuccess()));
    }

    @Test
    void Redis有预扣明细但DB无订单时必须判为不一致() {
        // 消息丢失：Lua 已预扣并写明细，但订单从未落库
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(hashOps.get(RedisConstants.detailKey(VOUCHER_ID), ORDER_ID.toString()))
                .thenReturn("{\"orderId\":123}");

        Result r = service.checkOrderConsistency(ORDER_ID, VOUCHER_ID);

        assertFalse(Boolean.TRUE.equals(r.getSuccess()),
                "Redis 有预扣、DB 无订单 = 消息丢失，绝不能判为一致");
    }

    @Test
    void Redis库存key缺失时不得判为一致() {
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn(null);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(hashOps.get(RedisConstants.detailKey(VOUCHER_ID), ORDER_ID.toString())).thenReturn(null);

        assertFalse(Boolean.TRUE.equals(service.checkOrderConsistency(ORDER_ID, VOUCHER_ID).getSuccess()),
                "key 缺失是运维态，不能被当成「一致」");
    }

    @Test
    void 在途订单必须计入期望库存_不能把Redis直接拉平到DB() {
        // DB 余量 10，其中 3 单已预扣未落库 → Redis 应为 7 才算一致（拉平到 10 会超卖）
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(setOps.size(RedisConstants.orderKey(VOUCHER_ID))).thenReturn(3L);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(hashOps.get(RedisConstants.detailKey(VOUCHER_ID), ORDER_ID.toString())).thenReturn(null);

        assertTrue(Boolean.TRUE.equals(service.checkOrderConsistency(ORDER_ID, VOUCHER_ID).getSuccess()),
                "3 单在途时 Redis==DB-3 是正常态，不得误报不一致");
    }

    // ---------------------------------------------------------------- A3

    @Test
    void repair必须以DB为准把Redis收敛回来() {
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");

        Result r = service.repairInconsistentData(VOUCHER_ID);

        // 原实现全程无 set/update/delete，Redis 修复后仍是 7
        verify(valueOps).set(RedisConstants.stockKey(VOUCHER_ID), "10");
        assertTrue(Boolean.TRUE.equals(r.getSuccess()));
        Map<?, ?> report = (Map<?, ?>) r.getData();
        assertEquals(Boolean.TRUE, report.get("repaired"),
                "真实写入后必须显式标记 repaired=true，杜绝「检查完成」式的虚假成功");
        assertEquals("DB_TO_REDIS", report.get("action"));
    }

    @Test
    void repair必须留下审计记录() {
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");

        service.repairInconsistentData(VOUCHER_ID);

        verify(auditMapper).insert(any(SeckillConsistencyAudit.class));
    }

    @Test
    void repair在已一致时不得谎报已修复() {
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(7));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");

        Result r = service.repairInconsistentData(VOUCHER_ID);

        Map<?, ?> report = (Map<?, ?>) r.getData();
        assertEquals(Boolean.FALSE, report.get("repaired"), "无分歧时必须是 repaired=false");
        verify(valueOps, never()).set(anyString(), anyString());
        verify(auditMapper, never()).insert(any(SeckillConsistencyAudit.class));
    }

    @Test
    void repair在库存key缺失时按DB重建而不是只读报检查完成() {
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(10));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn(null);

        Result r = service.repairInconsistentData(VOUCHER_ID);

        verify(valueOps).set(RedisConstants.stockKey(VOUCHER_ID), "10");
        Map<?, ?> report = (Map<?, ?>) r.getData();
        assertEquals(Boolean.TRUE, report.get("repaired"));
        assertEquals("REBUILD_STOCK_KEY", report.get("action"));
    }

    // ---------------------------------------------------------------- A4

    @Test
    void sync必须真的把Redis库存写回DB() {
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(0));
        when(voucherFeignClient.resetSeckillStock(VOUCHER_ID, 7)).thenReturn(Result.ok());

        Result r = service.syncStockFromRedisToDb(VOUCHER_ID);

        // 原实现只读 + 打日志 + 返回「库存同步成功」，DB 零写入
        verify(voucherFeignClient).resetSeckillStock(VOUCHER_ID, 7);
        assertTrue(Boolean.TRUE.equals(r.getSuccess()));
        Map<?, ?> report = (Map<?, ?>) r.getData();
        assertEquals(0, report.get("dbStockBefore"));
        assertEquals(7, report.get("dbStockAfter"));
    }

    @Test
    void sync必须留下审计记录() {
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(0));
        when(voucherFeignClient.resetSeckillStock(VOUCHER_ID, 7)).thenReturn(Result.ok());

        service.syncStockFromRedisToDb(VOUCHER_ID);

        verify(auditMapper).insert(any(SeckillConsistencyAudit.class));
    }

    @Test
    void sync写入失败时必须返回失败而不是谎报同步成功() {
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(0));
        when(voucherFeignClient.resetSeckillStock(VOUCHER_ID, 7)).thenReturn(Result.fail("秒杀券不存在"));

        Result r = service.syncStockFromRedisToDb(VOUCHER_ID);

        assertFalse(Boolean.TRUE.equals(r.getSuccess()), "DB 写入失败却返回成功 = 虚假运维信心");
        verify(auditMapper, never()).insert(any(SeckillConsistencyAudit.class));
    }

    @Test
    void sync在已一致时跳过写入且不算修复() {
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(7));

        Result r = service.syncStockFromRedisToDb(VOUCHER_ID);

        assertTrue(Boolean.TRUE.equals(r.getSuccess()));
        verify(voucherFeignClient, never()).resetSeckillStock(anyLong(), any());
        assertEquals("SKIP_ALREADY_EQUAL", ((Map<?, ?>) r.getData()).get("action"));
    }

    // ---------------------------------------------------------------- 待处理

    @Test
    void 待处理列表读取的是SECKILL_PENDING_KEY() {
        when(listOps.range(RedisConstants.SECKILL_PENDING_KEY, 0, -1))
                .thenReturn(List.of("9001:7:1:1700000000000"));

        Result r = service.checkPendingOrders();

        assertTrue(Boolean.TRUE.equals(r.getSuccess()));
        List<?> data = (List<?>) r.getData();
        assertEquals(1, data.size());
        assertEquals(9001L, ((Map<?, ?>) data.get(0)).get("orderId"));
    }

    @Test
    void 待处理列表用真实键而非永不写入的seckillOrderPending() {
        service.checkPendingOrders();

        // 原实现读的 seckill:order:pending 只有 DLQ 消费者写入，而那个消费者永远收不到消息 → 恒为空
        verify(listOps).range(RedisConstants.SECKILL_PENDING_KEY, 0, -1);
    }

    private VoucherOrder order(Long voucherId) {
        VoucherOrder o = new VoucherOrder();
        o.setId(ORDER_ID);
        o.setVoucherId(voucherId);
        return o;
    }

    @Test
    void voucherId缺省时从DB订单反查() {
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(order(VOUCHER_ID));
        noInFlight();
        when(voucherFeignClient.getSeckillStock(VOUCHER_ID)).thenReturn(Result.ok(7));
        when(valueOps.get(RedisConstants.stockKey(VOUCHER_ID))).thenReturn("7");

        assertTrue(Boolean.TRUE.equals(service.checkOrderConsistency(ORDER_ID, null).getSuccess()));
        verify(hashOps).get(eq(RedisConstants.detailKey(VOUCHER_ID)), eq(ORDER_ID.toString()));
    }
}
