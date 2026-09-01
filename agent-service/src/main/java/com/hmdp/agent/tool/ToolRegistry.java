package com.hmdp.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册表（D1.2 §5.3）：启动时扫描 @AgentToolHost 宿主类中的 @AgentTool 方法
 * LLM 只能引用注册表内工具；注册表同时生成 ReAct prompt 的工具清单
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;

    private volatile Map<String, ToolDefinition> tools;

    public Map<String, ToolDefinition> getTools() {
        Map<String, ToolDefinition> local = tools;
        if (local == null) {
            synchronized (this) {
                if (tools == null) {
                    init();
                }
                local = tools;
            }
        }
        return Collections.unmodifiableMap(local);
    }

    public ToolDefinition get(String name) {
        return getTools().get(name);
    }

    /** 生成注入 LLM 的工具清单文本 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        getTools().forEach((name, def) ->
                sb.append("- ").append(name).append(": ").append(def.description()).append('\n'));
        return sb.toString();
    }

    private void init() {
        Map<String, ToolDefinition> registry = new LinkedHashMap<>();
        Map<String, Object> hosts = applicationContext.getBeansWithAnnotation(AgentToolHost.class);
        for (Object bean : hosts.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getDeclaredMethods()) {
                AgentTool anno = method.getAnnotation(AgentTool.class);
                if (anno == null) continue;
                if (registry.containsKey(anno.name())) {
                    throw new IllegalStateException("工具名重复注册: " + anno.name());
                }
                registry.put(anno.name(), new ToolDefinition(anno.name(), anno.friendlyText(),
                        anno.description(), bean, method));
            }
        }
        tools = registry;
        log.info("ToolRegistry 初始化完成，已注册工具: {}", registry.keySet());
    }

    public record ToolDefinition(String name, String friendlyText, String description,
                                 Object host, Method method) {
    }
}
