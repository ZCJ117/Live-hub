package com.hmdp.order.dto;

import com.hmdp.entity.VoucherOrder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单查询 VO（agent-service 工具调用使用）
 * 订单字段 + 冗余券信息，避免跨服务二次查询
 */
@Data
@NoArgsConstructor
public class OrderQueryVO {

    private Long id;
    private Long userId;
    private Long voucherId;
    /** 券标题（联查 tb_voucher） */
    private String voucherTitle;
    /** 支付金额（分） */
    private Long payValue;
    /** 抵扣金额（分） */
    private Long actualValue;
    /** 订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;

    public static OrderQueryVO of(VoucherOrder order) {
        OrderQueryVO vo = new OrderQueryVO();
        vo.setId(order.getId());
        vo.setUserId(order.getUserId());
        vo.setVoucherId(order.getVoucherId());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setUseTime(order.getUseTime());
        vo.setRefundTime(order.getRefundTime());
        return vo;
    }
}
