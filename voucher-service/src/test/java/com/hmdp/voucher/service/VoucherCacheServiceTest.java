package com.hmdp.voucher.service;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 券元信息二级缓存（SPEC-15 P1-2 C2）
 *
 * <p>断言的重点是"键拼法与 SecondLevelCache 契约一致"与"写路径会失效"——
 * 这两点出错时业务表现正常（只是缓存永不命中 / 读到旧值），无任何日志信号。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherCacheServiceTest {

    @Mock private MultiLevelCacheFactory cacheFactory;
    @Mock private MultiLevelCache<Voucher> cache;
    @Mock private VoucherMapper voucherMapper;

    private VoucherCacheService service;

    @BeforeEach
    void setUp() {
        // 显式类型见证：Mockito 默认会把 create 的返回推断为 MultiLevelCache<Object>，
        // 与 @Mock MultiLevelCache<Voucher> 不兼容导致 thenReturn 编译失败
        when(cacheFactory.<Voucher>create(eq("voucher"), eq(Voucher.class))).thenReturn(cache);
        service = new VoucherCacheService(cacheFactory, voucherMapper);
    }

    @Test
    void getById按统一键前缀走缓存() {
        Voucher expected = new Voucher().setId(12L);
        when(cache.get(eq(CACHE_VOUCHER_KEY + 12L), any())).thenReturn(expected);

        assertSame(expected, service.getById(12L));
        verify(voucherMapper, never()).selectById(anyLong());
    }

    @Test
    void evict使用同一个键() {
        service.evict(12L);

        verify(cache).evict(CACHE_VOUCHER_KEY + 12L);
    }
}
