package com.hmdp.order.feign;

import com.hmdp.config.FeignTokenRelayConfig;
import com.hmdp.config.InternalTokenFeignConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "voucher-service",
        configuration = {InternalTokenFeignConfig.class, FeignTokenRelayConfig.class})
public interface VoucherFeignClient {

    /**
     * 扣减库存（内部端点，走 X-Internal-Token；SPEC-03 §5.4 的 orderId 为幂等键）
     */
    @PutMapping("/internal/voucher/seckill/{id}/stock")
    Result deductStock(@PathVariable("id") Long voucherId, @RequestParam("orderId") Long orderId);

    /**
     * 批量查询券详情（消除 queryMyOrders 的 N+1）
     */
    @PostMapping("/voucher/batch")
    Result getVouchersByIds(@RequestBody List<Long> ids);

    /**
     * 读取秒杀券 DB 库存（SPEC-04 §5.5 对账：Redis 与 DB 的权威比对需要 DB 侧真值）
     */
    @GetMapping("/internal/voucher/seckill/{id}/stock")
    Result getSeckillStock(@PathVariable("id") Long voucherId);

    /**
     * 绝对回写秒杀券 DB 库存（SPEC-04 §5.5 修复：以 Redis 为准回写 DB 时使用）
     */
    @PutMapping("/internal/voucher/seckill/{id}/stock/reset")
    Result resetSeckillStock(@PathVariable("id") Long voucherId,
                             @RequestParam("stock") Integer stock);

    /**
     * 活跃秒杀券 ID 列表（SPEC-04 §5.6 定时对账扫描）
     */
    @GetMapping("/internal/voucher/seckill/active")
    Result getActiveSeckillVoucherIds();
}
