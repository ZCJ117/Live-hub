package com.hmdp.agent.audit;

import com.hmdp.agent.entity.AgentToolCall;
import com.hmdp.agent.mapper.AgentToolCallMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 工具调用审计服务（4.2 审计：全量留痕，参数/结果/耗时/trace_id，支持按会话回放追溯）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ToolCallAuditService {

    public static final String TRACE_ID = "traceId";

    private final AgentToolCallMapper toolCallMapper;

    /** 当前链路 traceId（MDC 优先，缺失时生成） */
    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(TRACE_ID, traceId);
        }
        return traceId;
    }

    public void record(Long sessionId, Long userId, String toolName, String argsJson,
                       String resultSummary, boolean success, int latencyMs,
                       Integer stepNo, String errorCode) {
        try {
            AgentToolCall call = new AgentToolCall()
                    .setSessionId(sessionId)
                    .setUserId(userId)
                    .setToolName(toolName)
                    .setArgsJson(argsJson)
                    .setResultSummary(resultSummary)
                    .setSuccess(success ? 1 : 0)
                    .setLatencyMs(latencyMs)
                    .setTraceId(currentTraceId())
                    .setStepNo(stepNo)
                    .setErrorCode(errorCode);
            toolCallMapper.insert(call);
        } catch (Exception e) {
            // 审计失败不阻断业务，但必须留日志（4.2）
            log.error("审计留痕失败: sessionId={}, toolName={}", sessionId, toolName, e);
        }
    }

    /** 安全拦截事件留痕（FR-11；tool_name 固定 __security_block__，D1.5 §6） */
    public void recordSecurityBlock(Long sessionId, Long userId, String detail) {
        record(sessionId, userId, "__security_block__", detail, null, false, 0, null, "SECURITY_BLOCK");
    }
}
