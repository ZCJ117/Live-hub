package com.hmdp.voucher.service;

import com.hmdp.entity.Voucher;
import com.hmdp.utils.RedisConstants;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.voucher.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplWindowTest {

    /** save(voucher) 最终落到 baseMapper.insert()，必须提供该 mock 否则 NPE */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private VoucherMapper voucherMapper;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private ISeckillVoucherService seckillVoucherService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @InjectMocks private VoucherServiceImpl voucherService;

    /**
     * Mockito 的 {@code @InjectMocks} 不会给 {@code ServiceImpl} 继承来的
     * {@code protected baseMapper} 注入 {@code @Mock}，必须显式反射补齐
     * （同 shop-service ShopTypeServiceImplTest 的既有做法）。
     */
    @BeforeEach
    void injectBaseMapper() {
        ReflectionTestUtils.setField(voucherService, "baseMapper", voucherMapper);
    }

    private Voucher seckillVoucher(LocalDateTime begin, LocalDateTime end) {
        Voucher v = new Voucher();
        v.setId(123L);
        v.setStock(10);
        v.setBeginTime(begin);
        v.setEndTime(end);
        return v;
    }

    @Test
    void 创建秒杀券时写入时间窗Hash_值为epoch毫秒() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 12, 0);

        voucherService.addSeckillVoucher(seckillVoucher(begin, end));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(RedisConstants.windowKey(123L)), captor.capture());

        assertEquals(
                String.valueOf(begin.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
                captor.getValue().get(RedisConstants.SECKILL_WINDOW_FIELD_BEGIN));
        assertEquals(
                String.valueOf(end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
                captor.getValue().get(RedisConstants.SECKILL_WINDOW_FIELD_END));
    }

    @Test
    void 时间窗TTL必须长于活动周期_否则结束后key过期反而放行() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        LocalDateTime end = LocalDateTime.now().plusHours(2);

        voucherService.addSeckillVoucher(seckillVoucher(LocalDateTime.now().minusHours(1), end));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(stringRedisTemplate).expire(eq(RedisConstants.windowKey(123L)), ttl.capture());

        long secondsToEnd = Duration.between(LocalDateTime.now(), end).getSeconds();
        assertTrue(ttl.getValue().getSeconds() > secondsToEnd,
                "TTL(" + ttl.getValue().getSeconds() + "s) 必须大于距结束的 " + secondsToEnd
                        + "s：否则活动结束后 window key 过期，入口按「缺失即放行」反而放行（SPEC-14 §7 M2）");
        assertEquals(secondsToEnd + RedisConstants.SECKILL_WINDOW_RETAIN_HOURS * 3600L,
                ttl.getValue().getSeconds(), 5L);
    }

    @Test
    void 无时间窗的券不写windowKey_保持历史行为() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        voucherService.addSeckillVoucher(seckillVoucher(null, null));

        verify(stringRedisTemplate, never()).opsForHash();
        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }
}
