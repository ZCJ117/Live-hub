package com.hmdp.shop.service;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * 商铺实体缓存（SPEC-05 §5.1 契约 + 二级缓存设计文档 §5.1）。
 *
 * <p>读路径由 {@link MultiLevelCache} 承载：L1(Caffeine) → L2(Redis) → DB → 回填，
 * 缓存 {@link Shop} 本体而非 {@code Result} 包装对象，因此失败分支不可能被写入缓存；
 * 空值走 60 秒短 TTL 防穿透；正常值 TTL 带抖动（以上契约见设计文档 §1.1）。
 *
 * <p>公开方法签名未变，故 {@code ShopServiceImpl} / {@code ShopController} 的调用点无需改动。
 */
@Service
public class ShopCacheService {

    private final MultiLevelCache<Shop> cache;
    private final ShopMapper shopMapper;

    public ShopCacheService(MultiLevelCacheFactory cacheFactory, ShopMapper shopMapper) {
        this.cache = cacheFactory.create("shop", Shop.class);
        this.shopMapper = shopMapper;
    }

    /**
     * 读取商铺，未命中则回源 DB 并回填缓存。
     *
     * @return 店铺实体；店铺不存在时返回 {@code null}（同时留下空值标记防穿透）
     */
    public Shop getShop(Long id) {
        return cache.get(CACHE_SHOP_KEY + id, () -> shopMapper.selectById(id));
    }

    /**
     * 失效指定商铺的缓存。写路径（update / saveShop）调用，用于打断可能已存在的
     * 空值标记或旧值，保证变更后立即可见，并广播其他实例清 L1（设计文档 §5.2）。
     */
    public void evict(Long id) {
        cache.evict(CACHE_SHOP_KEY + id);
    }
}
