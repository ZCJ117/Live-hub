package com.hmdp.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 工具注册注解（ToolRegistry 启动扫描，D1.2 §5.3）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /** 工具名（LLM Action 引用） */
    String name();

    /** 友好文案：SSE tool_call 状态条展示（FR-04） */
    String friendlyText();

    /** 参数说明（注入 LLM 的工具描述） */
    String description();
}
