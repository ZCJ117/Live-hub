package com.hmdp.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 退款确认提交请求（FR-08 T4.3：POST /agent/chat/{sessionId}/confirm） */
@Data
public class ConfirmRequest {

    @NotBlank(message = "actionId 不能为空")
    private String actionId;

    /** CONFIRM / CANCEL */
    @NotBlank(message = "decision 不能为空")
    private String decision;

    /** 退款原因（下拉枚举，选填） */
    private String reason;

    /** "其他"原因的文本框补充 */
    private String reasonText;
}
