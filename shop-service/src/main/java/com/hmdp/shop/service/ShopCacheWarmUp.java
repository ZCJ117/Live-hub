package com.hmdp.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 热门店铺缓存预热（SPEC-05 §5.2）。
 *
 * <p>用 {@link ApplicationRunner} 而非 {@code @PostConstruct}：容器就绪后才执行，
 * 不占启动关键路径；且预热体投递到独立线程后立即返回，因此启动耗时与预热无关（G2）。
 *
 * <p>预热经 {@link ShopCacheService} 这个 Bean 调用，而非类内自调用——原实现调
 * {@code this.queryById(...)} 绕过 AOP，导致预热结果从未落缓存（G3）。
 */
@Component
@Slf4j
public class ShopCacheWarmUp implements ApplicationRunner {

    /** 预热的热门店铺数量 */
    static final int POPULAR_SHOP_LIMIT = 50;

    private final ShopMapper shopMapper;
    private final ShopCacheService shopCacheService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shop-cache-warmup");
        t.setDaemon(true);
        return t;
    });

    public ShopCacheWarmUp(ShopMapper shopMapper, ShopCacheService shopCacheService) {
        this.shopMapper = shopMapper;
        this.shopCacheService = shopCacheService;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.submit(this::warmUpPopularShops);
    }

    void warmUpPopularShops() {
        try {
            List<Shop> popularShops = shopMapper.selectList(
                    new QueryWrapper<Shop>().orderByDesc("score").last("LIMIT " + POPULAR_SHOP_LIMIT));
            for (Shop shop : popularShops) {
                shopCacheService.getShop(shop.getId());
            }
            log.info("热门店铺缓存预热完成，共 {} 个", popularShops.size());
        } catch (Exception e) {
            // 预热失败不影响服务可用性：缓存未命中时读路径会回源 DB
            log.error("热门店铺缓存预热失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
