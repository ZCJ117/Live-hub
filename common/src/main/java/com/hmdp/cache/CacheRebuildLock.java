package com.hmdp.cache;

/**
 * 缓存回源互斥锁（SPEC-15 P1-2 §2.2）
 *
 * <p>存在意义只有一个：让 {@link MultiLevelCache} 的「N 并发同 key 回源 → loader 只调用 1 次」
 * 可以离线确定性回归。若直接用 {@code StringRedisTemplate} 内联实现，测试只能去 mock
 * {@code SETNX} 的原子语义（Mockito 做不到），这条验收项就只剩人工压测可验。
 */
public interface CacheRebuildLock {

    /**
     * 尝试获取 {@code key} 对应的重建锁。
     *
     * @return true = 已持有锁（调用方**必须**在 finally 中 {@link #unlock}）；
     *         false = 他人持有，调用方应短暂等待后重读缓存
     */
    boolean tryLock(String key);

    /** 释放锁；未持有时为空操作 */
    void unlock(String key);
}
