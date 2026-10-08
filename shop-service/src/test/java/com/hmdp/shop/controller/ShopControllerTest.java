package com.hmdp.shop.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.ShopCacheService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopControllerTest {

    /** A2 机制：新增商铺后必须打断该 id 可能已存在的空值标记，否则最长 30 分钟不可见 */
    @Test
    void 新增店铺后打断该id的缓存() {
        ShopController controller = new ShopController();
        IShopService shopService = mock(IShopService.class);
        ShopCacheService shopCacheService = mock(ShopCacheService.class);
        controller.shopService = shopService;
        controller.shopCacheService = shopCacheService;
        Shop shop = new Shop().setId(99999L).setName("新店");

        Result result = controller.saveShop(shop);

        verify(shopService).save(shop);
        verify(shopCacheService).evict(99999L);
        assertEquals(99999L, result.getData());
    }
}
