package com.hmdp.shop.service;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopCacheServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ShopMapper shopMapper;

    private ShopCacheService shopCacheService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        shopCacheService = new ShopCacheService(stringRedisTemplate, shopMapper);
    }

    /** A1：空值标记的 TTL 必须是 60 秒级，而不是 30 分钟 */
    @Test
    void 未命中且DB无此id_写入短TTL空值标记并返回null() {
        when(valueOperations.get(CACHE_SHOP_KEY + 99999L)).thenReturn(null);
        when(shopMapper.selectById(99999L)).thenReturn(null);

        Shop result = shopCacheService.getShop(99999L);

        assertNull(result);
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq(CACHE_SHOP_KEY + 99999L), eq(""), ttl.capture(), eq(TimeUnit.SECONDS));
        assertTrue(ttl.getValue() <= 60L, "空值 TTL 必须 ≤60 秒，实际 " + ttl.getValue());
    }

    /** 命中真实缓存：不回查 DB */
    @Test
    void 命中缓存_返回实体且不查库() {
        Shop cached = new Shop().setId(1L).setName("缓存中的店");
        when(valueOperations.get(CACHE_SHOP_KEY + 1L)).thenReturn(JSONUtil.toJsonStr(cached));

        Shop result = shopCacheService.getShop(1L);

        assertNotNull(result);
        assertEquals("缓存中的店", result.getName());
        verifyNoInteractions(shopMapper);
    }

    /** 命中空值标记：防穿透，不回查 DB */
    @Test
    void 命中空值标记_返回null且不查库() {
        when(valueOperations.get(CACHE_SHOP_KEY + 5L)).thenReturn("");

        Shop result = shopCacheService.getShop(5L);

        assertNull(result);
        verifyNoInteractions(shopMapper);
    }

    /** 未命中且 DB 有值：回填缓存并返回 */
    @Test
    void 未命中且DB有值_回填并返回() {
        Shop dbShop = new Shop().setId(7L).setName("库里的店");
        when(valueOperations.get(CACHE_SHOP_KEY + 7L)).thenReturn(null);
        when(shopMapper.selectById(7L)).thenReturn(dbShop);

        Shop result = shopCacheService.getShop(7L);

        assertNotNull(result);
        assertEquals("库里的店", result.getName());
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq(CACHE_SHOP_KEY + 7L), anyString(), ttl.capture(), eq(TimeUnit.SECONDS));
        assertTrue(ttl.getValue() >= CACHE_TTL_BASE_SECONDS
                        && ttl.getValue() < CACHE_TTL_BASE_SECONDS + CACHE_TTL_JITTER_SECONDS,
                "TTL 越界: " + ttl.getValue());
    }

    /** A8：100 次写入的 TTL 必须有抖动（不全相同）且在区间内 */
    @Test
    void 写入TTL具备抖动() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(shopMapper.selectById(any()))
                .thenAnswer(inv -> new Shop().setId(((Number) inv.getArgument(0)).longValue()));

        for (long id = 1; id <= 100; id++) {
            shopCacheService.getShop(id);
        }

        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations, times(100)).set(anyString(), anyString(), ttl.capture(), eq(TimeUnit.SECONDS));
        List<Long> ttls = ttl.getAllValues();
        assertEquals(100, ttls.size());
        for (Long v : ttls) {
            assertTrue(v >= CACHE_TTL_BASE_SECONDS && v < CACHE_TTL_BASE_SECONDS + CACHE_TTL_JITTER_SECONDS,
                    "TTL 越界: " + v);
        }
        assertTrue(ttls.stream().distinct().count() > 1, "TTL 无抖动嫌疑：100 次写入取值全相同");
    }

    @Test
    void evict_删除对应key() {
        shopCacheService.evict(42L);

        verify(stringRedisTemplate).delete(CACHE_SHOP_KEY + 42L);
    }
}
