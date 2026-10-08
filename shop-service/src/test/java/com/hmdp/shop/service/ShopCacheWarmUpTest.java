package com.hmdp.shop.service;

import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopCacheWarmUpTest {

    /** A3 机制：run() 不得等待预热完成——慢查询也不能阻塞启动路径 */
    @Test
    void 启动路径不阻塞在预热上() throws Exception {
        ShopMapper shopMapper = mock(ShopMapper.class);
        CountDownLatch warmupStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(shopMapper.selectList(any())).thenAnswer(inv -> {
            warmupStarted.countDown();
            release.await(5, TimeUnit.SECONDS);   // 模拟慢查询
            return List.of();
        });

        ShopCacheWarmUp warmUp = new ShopCacheWarmUp(shopMapper, mock(ShopCacheService.class));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> warmUp.run(null));
            assertTrue(warmupStarted.await(5, TimeUnit.SECONDS), "预热应在独立线程中启动");
        } finally {
            release.countDown();
            warmUp.shutdown();
        }
    }

    /** A4 机制：预热必须经 ShopCacheService bean 调用，否则缓存写入不生效 */
    @Test
    void 预热经ShopCacheServiceBean逐个落缓存() {
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopCacheService shopCacheService = mock(ShopCacheService.class);
        when(shopMapper.selectList(any())).thenReturn(List.of(
                new Shop().setId(1L), new Shop().setId(2L)));

        ShopCacheWarmUp warmUp = new ShopCacheWarmUp(shopMapper, shopCacheService);
        try {
            warmUp.warmUpPopularShops();

            verify(shopCacheService).getShop(1L);
            verify(shopCacheService).getShop(2L);
        } finally {
            warmUp.shutdown();
        }
    }
}
