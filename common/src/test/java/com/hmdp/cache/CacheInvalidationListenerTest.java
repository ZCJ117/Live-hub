package com.hmdp.cache;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 订阅端接线的覆盖边界：本类锁定"消息体 → UTF-8 解码 → 原样转交注册表"这一层。
 *
 * <p>用 spy 行为验证而非断言注册表 size：注册在 {@code onMessage} 之前就完成、
 * {@code evictLocal} 又不移除注册项 ⇒ size 无论如何都成立（恒真断言），
 * 根本锁不住本类要保护的契约。
 *
 * <p>L1 真的被清由 Task 1 的
 * {@code MultiLevelCacheTest.收到广播只清本地L1不动Redis} 与本计划的 e2e 阶段② 共同证明，
 * 不要据本类认为订阅链路已被完整覆盖。
 */
class CacheInvalidationListenerTest {

    /** 收到广播消息 → 把解码后的 key 原样转交注册表清本地 L1（消息体是 Redis 全键） */
    @Test
    void 收到消息后清理对应L1() {
        LocalCacheRegistry registry = spy(new LocalCacheRegistry());
        MultiLevelCacheFactory factory = new MultiLevelCacheFactory(
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(CacheInvalidationPublisher.class), registry, properties());
        String key = CACHE_SHOP_KEY + 1L;
        MultiLevelCache<Shop> cache = factory.create("shop", Shop.class);
        cache.invalidateLocal(key);   // 空 no-op，贴近真实调用链（注册表已填充）

        assertDoesNotThrow(() ->
                new CacheInvalidationListener(registry).onMessage(message("cache:shop:1"), null));

        // 锁住「UTF-8 解码后的 key 被原样转交」——这正是本类存在的理由
        verify(registry).evictLocal("cache:shop:1");
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
