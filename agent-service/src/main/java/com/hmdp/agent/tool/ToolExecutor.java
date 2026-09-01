package com.hmdp.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.audit.ToolCallAuditService;
import com.hmdp.agent.dto.SseEvent;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.security.Desensitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具执行器：反射调用注册表中的工具方法
 * 统一负责：审计留痕（agent_tool_call）、SSE tool_call/tool_result 事件（FR-04）、耗时统计
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolCallAuditService auditService;
    private final TrackEventService trackEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param sseCallback SSE 事件回调（tool_call/tool_result 状态条，可折叠展示参数与结果）
     */
    public ToolResult execute(String toolName, Map<String, Object> args,
                              ToolContext ctx, ToolSseCallback sseCallback) {
        ToolRegistry.ToolDefinition def = registry.get(toolName);
        if (def == null) {
            return ToolResult.fail("UNKNOWN_TOOL", "该查询能力暂不可用");
        }

        // FR-04 交互 1：推送 tool_call 事件（友好文案）
        long start = System.currentTimeMillis();
        if (sseCallback != null) {
            sseCallback.onEvent("tool_call", Map.of(
                    "callId", ctx.getSessionId() + "-" + start,
                    "toolName", toolName,
                    "friendlyText", def.friendlyText()));
        }

        boolean success = false;
        String summary = null;
        String errorCode = null;
        ToolResult result;
        try {
            result = (ToolResult) def.method().invoke(def.host(), ctx, args == null ? Map.of() : args);
            success = result.isSuccess();
            summary = result.getSummary();
            errorCode = result.getErrorCode();
        } catch (Exception e) {
            log.error("工具执行异常: toolName={}", toolName, e);
            result = ToolResult.fail("TOOL_INVOKE_ERROR", "该查询暂时不可用，请稍后再试");
            errorCode = "TOOL_INVOKE_ERROR";
            summary = result.getSummary();
        }

        int latency = (int) (System.currentTimeMillis() - start);

        // 审计留痕（参数/结果均脱敏，4.2 数据红线）
        auditService.record(ctx.getSessionId(), ctx.getUserId(), toolName,
                safeJson(args), Desensitizer.mask(summary), success, latency, null, errorCode);
        // m5_tool_call 埋点
        trackEventService.track("m5_tool_call", ctx.getSessionId(), ctx.getUserId(), Map.of(
                "toolName", toolName, "success", success, "latency", latency));

        // FR-04 交互 2/3：tool_result 事件（含结果摘要）
        if (sseCallback != null) {
            sseCallback.onEvent("tool_result", Map.of(
                    "callId", ctx.getSessionId() + "-" + start,
                    "toolName", toolName,
                    "success", success,
                    "summary", summary == null ? "" : summary,
                    "latencyMs", latency,
                    "cardPayload", result.getCardPayload() == null ? Map.of() : result.getCardPayload()));
        }
        return result;
    }

    private String safeJson(Object args) {
        try {
            String json = objectMapper.writeValueAsString(args);
            return Desensitizer.mask(json);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    public interface ToolSseCallback {
        void onEvent(String event, Object data);
    }
}
