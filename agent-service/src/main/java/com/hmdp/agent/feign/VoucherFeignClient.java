package com.hmdp.agent.feign;

import com.hmdp.agent.config.FeignAuthConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * voucher-service 券详情客户端（FR-06，D1.4 C2）
 */
@FeignClient(name = "voucher-service", configuration = FeignAuthConfig.class)
public interface VoucherFeignClient {

    @GetMapping("/voucher/{id}")
    Result queryVoucherById(@PathVariable("id") Long id);
}
