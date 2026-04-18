package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 缓存客户端工具类
 * 
 * 提供通用的缓存模式实现，包括：
 * 1. 缓存穿透防护（Cache Penetration Protection）
 * 2. 缓存击穿防护（Cache Breakdown Protection）
 * 3. 逻辑过期（Logical Expiration）
 * 4. 互斥锁重建（Mutex Lock Rebuilding）
 * 
 * 设计模式：
 * - 查询模板模式：通过函数式接口封装数据库查询逻辑
 * - 降级策略：缓存失效时使用数据库查询作为fallback
 * - 异步重建：热点数据过期后异步更新缓存
 * 
 * 使用场景：
 * - 高并发查询场景下的缓存优化
 * - 防止恶意请求导致的缓存穿透
 * - 热点数据失效时的平滑过渡
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透防护查询方法
     * 
     * 解决缓存穿透问题的经典方案：当查询数据不存在时，缓存空值防止重复查询数据库。
     * 流程：
     * 1. 查询Redis缓存
     * 2. 缓存存在且有效 → 直接返回
     * 3. 缓存存在但为空值 → 返回null（防止穿透）
     * 4. 缓存不存在 → 查询数据库
     * 5. 数据库存在 → 写入缓存并返回
     * 6. 数据库不存在 → 缓存空值并返回null
     * 
     * @param keyPrefix 缓存键前缀
     * @param id 查询ID
     * @param type 返回类型
     * @param dbFallback 数据库查询函数（降级策略）
     * @param time 缓存时间
     * @param unit 时间单位
     * @return 查询结果，可能为null
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                        Function<ID,R> dbFallback,Long time,TimeUnit unit){

        String key = keyPrefix + id;
        //1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，直接返回
            return JSONUtil.toBean(json,type);
        }
        //因为如果为null则需要查询数据库，不为null且上一个判断为false（即有空值）的情况下才说明有空命中
        //判断是否命中的是否是空值
        if(json != null){
            //返回错误信息
            return null;
        }

        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在 写入redis
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }



    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期缓存查询方法
     * 
     * 解决缓存击穿问题的方案：缓存数据设置逻辑过期时间，过期后异步重建。
     * 流程：
     * 1. 查询Redis缓存
     * 2. 缓存不存在 → 返回null（需要调用方处理）
     * 3. 缓存存在 → 解析逻辑过期时间
     * 4. 未过期 → 直接返回缓存数据
     * 5. 已过期 → 获取互斥锁，异步重建缓存，返回旧数据
     * 
     * 特点：
     * - 保证高并发下热点数据不会同时失效
     * - 异步重建避免阻塞用户请求
     * - 返回旧数据保证用户体验连续性
     * 
     * @param keyPrefix 缓存键前缀
     * @param id 查询ID
     * @param type 返回类型
     * @param dbFallback 数据库查询函数
     * @param time 逻辑过期时间
     * @param unit 时间单位
     * @return 查询结果，可能为null或过期数据
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }


}
