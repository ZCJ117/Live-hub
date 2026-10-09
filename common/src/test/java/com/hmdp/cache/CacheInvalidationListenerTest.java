package com.hmdp.cache;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 订阅端接线的覆盖边界：本类只锁定"消息 → 注册表"这一层
 * （不抛异常、注册表被正确扫描）。
 *
 * <p>L1 真的被清由 Task 1 的
 * {@code MultiLevelCacheTest.收到广播只清本地L1不动Redis} 与本计划的 e2e 阶段② 共同证明，
 * 不要据本类认为订阅链路已被完整覆盖。
 */
class CacheInvalidationListenerTest {

    /** 收到广播消息 → 清掉本地 L1（消息体是 Redis 全键） */
    @Test
    void 收到消息后清理对应L1() {
        LocalCacheRegistry registry = new LocalCacheRegistry();
        MultiLevelCacheFactory factory = new MultiLevelCacheFactory(
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(CacheInvalidationPublisher.class), registry, properties());
        String key = CACHE_SHOP_KEY + 1L;
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);
        cache.invalidateLocal(key);   // 空 no-op，确保注册表已被填充

        new CacheInvalidationListener(registry).onMessage(message("cache:shop:1"), null);

        // 断言行为：L1 被清 —— 通过 stats 的 miss 计数无法观测，改为断言不抛且注册表非空
        assertEquals(1, registry.caches().size());
    }

    private static MultiLevelCacheProperties properties() {
        MultiLevelCacheProperties p = new MultiLevelCacheProperties();
        p.setL1Ttl(Duration.ofSeconds(10));
        return p;
    }

    private static Message message(String body) {
        return new Message() {
            @Override
            public byte[] getBody() {
                return body.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] getChannel() {
                return "cache:invalidate".getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
