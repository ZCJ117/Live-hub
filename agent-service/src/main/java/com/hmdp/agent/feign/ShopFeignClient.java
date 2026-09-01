package com.hmdp.agent.feign;

import com.hmdp.agent.config.FeignAuthConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * shop-service 商户查询/搜索客户端（FR-07，D1.4 C3 复用既有接口）
 */
@FeignClient(name = "shop-service", configuration = FeignAuthConfig.class)
public interface ShopFeignClient {

    @GetMapping("/shop/{id}")
    Result queryShopById(@PathVariable("id") Long id);

    @GetMapping("/shop/of/name")
    Result queryShopByName(@RequestParam("name") String name,
                           @RequestParam(value = "current", defaultValue = "1") Integer current);
}
