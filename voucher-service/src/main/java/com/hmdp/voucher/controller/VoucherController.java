package com.hmdp.voucher.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

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

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
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
        Voucher voucher = voucherService.getById(id);
        if (voucher == null) {
            return Result.fail("券不存在");
        }
        return Result.ok(voucher);
    }
}