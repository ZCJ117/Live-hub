package com.hmdp.social.feign;

import com.hmdp.config.FeignTokenRelayConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商户服务 Feign 客户端（SPEC-09 §1.8：发布笔记时校验商户存在性）。
 *
 * <p>契约由 common 的 {@code FeignContractTest} 守护：{@code GET /shop/{id}} 对应
 * shop-service 的 {@code ShopController.queryShopById}。
 */
@FeignClient(name = "shop-service", configuration = FeignTokenRelayConfig.class)
public interface ShopFeignClient {

    /**
     * 根据 id 查询商铺信息；不存在时目标服务返回 {@code success=false}
     */
    @GetMapping("/shop/{id}")
    Result queryShopById(@PathVariable("id") Long id);
}
