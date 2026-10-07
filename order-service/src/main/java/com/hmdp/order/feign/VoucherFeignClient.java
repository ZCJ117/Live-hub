package com.hmdp.order.feign;

import com.hmdp.config.InternalTokenFeignConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "voucher-service", configuration = InternalTokenFeignConfig.class)
public interface VoucherFeignClient {

    /**
     * 扣减库存（内部端点，走 X-Internal-Token；SPEC-03 §5.4 的 orderId 为幂等键）
     */
    @PutMapping("/internal/voucher/seckill/{id}/stock")
    Result deductStock(@PathVariable("id") Long voucherId, @RequestParam("orderId") Long orderId);

    @GetMapping("voucher/{id}")
    Result getVoucherById(@PathVariable("id") Long voucherId);
}
