package com.hmdp.shop.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商铺实体缓存（SPEC-05 §5.1）。
 *
 * <p>读路径显式实现 L2(Redis) → DB → 回填，缓存 {@link Shop} 本体而非 {@code Result}
 * 包装对象——失败分支因此不可能被写入缓存（G1）。空值走 60 秒短 TTL 防穿透（G5），
 * 正常值 TTL 带抖动避免同刻集体失效（G7）。
 *
 * <p>注入 {@link ShopMapper} 而非 {@code IShopService}，以避开与 {@code ShopServiceImpl}
 * 的循环依赖。
 */
@Service
public class ShopCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopMapper shopMapper;

    public ShopCacheService(StringRedisTemplate stringRedisTemplate, ShopMapper shopMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.shopMapper = shopMapper;
    }

    /**
     * 读取商铺，未命中则回源 DB 并回填缓存。
     *
     * @return 店铺实体；店铺不存在时返回 {@code null}（同时留下空值标记防穿透）
     */
    public Shop getShop(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 命中真实缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, Shop.class);
        }
        // 2. 命中空值标记：直接返回，不再穿透到 DB
        if (json != null) {
            return null;
        }

        // 3. 未命中，回源 DB
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }

        // 4. 回填，TTL 带抖动
        long ttl = CACHE_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.SECONDS);
        return shop;
    }

    /**
     * 失效指定商铺的缓存。写路径（update / saveShop）调用，用于打断可能已存在的
     * 空值标记或旧值，保证变更后立即可见。
     */
    public void evict(Long id) {
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    }
}
