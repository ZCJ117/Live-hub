package com.hmdp.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * GEO 索引预热（SPEC-05 §5.2）。
 *
 * <p>职责是填充 {@code shop:geo:{typeId}}，**与 shopCache 无关**——本类不触碰商铺缓存。
 * 这是全仓唯一的 GEO 索引写入点，{@code ShopServiceImpl.queryShopByType} 的带坐标
 * 分支依赖它，因此不可删除。
 *
 * <p>按 {@code id} 做 keyset 分页（每批 {@value #PAGE_SIZE} 条），避免启动阶段一次性
 * 把整表读入内存。不使用 {@code selectPage}——本仓未配置 MyBatis-Plus 分页插件，
 * {@code selectPage} 不会真正限量。
 */
@Component
@Slf4j
public class ShopGeoIndexWarmUp implements ApplicationRunner {

    /** 每批拉取条数 */
    static final int PAGE_SIZE = 500;

    private final ShopMapper shopMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shop-geo-warmup");
        t.setDaemon(true);
        return t;
    });

    public ShopGeoIndexWarmUp(ShopMapper shopMapper, StringRedisTemplate stringRedisTemplate) {
        this.shopMapper = shopMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.submit(this::warmUpGeoIndex);
    }

    void warmUpGeoIndex() {
        try {
            Map<Long, List<RedisGeoCommands.GeoLocation<String>>> byType = new HashMap<>();
            long lastId = 0L;
            int total = 0;

            while (true) {
                List<Shop> chunk = shopMapper.selectList(new QueryWrapper<Shop>()
                        .select("id", "type_id", "x", "y")
                        .isNotNull("x")
                        .isNotNull("y")
                        .gt("id", lastId)
                        .orderByAsc("id")
                        .last("LIMIT " + PAGE_SIZE));
                if (chunk.isEmpty()) {
                    break;
                }
                for (Shop shop : chunk) {
                    if (shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
                        continue;
                    }
                    byType.computeIfAbsent(shop.getTypeId(), k -> new ArrayList<>())
                            .add(new RedisGeoCommands.GeoLocation<>(
                                    shop.getId().toString(),
                                    new Point(shop.getX(), shop.getY())));
                    lastId = shop.getId();
                }
                total += chunk.size();
                if (chunk.size() < PAGE_SIZE) {
                    break;
                }
            }

            for (Map.Entry<Long, List<RedisGeoCommands.GeoLocation<String>>> entry : byType.entrySet()) {
                stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + entry.getKey(), entry.getValue());
            }
            log.info("GEO 索引预热完成，共 {} 个店铺，覆盖 {} 种类型", total, byType.size());
        } catch (Exception e) {
            log.error("GEO 索引预热失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
