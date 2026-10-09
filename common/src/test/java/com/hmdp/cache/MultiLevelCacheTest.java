package com.hmdp.cache;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
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

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiLevelCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private CacheInvalidationPublisher publisher;

    private LocalCacheRegistry registry;
    private MultiLevelCacheProperties properties;
    private MultiLevelCacheFactory factory;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new LocalCacheRegistry();
        factory = new MultiLevelCacheFactory(redisTemplate, publisher, registry, defaultProperties());
    }

    private static MultiLevelCacheProperties defaultProperties() {
        MultiLevelCacheProperties p = new MultiLevelCacheProperties();
        p.setL1MaxSize(1000);
        p.setL1Ttl(Duration.ofSeconds(10));
        p.setStatsLogInterval(Duration.ofSeconds(60));
        return p;
    }

    /** U1：L1 命中时不再访问 Redis（这是本地缓存的全部收益） */
    @Test
    void L1命中时不再访问Redis() {
        String key = CACHE_SHOP_KEY + 1L;
        when(valueOperations.get(key)).thenReturn(JSONUtil.toJsonStr(new Shop().setId(1L).setName("缓存中的店")));
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        Shop first = cache.get(key, () -> {
            throw new AssertionError("L2 命中了，不应回源");
        });
        Shop second = cache.get(key, () -> {
            throw new AssertionError("L1 命中了，不应回源");
        });

        assertEquals("缓存中的店", first.getName());
        assertEquals("缓存中的店", second.getName());
        verify(valueOperations, times(1)).get(key);
    }

    /** U2：L1/L2 均未命中 → 回源恰好一次 → 回填 L2（TTL 带抖动）与 L1 */
    @Test
    void 两级均未命中时回源并回填() {
        String key = CACHE_SHOP_KEY + 7L;
        when(valueOperations.get(key)).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        Shop result = cache.get(key, () -> {
            loads.incrementAndGet();
            return new Shop().setId(7L).setName("库里的店");
        });

        assertEquals("库里的店", result.getName());
        assertEquals(1, loads.get());
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq(key), anyString(), ttl.capture(), eq(TimeUnit.SECONDS));
        // 字面量边界是刻意为之：这两个数字是 SPEC-05 冻结的契约（1800 基础 + [0,300) 抖动）。
        // 若断言引用 CACHE_TTL_BASE_SECONDS 等同款常量，常量被改动时用例不会红，等于不设防。
        assertTrue(ttl.getValue() >= 1800L && ttl.getValue() < 2100L, "TTL 越界: " + ttl.getValue());

        // 第二次必须由 L1 命中，不再调 loader
        assertEquals("库里的店", cache.get(key, () -> {
            throw new AssertionError("L1 未生效");
        }).getName());
        assertEquals(1, loads.get());
    }

    /** U3：loader 返回 null → 写 60 秒空值标记，且标记不进 L1（下次仍读 Redis） */
    @Test
    void loader返回null时写短TTL空值标记且不写L1() {
        String key = CACHE_SHOP_KEY + 99999L;
        when(valueOperations.get(key)).thenReturn(null, "");
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        assertNull(cache.get(key, () -> null));
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq(key), eq(""), ttl.capture(), eq(TimeUnit.SECONDS));
        assertEquals(60L, ttl.getValue(), "空值标记 TTL 必须恰好 60 秒（SPEC-05 G5 冻结的负缓存窗口）");

        // 第二次仍打到 Redis（证明标记没进 L1），且不再回源
        assertNull(cache.get(key, () -> {
            throw new AssertionError("空值标记未生效");
        }));
        verify(valueOperations, times(2)).get(key);
    }

    /** U4：泛型 List 值可正确往返（hutool 的 java.lang.reflect.Type 反序列化） */
    @Test
    void 泛型List值可正确往返() {
        Type listType = new TypeReference<List<ShopType>>() {}.getType();
        when(valueOperations.get(SHOP_LIST_KEY)).thenReturn(null);
        MultiLevelCache<List<ShopType>> cache = factory.create("shopTypeList", listType);

        List<ShopType> loaded = cache.get(SHOP_LIST_KEY, () -> List.of(new ShopType().setId(1L).setName("美食")));

        assertEquals(1, loaded.size());
        assertEquals("美食", loaded.get(0).getName());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(SHOP_LIST_KEY), json.capture(), anyLong(), eq(TimeUnit.SECONDS));
        // 写入 L2 的 JSON 必须能按同一 Type 反序列化回来（跨进程一致性的最小保证）
        List<ShopType> fromL2 = JSONUtil.toBean(json.getValue(), listType, false);
        assertEquals("美食", fromL2.get(0).getName());
    }

    /** U5：evict 删 Redis + 清本实例 L1 + 广播一次 */
    @Test
    void evict删除两级缓存并广播() {
        String key = CACHE_SHOP_KEY + 42L;
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);
        when(valueOperations.get(key)).thenReturn(JSONUtil.toJsonStr(new Shop().setId(42L).setName("旧名")));
        cache.get(key, () -> null);   // 先让 L1 有值
        when(valueOperations.get(key)).thenReturn(null);

        cache.evict(key);

        verify(redisTemplate).delete(key);
        verify(publisher).publish(key);
        AtomicInteger loads = new AtomicInteger();
        Shop after = cache.get(key, () -> {
            loads.incrementAndGet();
            return new Shop().setId(42L).setName("新名");
        });
        assertEquals("新名", after.getName());
        assertEquals(1, loads.get(), "evict 后本实例 L1 应已失效，必须回源");
    }

    /** U6：收到广播只清 L1，不删 Redis、不再广播（否则回环） */
    @Test
    void 收到广播只清本地L1不动Redis() {
        String key = CACHE_SHOP_KEY + 5L;
        when(valueOperations.get(key)).thenReturn(JSONUtil.toJsonStr(new Shop().setId(5L).setName("旧名")));
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);
        cache.get(key, () -> null);
        clearInvocations(redisTemplate, publisher);
        when(valueOperations.get(key)).thenReturn(null);

        registry.evictLocal(key);

        verify(redisTemplate, never()).delete(anyString());
        verify(publisher, never()).publish(anyString());
        AtomicInteger loads = new AtomicInteger();
        cache.get(key, () -> {
            loads.incrementAndGet();
            return new Shop().setId(5L);
        });
        assertEquals(1, loads.get(), "广播后 L1 应已失效");
    }

    /** U7：发布失败不阻断失效，也不影响业务返回 */
    @Test
    void 广播发布失败不影响失效与返回() {
        String key = CACHE_SHOP_KEY + 9L;
        doThrow(new RuntimeException("pub/sub 不可用")).when(publisher).publish(key);
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        assertDoesNotThrow(() -> cache.evict(key));

        verify(redisTemplate).delete(key);
    }

    /** U8：Redis 读取异常降级为未命中并回源（不让缓存故障变成业务 500） */
    @Test
    void Redis读取异常时降级回源() {
        String key = CACHE_SHOP_KEY + 3L;
        when(valueOperations.get(key)).thenThrow(new RuntimeException("Redis 不可用"));
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        Shop shop = assertDoesNotThrow(() -> cache.get(key, () -> new Shop().setId(3L).setName("从库里拿")));

        assertEquals("从库里拿", shop.getName());
    }

    /** U8b：Redis 写入异常不抛出（空值标记写失败时仍返回 null） */
    @Test
    void Redis写入异常时仍正常返回() {
        String key = CACHE_SHOP_KEY + 4L;
        when(valueOperations.get(key)).thenReturn(null);
        doThrow(new RuntimeException("Redis 不可用"))
                .when(valueOperations).set(eq(key), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        assertNull(assertDoesNotThrow(() -> cache.get(key, () -> null)));
    }

    /** U8c：L2 值反序列化失败时按未命中处理并回源（而不是把 null 当结果返回） */
    @Test
    void 反序列化失败时回源() {
        String key = CACHE_SHOP_KEY + 6L;
        when(valueOperations.get(key)).thenReturn("不是合法 JSON");
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        Shop shop = cache.get(key, () -> new Shop().setId(6L).setName("从库里拿"));

        assertEquals("从库里拿", shop.getName());
    }

    /** U9：stats 命中/未命中计数（getIfPresent 语义：miss 计一次、hit 计一次） */
    @Test
    void stats记录命中与未命中() {
        String key = CACHE_SHOP_KEY + 11L;
        when(valueOperations.get(key)).thenReturn(JSONUtil.toJsonStr(new Shop().setId(11L)));
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);

        cache.get(key, () -> null);   // L1 未命中
        cache.get(key, () -> null);   // L1 命中

        CacheStats stats = cache.stats();
        assertEquals(1, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(0.5, stats.hitRate());
    }

    /**
     * U10：L1 容量上限生效。
     *
     * <p>注意：Caffeine 的按容量淘汰是异步维护的，写入 100 条后 estimatedSize() 仍可能报 100，
     * 需要一次后续访问才会收敛到 10（本机实测：100 → getIfPresent → 10）。故先读一次再断言。
     */
    @Test
    void L1容量上限生效() {
        MultiLevelCacheProperties small = defaultProperties();
        small.setL1MaxSize(10);
        MultiLevelCacheFactory smallFactory = new MultiLevelCacheFactory(redisTemplate, publisher, registry, small);
        when(valueOperations.get(anyString())).thenReturn(null);
        MultiLevelCache<Shop> cache = smallFactory.create("small", Shop.class);

        for (int i = 0; i < 100; i++) {
            int id = i;
            cache.get("cache:shop:" + id, () -> new Shop().setId((long) id));
        }
        // 触发一次**命中**读：Caffeine 的按容量淘汰是异步维护的，需要一次访问才收敛
        // （实测：100 条写入后 estimatedSize 仍报 100，一次 getIfPresent 后降为 10）
        cache.get("cache:shop:sentinel", () -> new Shop().setId(-1L));
        cache.get("cache:shop:sentinel", () -> {
            throw new AssertionError("哨兵值未留在 L1，说明读没命中");
        });

        assertTrue(cache.estimatedSize() <= 10, "L1 容量上限失效: " + cache.estimatedSize());
    }

    /** U11：L1 兜底 TTL 生效（广播丢失时的最大脏读窗口） */
    @Test
    void L1兜底TTL到期后回源() throws InterruptedException {
        MultiLevelCacheProperties shortTtl = defaultProperties();
        shortTtl.setL1Ttl(Duration.ofMillis(150));
        MultiLevelCacheFactory shortFactory = new MultiLevelCacheFactory(redisTemplate, publisher, registry, shortTtl);
        String key = CACHE_SHOP_KEY + 8L;
        when(valueOperations.get(key)).thenReturn(JSONUtil.toJsonStr(new Shop().setId(8L).setName("旧名")));
        MultiLevelCache<Shop> cache = shortFactory.create("shortTtl", Shop.class);

        cache.get(key, () -> null);
        Thread.sleep(300);
        when(valueOperations.get(key)).thenReturn(null);

        Shop refreshed = cache.get(key, () -> new Shop().setId(8L).setName("新名"));

        assertEquals("新名", refreshed.getName(), "L1 TTL 到期后必须回源");
    }
}
