package com.hmdp.agent.planner;

/**
 * 意图枚举（FR-03，7 类）
 */
public enum Intent {
    ORDER_QUERY, VOUCHER_CONSULT, SHOP_CONSULT, REFUND, COMPLAINT, CHAT, HUMAN_DEMAND;

    /** 解析失败返回 null（由上层按解析异常处理） */
    public static Intent of(String s) {
        if (s == null) return null;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
