package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SETNX 的缓存重建锁（SPEC-15 P1-2 §2.2）
 *
 * <p><b>为什么不是 Redisson tryLock</b>：SPEC-15 §2.2 原文写的是 Redisson，
 * 但 {@code MultiLevelCache} 的构造链只有 {@code StringRedisTemplate}，
 * 而 {@code shop-service} 已经使用本组件却没有 {@code hmdp.redisson.enabled=true}——
 * 按字面做会让 shop / voucher 两个服务都被迫多装配一个 Redisson 连接池。
 * 回源互斥不需要可重入、不需要看门狗续期、不需要排队，SETNX + 比对释放已完全够用。
 *
 * <p><b>fail-open</b>：Redis 抖动时 {@link #tryLock} 返回一个令牌（fail-open），
 * 语义是「允许回源」而非「已持有互斥」，调用方退化为无锁回源
 * （即本改动之前的行为）。缓存组件故障不该把业务回源变成失败（设计文档 §7）。
 */
@Slf4j
public class RedisCacheRebuildLock implements CacheRebuildLock {

    /** 锁键前缀：与限流、订单锁隔离，排障时一眼可辨 */
    static final String LOCK_KEY_PREFIX = "lock:cache:rebuild:";

    /**
     * 锁 TTL（秒）。
     *
     * <p>必须显著大于一次 DB 回源耗时（否则锁在回源中途过期，互斥失效），
     * 又不能长到持锁者崩溃后长时间阻塞重建。
     */
    static final long LOCK_TTL_SECONDS = 3L;

    /**
     * 释放脚本：比对 value 再删，避免误删他人重新抢到的锁。
     *
     * <p>不能用裸 {@code DEL}：若本线程持锁期间回源超过 TTL，锁已自动过期并被他人抢到，
     * 裸 DEL 会把**别人的锁**删掉，互斥当场失效 —— 这正是"锁误删"的经典形态。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end");
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RedisCacheRebuildLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @implNote 令牌是**返回值**而不是实例字段。曾经写成
     * {@code Map<String,String> heldTokens}（按 key 索引），在多线程下有如下时序问题：
     * <pre>
     * T0   A: SETNX(L,tokA)=true  heldTokens[key]=tokA，开始回源
     * T0+  B: SETNX(L,tokB)=false → 未拿到，进入等待重读
     * T3   L 因 TTL 过期消失（A 回源超过 3s，DB 抖动时完全现实）
     * T3+  B: 重试 SETNX(L,tokB)=true → heldTokens[key]=tokB（覆盖 tokA）
     * T4   A: unlock(key) 取到的是 tokB → 比对通过 → 删掉了 B 的锁
     * </pre>
     * 后果是紧接着的 C 能立刻抢锁并与 B 并发回源：互斥被削弱到近似无锁，
     * 而"N 并发同 key → loader 只调用 1 次"（SPEC-15 验收 2）正是本类存在的全部意义。
     * 把令牌交给调用方持有后，A 的 unlock 携带 tokA、比对失败返回 0，不会误删 B 的锁。
     */
    @Override
    public String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY_PREFIX + key, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            // Redis 抖动：无法互斥时退化为"人人可回源"（本改动之前的行为），
            // 而不是让回源失败——缓存组件故障不该阻断业务（设计文档 §7）。
            //
            // 注意语义：这里返回的令牌代表「允许回源」而非「已持有互斥」，
            // 调用方**不能**把它当成互斥保证。
            log.warn("缓存重建锁获取异常，降级为无锁回源。key={}", key, e);
            return token;
        }
    }

    @Override
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(LOCK_KEY_PREFIX + key), token);
        } catch (Exception e) {
            // 释放失败不阻断读路径：TTL 会自动兜底
            log.warn("缓存重建锁释放失败（TTL 会兜底）。key={}", key, e);
        }
    }
}
