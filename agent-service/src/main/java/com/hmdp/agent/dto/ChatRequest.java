package com.hmdp.agent.dto;

import jakarta.validation.Valid;
import lombok.Data;

/**
 * 客服会话请求（FR-01 建连 / FR-02 发消息统一入口）
 */
@Data
public class ChatRequest {

    /** 断线重连时携带（5 分钟内上下文不丢失，FR-01 验收 3） */
    private Long sessionId;

    /** 本轮消息；为空表示仅建连（输出欢迎语） */
    private String message;

    /** 入口：my / order_detail / voucher_detail（m5_session_start.entry） */
    private String entry;

    /** 入口预注入上下文（订单/券详情页「遇到问题」进入） */
    @Valid
    private ContextPayload context;

    @Data
    public static class ContextPayload {
        private Long orderId;
        private Long voucherId;
        private Long shopId;
    }
}
