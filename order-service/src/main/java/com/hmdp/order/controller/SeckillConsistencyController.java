package com.hmdp.order.controller;

import com.hmdp.dto.Result;
import com.hmdp.order.service.ISeckillConsistencyService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/seckill/consistency")
public class SeckillConsistencyController {

    @Resource
    private ISeckillConsistencyService consistencyService;

    @GetMapping("/order/{orderId}")
    public Result checkOrderConsistency(@PathVariable Long orderId) {
        return consistencyService.checkOrderConsistency(orderId);
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
