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
     * @return 非 null = 已获得许可，**必须**把该令牌原样回传给 {@link #unlock(String, String)}；
     *         null = 他人持有，调用方应短暂等待后重读缓存
     */
    String tryLock(String key);

    /**
     * 释放锁。
     *
     * <p>令牌必须由 {@link #tryLock} 返回并由**调用方**持有，而不是由锁实例内部按 key 记账。
     * 后者在"持锁者回源超过 TTL、锁已过期并被他人重抢"的时序下会让先前的持锁者删掉
     * **他人的锁**，把互斥削到近似失效 —— 见实现类的 @implNote。
     *
     * @param token {@link #tryLock} 的返回值；为 null 时为空操作
     */
    void unlock(String key, String token);
}
