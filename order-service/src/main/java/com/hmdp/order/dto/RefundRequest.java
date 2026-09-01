package com.hmdp.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 退款受理请求（agent-service confirm 编排调用，FR-08 T4.3） */
@Data
public class RefundRequest {

    @NotNull(message = "orderId 不能为空")
    private Long orderId;

    /** 退款原因（选填，审计用） */
    private String reason;
}
