package com.hmdp.order.fallback;

import com.hmdp.dto.Result;
import com.hmdp.order.feign.VoucherFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class VoucherFeignClientFallback implements VoucherFeignClient {

    @Override
    public Result deductStock(Long voucherId) {
        log.warn("voucher-service服务不可用，扣减库存降级处理: voucherId={}", voucherId);
        return Result.fail("库存服务暂时不可用，订单将异步处理");
    }

    @Override
    public Result getVoucherById(Long voucherId) {
        log.warn("voucher-service服务不可用，查询优惠券降级处理: voucherId={}", voucherId);
        return Result.fail("优惠券服务暂时不可用");
    }
}
