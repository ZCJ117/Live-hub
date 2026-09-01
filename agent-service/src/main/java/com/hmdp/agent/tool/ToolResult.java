package com.hmdp.agent.tool;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 工具执行结果
 */
@Getter
@Builder
public class ToolResult {

    private final boolean success;

    /** 结构化原始数据（供卡片渲染/LLM Observation） */
    private final Object data;

    /** 脱敏后的人类可读摘要（SSE tool_result 展示 + 进上下文，FR-04 验收 2） */
    private final String summary;

    /** 卡片负载：cardType -> payload（ORDER_LIST 等） */
    private final Map<String, Object> cardPayload;

    private final String errorCode;

    public static ToolResult fail(String errorCode, String summary) {
        return ToolResult.builder().success(false).summary(summary).errorCode(errorCode).build();
    }
}
