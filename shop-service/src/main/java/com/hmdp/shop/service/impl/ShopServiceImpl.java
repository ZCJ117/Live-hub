package com.hmdp.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.ShopCacheService;
import com.hmdp.utils.SystemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 店铺服务实现类
 *
 * <p>缓存策略（SPEC-05 修复后）：
 * <ul>
 *   <li>读路径由 {@link ShopCacheService} 显式承载：Redis(L2) → DB → 回填，
 *       缓存 {@code Shop} 本体，带 TTL 抖动与空值短 TTL；</li>
 *   <li>写路径（{@link #update} 与 {@code ShopController#saveShop}）显式失效缓存；</li>
 *   <li>预热由 {@code ShopCacheWarmUp} / {@code ShopGeoIndexWarmUp} 在容器就绪后
 *       异步执行，不占用启动关键路径。</li>
 * </ul>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final Logger logger = LoggerFactory.getLogger(ShopServiceImpl.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopCacheService shopCacheService;

    @Override
    public Result queryById(Long id) {
        Shop shop = shopCacheService.getShop(id);
        if (shop == null) {
            logger.warn("店铺不存在，ID: {}", id);
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        logger.info("开始更新店铺信息，店铺ID: {}", id);

        Shop oldShop = getById(id);
        if (oldShop != null) {
            logger.info("更新前店铺名称: {}", oldShop.getName());
        }

        boolean success = updateById(shop);

        // 与原先 @CacheEvict 的语义保持一致：方法正常返回即失效，
        // 避免旧值或空值标记在 TTL 内继续对外可见（SPEC-05 G1）
        shopCacheService.evict(id);

        if (success) {
            logger.info("数据库更新成功，缓存已失效，店铺ID: {}", id);
        } else {
            logger.error("数据库更新失败，店铺ID: {}", id);
        }
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        logger.info("查询类型为 {} 的店铺，页码: {}, 坐标: ({}, {})");

        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            logger.info("执行无坐标查询");
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            logger.info("查询到 {} 条记录", page.getRecords().size());
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        logger.info("执行地理位置查询，从第 {} 条到第 {} 条", from, end);

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new org.springframework.data.geo.Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            logger.info("未找到附近店铺");
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            logger.info("没有更多店铺数据");
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        logger.info("找到 {} 个附近店铺，ID列表: {}", ids.size(), ids);

        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        logger.info("成功获取 {} 个店铺详细信息", shops.size());

        // 6.返回
        return Result.ok(shops);
    }
}
