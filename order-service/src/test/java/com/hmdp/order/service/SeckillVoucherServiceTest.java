package com.hmdp.order.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.order.service.impl.VoucherOrderServiceImpl;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
}
