package com.hmdp.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.redis.core.StringRedisTemplate;

import static com.hmdp.utils.RedisConstants.CACHE_INVALIDATE_CHANNEL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    /** 发布到约定的频道，消息体是被失效的 Redis 全键 */
    @Test
    void 发布到约定频道且消息体为缓存键() {
        new CacheInvalidationPublisher(redisTemplate).publish("cache:shop:12");

        verify(redisTemplate).convertAndSend(CACHE_INVALIDATE_CHANNEL, (Object) "cache:shop:12");
    }

    /** Redis 异常必须自己吞掉：广播是加速手段而非依赖（设计文档 §7） */
    @Test
    void Redis异常时吞掉不抛出() {
        when(redisTemplate.convertAndSend(anyString(), any()))
                .thenThrow(new RuntimeException("Redis 不可用"));

        assertDoesNotThrow(() -> new CacheInvalidationPublisher(redisTemplate).publish("cache:shop:12"));
    }
}
