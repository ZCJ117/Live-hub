package com.hmdp.voucher.controller;


import cn.dev33.satoken.annotation.SaCheckRole;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.service.IVoucherService;
import com.hmdp.voucher.service.VoucherCacheService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Resource
    private VoucherCacheService voucherCacheService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @SaCheckRole("admin")
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        // 写后失效（SPEC-15 P1-2 C2）：新券可能在创建前被 GET /voucher/{id} 查过，
        // 留下一枚 60 秒的空值标记，不失效会让新券在窗口内"查不到"
        voucherCacheService.evict(voucher.getId());
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    @SaCheckRole("admin")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        voucherCacheService.evict(voucher.getId());
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    /**
     * 查询券详情（agent-service FR-06，D1.4 C2）
     * @param id 券id
     * @return 券详情（含 threshold/applicableScope，未录入为 null）
     */
    @GetMapping("/{id}")
    public Result queryVoucherById(@PathVariable("id") Long id) {
        // SPEC-15 P1-2 C2：券元信息走 L1(Caffeine) + L2(Redis)，不再每次直查 DB
        Voucher voucher = voucherCacheService.getById(id);
        if (voucher == null) {
            return Result.fail("券不存在");
        }
        return Result.ok(voucher);
    }

    /**
     * 批量查询券（order-service 的 queryMyOrders 联查用，SPEC-07 §5.4 消除 N+1）
     */
    @PostMapping("/batch")
    public Result queryVouchersByIds(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        if (ids.size() > 100) {
            return Result.fail("批量查询数量不能超过 100");
        }
        return Result.ok(voucherService.listByIds(ids));
    }
}