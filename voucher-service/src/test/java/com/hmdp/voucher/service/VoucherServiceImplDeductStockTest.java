package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.voucher.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplDeductStockTest {

    /** MyBatis-Plus 的 update() 返回链式 wrapper，用 deep stubs 才能 stub 到链尾的 update() */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private ISeckillVoucherService seckillVoucherService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private SetOperations<String, String> setOperations;
    @InjectMocks private VoucherServiceImpl voucherService;

    /**
     * 把 DB 条件更新（stock = stock - 1 WHERE voucher_id = ? AND stock > 0）的结果固定住。
     *
     * <p>deep stubs 只对返回接口/抽象类型生成 mock；MyBatis-Plus 的
     * {@code update()} 返回的是具体类 {@code UpdateChainWrapper}，链式调用会拿到
     * 未 mock 的真对象并在 {@code setSql} 处 NPE。这里显式 mock 该 wrapper，
     * 断言仍只针对 deductStock 的行为。
     */
    private void dbUpdateReturns(boolean result) {
        UpdateChainWrapper<SeckillVoucher> wrapper = mock(UpdateChainWrapper.class);
        when(seckillVoucherService.update()).thenReturn(wrapper);
        when(wrapper.setSql(anyString())).thenReturn(wrapper);
        when(wrapper.eq(anyString(), any())).thenReturn(wrapper);
        when(wrapper.gt(anyString(), any())).thenReturn(wrapper);
        when(wrapper.update()).thenReturn(result);
    }

    @Test
    void 首次扣减成功_写幂等键且不再操作库存key() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(1L);
        dbUpdateReturns(true);

        Result r = voucherService.deductStock(1L, 9001L);

        assertTrue(r.getSuccess());
        // 关键断言（SPEC-03 A7）：不再 DECR/INCR seckill:stock:{id}
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void 重复调用直接返回成功_不再扣减DB() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(0L);

        Result r = voucherService.deductStock(1L, 9001L);

        assertTrue(r.getSuccess());
        verifyNoInteractions(seckillVoucherService);
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void DB扣减失败时返回库存不足_并放开幂等标记() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(1L);
        dbUpdateReturns(false);

        Result r = voucherService.deductStock(1L, 9001L);

        assertFalse(r.getSuccess());
        assertEquals("库存不足", r.getErrorMsg());
        // 未扣成功必须放开标记，否则该订单被永久误标为"已扣"
        verify(setOperations).remove("seckill:deduct:1", "9001");
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void deductStock必须带事务() throws Exception {
        Method m = VoucherServiceImpl.class.getMethod("deductStock", Long.class, Long.class);
        assertNotNull(m.getAnnotation(Transactional.class),
                "SPEC-03 §1.8：两步扣减必须原子，缺少 @Transactional");
    }
}
