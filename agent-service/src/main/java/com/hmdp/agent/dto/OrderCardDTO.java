package com.hmdp.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单卡片数据（FR-05 结构化卡片：券名/金额/状态/时间）
 */
@Data
public class OrderCardDTO {

    private Long orderId;
    /** 关联券ID（供券咨询链路 query_voucher 串联，T3.8） */
    private Long voucherId;
    private String voucherTitle;
    /** 支付金额（分） */
    private Long payValue;
    /** 抵扣金额（分） */
    private Long actualValue;
    /** 状态码：1未支付 2已支付 3已核销 4已取消 5退款中 6已退款 */
    private Integer statusCode;
    /** 状态中文描述 */
    private String statusText;
    /** 已取消单置灰标记（FR-05 边界） */
    private Boolean cancelled;
    private LocalDateTime createTime;

    public static OrderCardDTO from(Long orderId, Long voucherId, String title, Long payValue, Long actualValue,
                                    Integer statusCode, LocalDateTime createTime) {
        OrderCardDTO c = new OrderCardDTO();
        c.setOrderId(orderId);
        c.setVoucherId(voucherId);
        c.setVoucherTitle(title);
        c.setPayValue(payValue);
        c.setActualValue(actualValue);
        c.setStatusCode(statusCode);
        c.setStatusText(statusText(statusCode));
        c.setCancelled(statusCode != null && statusCode == 4);
        c.setCreateTime(createTime);
        return c;
    }

    public static String statusText(Integer code) {
        if (code == null) return "未知";
        return switch (code) {
            case 1 -> "未支付";
            case 2 -> "已支付";
            case 3 -> "已核销";
            case 4 -> "已取消";
            case 5 -> "退款中";
            case 6 -> "已退款";
            default -> "未知";
        };
    }
}
