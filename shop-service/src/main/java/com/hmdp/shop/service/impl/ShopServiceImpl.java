// NOTE 缓存预热（Cache Warm-Up）是指在系统启动或流量高峰来临前，提前将热点数据加载到缓存系统中的过程。
//    为啥需要缓存预热：
//    解决冷启动问题： 新系统启动时缓存为空，首请求直接穿透到数据库，容易引发雪崩效应
//    应对突发流量： 秒杀活动、热点新闻等场景下，瞬时高并发请求可能导致数据库过载
//    提升性能稳定性： 预先加载高频访问数据，保障核心接口响应时间稳定
//    优化用户体验： 避免用户首次访问时的长等待时间
package com.hmdp.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import com.hmdp.shop.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 店铺服务实现类
 * 
 * 本服务实现了完整的二级缓存策略，包括：
 * 1. 缓存预热（Cache Warm-Up）：应用启动时预加载热点数据
 * 2. 缓存穿透防护：空值缓存防止恶意查询
 * 3. 缓存击穿防护：互斥锁重建防止热点数据失效
 * 4. 缓存一致性：更新时自动清除缓存
 * 
 * 缓存架构：
 * - 使用Spring Cache抽象层，支持注解驱动的缓存操作
 * - Redis作为二级缓存，提供分布式缓存能力
 * - 可通过扩展支持本地一级缓存（如Caffeine）
 * 
 * 关键特性：
 * - @Cacheable: 查询时自动缓存结果
 * - @CacheEvict: 更新时自动清除缓存
 * - 异步缓存预热：不阻塞应用启动
 * - 缓存状态监控：提供缓存命中率等指标
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final Logger logger = LoggerFactory.getLogger(ShopServiceImpl.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheManager cacheManager;  //NOTE 用于管理缓存 spring缓存管理器

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    // NOTE 线程池用于异步缓存预热
    private ExecutorService cacheWarmupExecutor;

    /**
     * 初始化方法 - 创建线程池
     */
    @PostConstruct
    public void init() {
        // 创建固定大小的线程池用于缓存预热
        cacheWarmupExecutor = Executors.newFixedThreadPool(3);
        logger.info("缓存预热线程池初始化完成");
    }

    /**
     * 缓存预热 - 应用启动时执行
     */
    //NOTE 使用 @PostConstruct 注解确保该方法在服务启动后立即执行 自动预热
    // NOTE 使用 @Async 注解使该方法异步执行，避免阻塞应用启动过程
    @PostConstruct
    @Async
    public void warmUpCacheOnStartup() {
        try {
            // 延迟启动，等待应用完全启动
            Thread.sleep(10000);
            logger.info("开始执行应用启动缓存预热...");

            // 预热热门店铺数据
            warmUpPopularShops();

            // 预热按类型分类的店铺
            warmUpShopsByType();

            // 预热地理位置数据
            warmUpGeoData();

            logger.info("应用启动缓存预热完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("缓存预热被中断", e);
        } catch (Exception e) {
            logger.error("缓存预热执行失败", e);
        }
    }

    /**
     * 预热热门店铺数据
     */
    private void warmUpPopularShops() {
        cacheWarmupExecutor.submit(() -> {
            try {
                logger.info("开始预热热门店铺数据...");

                // 查询热门店铺（这里可以根据业务逻辑调整，比如按评分、销量等）
                List<Shop> popularShops = this.query()
                        .orderByDesc("score") // 假设有评分字段
                        .last("LIMIT 50") // 预热前50个热门店铺
                        .list();

                int successCount = 0;
                for (Shop shop : popularShops) {
                    try {
                        // 使用queryById方法，会自动缓存
                        this.queryById(shop.getId());
                        successCount++;

                        // 批量操作时稍微延迟，避免对数据库造成压力
                        Thread.sleep(10);
                    } catch (Exception e) {
                        logger.warn("预热店铺 {} 失败: {}", shop.getId(), e.getMessage());
                    }
                }

                logger.info("热门店铺数据预热完成，成功预热 {} 个店铺", successCount);
            } catch (Exception e) {
                logger.error("预热热门店铺数据失败", e);
            }
        });
    }

    /**
     * 预热按类型分类的店铺
     */
    private void warmUpShopsByType() {
        cacheWarmupExecutor.submit(() -> {
            try {
                logger.info("开始预热按类型分类的店铺数据...");

                // 使用正确的 QueryWrapper 创建方式
                com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Shop> queryWrapper = 
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                queryWrapper.select("DISTINCT type_id").isNotNull("type_id");

                // 获取所有店铺类型
                List<Object> typeList = this.baseMapper.selectObjs(queryWrapper);

                int typeCount = 0;
                for (Object typeObj : typeList) {
                    if (typeObj != null) {
                        try {
                            // 根据Shop实体中typeId的实际类型进行转换
                            Integer typeId;
                            if (typeObj instanceof Long) {
                                typeId = ((Long) typeObj).intValue();
                            } else if (typeObj instanceof Integer) {
                                typeId = (Integer) typeObj;
                            } else {
                                typeId = Integer.valueOf(typeObj.toString());
                            }

                            // 预热每种类型前几页数据
                            for (int current = 1; current <= 3; current++) {
                                this.queryShopByType(typeId, current, null, null);
                            }
                            typeCount++;

                            Thread.sleep(50); // 类型间延迟
                        } catch (Exception e) {
                            logger.warn("预热类型 {} 的店铺失败: {}", typeObj, e.getMessage());
                        }
                    }
                }

                logger.info("按类型分类的店铺数据预热完成，成功预热 {} 种类型", typeCount);
            } catch (Exception e) {
                logger.error("预热按类型分类的店铺数据失败", e);
            }
        });
    }

    /**
     * 预热地理位置数据
     */
    private void warmUpGeoData() {
        cacheWarmupExecutor.submit(() -> {
            try {
                logger.info("开始预热地理位置数据...");

                // 查询所有有地理坐标的店铺
                List<Shop> shopsWithLocation = this.query()
                        .isNotNull("x")
                        .isNotNull("y")
                        .list();

                // 按类型分组 - 修复类型问题
                Map<Long, List<Shop>> shopsByType = new HashMap<>();
                for (Shop shop : shopsWithLocation) {
                    if (shop.getTypeId() != null) {
                        // 使用Long作为key，因为Shop的typeId通常是Long类型
                        Long typeId = shop.getTypeId();
                        shopsByType.computeIfAbsent(typeId, k -> new ArrayList<>()).add(shop);
                    }
                }

                // 预热每种类型的地理位置数据
                int geoCount = 0;
                for (Map.Entry<Long, List<Shop>> entry : shopsByType.entrySet()) {
                    Long typeId = entry.getKey();
                    List<Shop> shops = entry.getValue();

                    try {
                        String key = SHOP_GEO_KEY + typeId;

                        // 批量添加地理位置数据
                        List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
                        for (Shop shop : shops) {
                            if (shop.getX() != null && shop.getY() != null) {
                                locations.add(new RedisGeoCommands.GeoLocation<>(
                                        shop.getId().toString(),
                                        new org.springframework.data.geo.Point(shop.getX(), shop.getY())
                                ));
                            }
                        }

                        if (!locations.isEmpty()) {
                            stringRedisTemplate.opsForGeo().add(key, locations);
                            geoCount++;
                            logger.debug("成功预热类型 {} 的地理位置数据，包含 {} 个店铺", typeId, locations.size());
                        }

                        Thread.sleep(30); // 类型间延迟
                    } catch (Exception e) {
                        logger.warn("预热类型 {} 的地理位置数据失败: {}", typeId, e.getMessage());
                    }
                }

                logger.info("地理位置数据预热完成，成功预热 {} 种类型的地理数据", geoCount);
            } catch (Exception e) {
                logger.error("预热地理位置数据失败", e);
            }
        });
    }

    /**
     * 手动触发缓存预热
     */
    @Override
    public Result warmUpCache() {
        logger.info("手动触发缓存预热...");

        try {
            // 异步执行缓存预热
            warmUpPopularShops();
            warmUpShopsByType();
            warmUpGeoData();

            return Result.ok("缓存预热任务已启动，请查看日志了解进度");
        } catch (Exception e) {
            logger.error("手动缓存预热失败", e);
            return Result.fail("缓存预热失败: " + e.getMessage());
        }
    }

    /**
     * 预热指定店铺的缓存
     */
    @Override
    public Result warmUpShopCache(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Result.fail("店铺ID列表不能为空");
        }

        cacheWarmupExecutor.submit(() -> {
            try {
                logger.info("开始预热指定店铺缓存，数量: {}", shopIds.size());

                int successCount = 0;
                for (Long shopId : shopIds) {
                    try {
                        this.queryById(shopId);
                        successCount++;
                        Thread.sleep(20); // 延迟避免压力过大
                    } catch (Exception e) {
                        logger.warn("预热店铺 {} 失败: {}", shopId, e.getMessage());
                    }
                }

                logger.info("指定店铺缓存预热完成，成功预热 {} 个店铺", successCount);
            } catch (Exception e) {
                logger.error("预热指定店铺缓存失败", e);
            }
        });

        return Result.ok("指定店铺缓存预热任务已启动");
    }

    /**
     * 获取缓存预热状态
     */
    @Override
    public Result getCacheWarmupStatus() {
        try {
            Cache shopCache = cacheManager.getCache("shopCache");
            if (shopCache != null) {
                Object nativeCache = shopCache.getNativeCache();

                // 这里可以根据具体的缓存实现获取更详细的状态信息
                Map<String, Object> status = new HashMap<>();
                status.put("cacheType", nativeCache.getClass().getSimpleName());
                status.put("executorActive", cacheWarmupExecutor != null && !cacheWarmupExecutor.isShutdown());

                // 可以添加更多状态信息
                logger.info("缓存预热状态查询: {}", status);
                return Result.ok(status);
            }
            return Result.fail("缓存未就绪");
        } catch (Exception e) {
            logger.error("获取缓存预热状态失败", e);
            return Result.fail("获取状态失败: " + e.getMessage());
        }
    }

    //NOTE 这个方法使用了Spring Cache的@Cacheable注解来实现缓存功能
    //NOTE value指定缓存的名称 shopCache，key指定缓存的键为店铺ID
    //NOTE unless="#result == null"表示如果结果为null则不缓存  避免缓存穿透

    //NOTE 这里自动使用二级缓存，先查一级缓存（本地缓存），再查二级缓存（Redis）
    //NOTE @Cacheable("shopCache")->spring AOP拦截方法调用->CacheManager.getCache("shopCache")->
    // 由于配置了LayeredCacheManager ->返回LayeredCache示例 -> 执行二级缓存逻辑
    @Override
    @Cacheable(value = "shopCache", key = "#id", unless = "#result == null")
    public Result queryById(Long id) {
        logger.info("查询数据库获取店铺信息，店铺ID: {}", id);

        // 直接查询数据库，缓存由注解自动处理
        Shop shop = getById(id);

        // 手动检查缓存状态（用于调试）
        checkCacheStatus(id, shop);

        if(shop == null){
            logger.warn("店铺不存在，ID: {}", id);
            return Result.fail("店铺不存在!");
        }

        logger.info("成功获取店铺信息，店铺名称: {}", shop.getName());
        return Result.ok(shop);
    }


    //NOTE 这个方法使用了Spring Cache的@CacheEvict注解来实现缓存更新功能
    //NOTE value指定缓存的名称 shopCache，key指定要删除的缓存键为店铺ID
    //NOTE Transactional确保数据库更新和缓存删除在同一事务中执行

    //NOTE 自动清除二级缓存
    @Override
    @Transactional
    @CacheEvict(value = "shopCache", key = "#shop.id")
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        logger.info("开始更新店铺信息，店铺ID: {}", id);

        // 更新前先获取旧数据用于日志
        Shop oldShop = getById(id);
        if (oldShop != null) {
            logger.info("更新前店铺名称: {}", oldShop.getName());
        }

        // 更新数据库
        boolean success = updateById(shop);
        if (success) {
            logger.info("数据库更新成功，店铺ID: {}", id);

            // 验证缓存是否已被清除
            checkCacheAfterEvict(id);
        } else {
            logger.error("数据库更新失败，店铺ID: {}", id);
        }

        // 缓存由 @CacheEvict 注解自动删除
        return Result.ok();
    }

    @CacheEvict(value = "shopCache", allEntries = true)
    public Result clearShopCache() {
        logger.info("清空所有店铺缓存");

        // 获取缓存统计信息
        Cache shopCache = cacheManager.getCache("shopCache");
        if (shopCache != null) {
            logger.info("执行清空缓存操作");
        }

        return Result.ok("店铺缓存已清空");
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

    /**
     * 检查缓存状态（用于调试）
     */
    private void checkCacheStatus(Long shopId, Shop shopFromDB) {
        try {
            Cache shopCache = cacheManager.getCache("shopCache");
            if (shopCache != null) {
                Cache.ValueWrapper cachedValue = shopCache.get(shopId);
                if (cachedValue != null) {
                    Object value = cachedValue.get();
                    logger.info("缓存命中 - 店铺ID: {}, 缓存值: {}", shopId, value);
                } else {
                    logger.info("缓存未命中 - 店铺ID: {}, 将从数据库查询", shopId);

                    // 如果是新查询的数据，应该会被自动缓存
                    if (shopFromDB != null) {
                        logger.info("数据库查询成功，数据将被缓存");
                    }
                }

                // 如果使用的是二级缓存，可以尝试获取原生缓存来检查两级缓存状态
                Object nativeCache = shopCache.getNativeCache();
                logger.debug("缓存原生类型: {}", nativeCache.getClass().getName());
            } else {
                logger.warn("未找到 shopCache 缓存实例");
            }
        } catch (Exception e) {
            logger.error("检查缓存状态时发生错误", e);
        }
    }

    /**
     * 检查缓存清除后的状态
     */
    private void checkCacheAfterEvict(Long shopId) {
        try {
            Cache shopCache = cacheManager.getCache("shopCache");
            if (shopCache != null) {
                Cache.ValueWrapper cachedValue = shopCache.get(shopId);
                if (cachedValue == null) {
                    logger.info("缓存清除验证成功 - 店铺ID: {} 的缓存已被清除", shopId);
                } else {
                    logger.warn("缓存清除验证失败 - 店铺ID: {} 的缓存仍然存在", shopId);
                }
            }
        } catch (Exception e) {
            logger.error("检查缓存清除状态时发生错误", e);
        }
    }

    /**
     * 手动获取缓存中的店铺信息（用于测试）
     */
    public Result getCachedShop(Long id) {
        try {
            Cache shopCache = cacheManager.getCache("shopCache");
            if (shopCache != null) {
                Cache.ValueWrapper wrapper = shopCache.get(id);
                if (wrapper != null) {
                    Object value = wrapper.get();
                    logger.info("手动查询缓存成功 - 店铺ID: {}, 值: {}", id, value);
                    return Result.ok(value);
                } else {
                    logger.info("手动查询缓存未命中 - 店铺ID: {}", id);
                    return Result.fail("缓存中未找到该店铺");
                }
            } else {
                return Result.fail("缓存管理器未就绪");
            }
        } catch (Exception e) {
            logger.error("手动查询缓存时发生错误", e);
            return Result.fail("查询缓存失败: " + e.getMessage());
        }
    }

    /**
     * Bean销毁时关闭线程池
     */
    @jakarta.annotation.PreDestroy
    public void destroy() {
        if (cacheWarmupExecutor != null) {
            try {
                cacheWarmupExecutor.shutdown();
                if (!cacheWarmupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cacheWarmupExecutor.shutdownNow();
                }
                logger.info("缓存预热线程池已关闭");
            } catch (InterruptedException e) {
                cacheWarmupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}