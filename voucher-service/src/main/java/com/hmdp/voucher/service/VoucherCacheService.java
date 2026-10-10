package com.hmdp.voucher.service;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_KEY;

/**
 * 券元信息缓存（SPEC-15 P1-2 C2；结构对齐 shop-service 的 {@code ShopCacheService}）
 *
 * <p>只缓存 {@link Voucher} **元信息**（标题、面额、使用规则），供
 * {@code GET /voucher/{id}} 与订单联查使用。
 *
 * <p><b>明确排除库存</b>：秒杀库存的读取路径是
 * {@code VoucherServiceImpl#getSeckillStock}（对账用的强一致读），
 * 本类与它没有任何交集 —— 给库存加缓存会直接破坏 SPEC-13 §2.2 的对账等式。
 */
@Service
public class VoucherCacheService {

    private final MultiLevelCache<Voucher> cache;
    private final VoucherMapper voucherMapper;

    public VoucherCacheService(MultiLevelCacheFactory cacheFactory, VoucherMapper voucherMapper) {
        this.cache = cacheFactory.create("voucher", Voucher.class);
        this.voucherMapper = voucherMapper;
    }

    /**
     * 读取券元信息，未命中则回源 DB 并回填缓存。
     *
     * @return 券实体；不存在时返回 {@code null}（同时留下空值标记防穿透）
     */
    public Voucher getById(Long id) {
        return cache.get(CACHE_VOUCHER_KEY + id, () -> voucherMapper.selectById(id));
    }

    /** 写路径失效：券新增/变更后调用，打断可能已存在的空值标记或旧值 */
    public void evict(Long id) {
        cache.evict(CACHE_VOUCHER_KEY + id);
    }
}
