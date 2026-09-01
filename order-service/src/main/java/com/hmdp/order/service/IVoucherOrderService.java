package com.hmdp.order.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 按 userId 批量查询订单（PRD 附录 B：agent-service 工具调用，本 PRD 唯一业务服务改造点）
     * userId 由登录态强制注入，不接受外部传参（防越权，PRD 4.2 授权）
     *
     * @param userId  用户ID（服务端获取）
     * @param orderId 指定订单ID（可空；非本人订单直接返回空，不泄露存在性）
     * @param status  订单状态筛选（可空）
     * @param days    最近 N 天（可空，默认 7）
     * @param page    页码（从 1 开始）
     * @param size    每页条数（默认 5，上限 20）
     */
    Result queryMyOrders(Long userId, Long orderId, Integer status, Integer days, Integer page, Integer size);

    /** 退款受理（FR-08 第二道闸门）：原子 UPDATE，userId 登录态强制注入 */
    com.hmdp.dto.Result refund(Long userId, Long orderId, String reason);
}