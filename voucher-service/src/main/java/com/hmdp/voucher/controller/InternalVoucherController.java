package com.hmdp.voucher.controller;

import com.hmdp.dto.Result;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 内部端点（SPEC-06 §5.2 方案 A）
 *
 * <p>仅供 order-service 经 Nacos 直连调用，与公开业务接口物理隔离：
 * 网关无 {@code /internal/**} 路由，服务侧由 common 的 InternalTokenInterceptor
 * 校验 {@code X-Internal-Token}。不再依赖"网关不转发"这一隐式假设。
 */
@RestController
@RequestMapping("/internal/voucher")
public class InternalVoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 扣减秒杀券库存（原 PUT /voucher/seckill/{id}/stock，见 SPEC-06 §1.2）
     */
    @PutMapping("/seckill/{id}/stock")
    public Result deductStock(@PathVariable("id") Long voucherId,
                              @RequestParam("orderId") Long orderId) {
        return voucherService.deductStock(voucherId, orderId);
    }

    /**
     * 读取秒杀券 DB 库存（SPEC-04 §5.5 对账）
     */
    @GetMapping("/seckill/{id}/stock")
    public Result getSeckillStock(@PathVariable("id") Long voucherId) {
        return voucherService.getSeckillStock(voucherId);
    }

    /**
     * 绝对回写秒杀券 DB 库存（SPEC-04 §5.5 修复，仅运维对账调用）
     */
    @PutMapping("/seckill/{id}/stock/reset")
    public Result resetSeckillStock(@PathVariable("id") Long voucherId,
                                    @RequestParam("stock") Integer stock) {
        return voucherService.resetSeckillStock(voucherId, stock);
    }

    /**
     * 活跃秒杀券 ID 列表（SPEC-04 §5.6 定时对账扫描用）
     */
    @GetMapping("/seckill/active")
    public Result listActiveSeckillVoucherIds() {
        return voucherService.listActiveSeckillVoucherIds();
    }
}
