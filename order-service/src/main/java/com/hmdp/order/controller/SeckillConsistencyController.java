package com.hmdp.order.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hmdp.dto.Result;
import com.hmdp.order.service.ISeckillConsistencyService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/seckill/consistency")
@SaCheckRole("admin")
public class SeckillConsistencyController {

    @Resource
    private ISeckillConsistencyService consistencyService;

    /**
     * 订单一致性检查（SPEC-04 §5.5）
     *
     * <p>{@code voucherId} 可选：明细 hash 按 voucherId 分片，不传时按「DB 订单 → 活跃券明细反查」
     * 自动定位；定位不到会明确失败，绝不静默返回"一致"。
     */
    @GetMapping("/order/{orderId}")
    public Result checkOrderConsistency(@PathVariable Long orderId,
                                        @RequestParam(value = "voucherId", required = false) Long voucherId) {
        return consistencyService.checkOrderConsistency(orderId, voucherId);
    }

    @PostMapping("/stock/sync/{voucherId}")
    public Result syncStockFromRedisToDb(@PathVariable Long voucherId) {
        return consistencyService.syncStockFromRedisToDb(voucherId);
    }

    @GetMapping("/pending")
    public Result checkPendingOrders() {
        return consistencyService.checkPendingOrders();
    }

    @PostMapping("/repair/{voucherId}")
    public Result repairInconsistentData(@PathVariable Long voucherId) {
        return consistencyService.repairInconsistentData(voucherId);
    }
}
