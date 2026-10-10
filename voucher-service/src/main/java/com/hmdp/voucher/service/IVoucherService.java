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

    /**
     * 读取秒杀券 DB 库存（内部端点，SPEC-04 §5.5 对账用）
     */
    Result getSeckillStock(Long voucherId);

    /**
     * 绝对回写秒杀券 DB 库存（内部端点，SPEC-04 §5.5 修复用）
     *
     * <p>秒杀主链路只允许 {@link #deductStock} 的相对扣减；绝对回写是**运维修复**语义，
     * 仅供 order-service 的对账/修复接口调用，故单独成方法并打 warn 日志留痕。
     */
    Result resetSeckillStock(Long voucherId, Integer stock);

    /**
     * 活跃秒杀券 ID 列表（begin_time &lt;= now &lt;= end_time），供定时对账扫描（SPEC-04 §5.6）
     */
    Result listActiveSeckillVoucherIds();
}