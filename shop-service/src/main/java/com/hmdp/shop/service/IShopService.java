package com.hmdp.shop.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    /**
     * 手动触发缓存预热
     */
    Result warmUpCache();

    /**
     * 预热指定店铺的缓存
     */
    Result warmUpShopCache(List<Long> shopIds);

    /**
     * 获取缓存预热状态
     */
    Result getCacheWarmupStatus();

}