package com.hmdp.shop.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.shop.service.ShopCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopServiceImplQueryByIdTest {

    @Mock
    private ShopCacheService shopCacheService;

    @InjectMocks
    private ShopServiceImpl shopService;

    @Test
    void 缓存返回店铺时返回ok() {
        Shop shop = new Shop().setId(1L).setName("测试店");
        when(shopCacheService.getShop(1L)).thenReturn(shop);

        Result result = shopService.queryById(1L);

        assertTrue(result.getSuccess());
        assertEquals(shop, result.getData());
    }

    /** G1：店铺不存在仍是失败，但该失败不再被写入缓存 */
    @Test
    void 缓存返回null时返回店铺不存在() {
        when(shopCacheService.getShop(99999L)).thenReturn(null);

        Result result = shopService.queryById(99999L);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在!", result.getErrorMsg());
    }

    /** G8：queryById 只读不写——修复前 Result 包装对象会被 @Cacheable 写进 shopCache */
    @Test
    void 查询结果不再写入缓存() {
        when(shopCacheService.getShop(1L)).thenReturn(new Shop().setId(1L));

        shopService.queryById(1L);

        verify(shopCacheService).getShop(1L);
        verifyNoMoreInteractions(shopCacheService);
    }

    @Test
    void 更新店铺后缓存失效() {
        Shop shop = new Shop().setId(3L).setName("改名后的店");
        ShopServiceImpl spy = spy(shopService);
        doReturn(new Shop().setId(3L)).when(spy).getById(3L);
        doReturn(true).when(spy).updateById(shop);

        Result result = spy.update(shop);

        assertTrue(result.getSuccess());
        verify(shopCacheService).evict(3L);
    }

    @Test
    void 无id时不触发缓存失效() {
        Result result = shopService.update(new Shop());

        assertFalse(result.getSuccess());
        verify(shopCacheService, never()).evict(any());
    }

    @Test
    void 更新失败时缓存仍失效() {
        // 与原先 @CacheEvict 的语义一致：方法正常返回即失效
        Shop shop = new Shop().setId(4L);
        ShopServiceImpl spy = spy(shopService);
        doReturn(new Shop().setId(4L)).when(spy).getById(4L);
        doReturn(false).when(spy).updateById(shop);

        spy.update(shop);

        verify(shopCacheService).evict(4L);
    }
}
