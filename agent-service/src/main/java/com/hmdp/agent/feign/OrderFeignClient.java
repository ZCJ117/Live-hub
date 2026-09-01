package com.hmdp.agent.feign;

import com.hmdp.agent.config.FeignAuthConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * order-service 订单查询客户端（FR-05，附录 B 新接口）
 * Feign 超时 2s（application.yaml feign.readTimeout，PRD 4.1）
 */
@FeignClient(name = "order-service", configuration = FeignAuthConfig.class)
public interface OrderFeignClient {

    @GetMapping("/voucher-order/my")
    Result queryMyOrders(@RequestParam(value = "orderId", required = false) Long orderId,
                         @RequestParam(value = "status", required = false) Integer status,
                         @RequestParam(value = "days", required = false) Integer days,
                         @RequestParam(value = "page", required = false) Integer page,
                         @RequestParam(value = "size", required = false) Integer size);
}
