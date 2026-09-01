package com.hmdp.order.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.order.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 我的订单查询（agent-service 客服工具调用，PRD 附录 B）
     * userId 从登录态强制注入，禁止传参（工具层授权红线，PRD 4.2）
     */
    @GetMapping("my")
    public Result myOrders(
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        return voucherOrderService.queryMyOrders(user.getId(), orderId, status, days, page, size);
    }

}