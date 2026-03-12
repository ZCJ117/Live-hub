package com.hmdp.order.feign;

import com.hmdp.dto.Result;
import com.hmdp.order.fallback.VoucherFeignClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(name = "voucher-service", fallback = VoucherFeignClientFallback.class)
public interface VoucherFeignClient {

    @PutMapping("voucher/seckill/{id}/stock")
    Result deductStock(@PathVariable("id") Long voucherId);

    @GetMapping("voucher/{id}")
    Result getVoucherById(@PathVariable("id") Long voucherId);
}
