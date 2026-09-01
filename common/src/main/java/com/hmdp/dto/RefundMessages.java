package com.hmdp.dto;

/**
 * 退款受理跨服务契约文案（agent-service 依据返回文案分流处理，双侧必须引用同一常量，
 * 避免字面量耦合——文案改动导致 agent 静默走错分支）
 */
public final class RefundMessages {

    /** 订单已有进行中的退款申请（agent 侧据此返回既有受理编号） */
    public static final String ALREADY_PENDING = "该订单已有进行中的退款申请";

    private RefundMessages() {
    }
}
