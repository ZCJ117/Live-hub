package com.hmdp.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具宿主标记：标注在包含 @AgentTool 方法的 Spring bean 类上（避免全量 bean 扫描）
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentToolHost {
}
