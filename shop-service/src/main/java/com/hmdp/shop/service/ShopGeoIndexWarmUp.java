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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>按 {@code id} 做 keyset 分页（每批 {@value #PAGE_SIZE} 条），<b>每批写完即清空
 * 累积器</b>，因此峰值内存是一批坐标而非整表。不使用 {@code selectPage}——本仓未配置
 * MyBatis-Plus 分页插件（无 {@code PaginationInnerInterceptor} 实现），{@code selectPage}
 * 不会真正限量。
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
            long lastId = 0L;
            int written = 0;
            Set<Long> types = new HashSet<>();

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

                Map<Long, List<RedisGeoCommands.GeoLocation<String>>> byType = new HashMap<>();
                for (Shop shop : chunk) {
                    // 游标必须对每一行前进：整页都被空值守卫跳过时，否则会反复读到同一页
                    lastId = shop.getId();
                    if (shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
                        continue;
                    }
                    byType.computeIfAbsent(shop.getTypeId(), k -> new ArrayList<>())
                            .add(new RedisGeoCommands.GeoLocation<>(
                                    shop.getId().toString(),
                                    new Point(shop.getX(), shop.getY())));
                }

                // 每批写完即清空，峰值内存收敛为一批坐标；GEOADD 按 member 覆盖，分批重写安全
                for (Map.Entry<Long, List<RedisGeoCommands.GeoLocation<String>>> entry : byType.entrySet()) {
                    stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + entry.getKey(), entry.getValue());
                    written += entry.getValue().size();
                    types.add(entry.getKey());
                }

                if (chunk.size() < PAGE_SIZE) {
                    break;
                }
            }

            log.info("GEO 索引预热完成，共写入 {} 个店铺坐标，覆盖 {} 种类型", written, types.size());
        } catch (Exception e) {
            log.error("GEO 索引预热失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
