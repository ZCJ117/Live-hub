package com.hmdp.voucher.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);

    /**
     * 扣减秒杀券库存（SPEC-03 §5.1/§5.4）
     *
     * @param voucherId 优惠券 id
     * @param orderId   订单 id——作为服务端幂等键：消费重试时同一 orderId 只扣减一次
     * @return 成功；或库存不足失败
     */
    Result deductStock(Long voucherId, Long orderId);
}