package com.hmdp.cache;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * 缓存失效广播的订阅端（二级缓存设计文档 §4.3）。
 *
 * <p>只做一件事：把消息体（Redis 全键）交给 {@link LocalCacheRegistry} 清本地 L1。
 * 清理动作本身不可能外逸异常（异常在注册表内按缓存逐个兜住），
 * 因此不会把 Redis 监听容器带挂。
 */
public class CacheInvalidationListener implements MessageListener {

    private final LocalCacheRegistry registry;

    public CacheInvalidationListener(LocalCacheRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        registry.evictLocal(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
