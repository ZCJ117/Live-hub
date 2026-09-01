package com.hmdp.agent.planner;

/**
 * 结构化输出解析失败（R2：重试 2 次后降级菜单）
 */
public class IntentParseException extends Exception {
    public IntentParseException(String message) {
        super(message);
    }
}
