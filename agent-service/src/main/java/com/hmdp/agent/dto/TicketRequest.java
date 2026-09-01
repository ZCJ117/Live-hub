package com.hmdp.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 工单创建/更新请求（工单基础模块 REST API）
 * state transition: OPEN→ROUTED→RESOLVED
 */
@Data
public class TicketRequest {

    @NotBlank(message = "问题类别不能为空")
    @Pattern(regexp = "ORDER|VOUCHER|MERCHANT_SERVICE|ACCOUNT_SECURITY|OTHER",
            message = "非法的问题类别")
    private String category;

    /** 可空则按规则推导：涉资金=HIGH，建议类=LOW，其余=MEDIUM（FR-09 规则） */
    @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "非法的优先级")
    private String priority;

    @NotBlank(message = "问题摘要不能为空")
    @Size(max = 200, message = "摘要不能超过200字")
    private String summary;

    /** refs：orderId/voucherId/shopId */
    private Map<String, Object> refs;

    public static TicketRequest of(String category, String priority, String summary, Map<String, Object> refs) {
        TicketRequest req = new TicketRequest();
        req.setCategory(category);
        req.setPriority(priority);
        req.setSummary(summary);
        req.setRefs(refs);
        return req;
    }
}
