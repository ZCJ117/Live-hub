package com.hmdp.agent.service;

import com.hmdp.agent.audit.ToolCallAuditService;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.config.AgentTokenHolder;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.flow.ComplaintFlowService;
import com.hmdp.agent.flow.RefundFlowService;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.PlanDecision;
import com.hmdp.agent.planner.PlannerService;
import com.hmdp.agent.react.ReActEngine;
import com.hmdp.agent.security.Desensitizer;
import com.hmdp.agent.security.EmotionDetector;
import com.hmdp.agent.security.InjectionDetector;
import com.hmdp.agent.security.InputPreprocessor;
import com.hmdp.agent.security.SensitiveWordService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.tool.ToolContext;
import com.hmdp.agent.transfer.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 对话编排服务（Phase 3 主链路）
 * 输入预处理 → 短期记忆组装 → Planner 意图路由（FR-03）→ ReAct/直答/澄清/菜单 → 结论流式输出 → 摘要压缩触发
 * 全程在 agentSseExecutor 异步线程池执行，严禁阻塞 Tomcat 工作线程（PRD 4.1）
 */
@Service
@Slf4j
public class ChatOrchestratorService {

    private final AgentSessionService sessionService;
    private final ChatMemoryService memoryService;
    private final ReActEngine reActEngine;
    private final SseSessionManager sseManager;
    private final TrackEventService trackEventService;
    private final GlmClient glmClient;
    private final AgentProperties props;
    private final PlannerService plannerService;
    private final Executor sseExecutor;
    private final InjectionDetector injectionDetector;
    private final SensitiveWordService sensitiveWordService;
    private final EmotionDetector emotionDetector;
    private final ToolCallAuditService auditService;
    private final RefundFlowService refundFlowService;
    private final ComplaintFlowService complaintFlowService;
    private final TransferService transferService;

    public ChatOrchestratorService(AgentSessionService sessionService,
                                   ChatMemoryService memoryService,
                                   ReActEngine reActEngine,
                                   SseSessionManager sseManager,
                                   TrackEventService trackEventService,
                                   GlmClient glmClient,
                                   AgentProperties props,
                                   PlannerService plannerService,
                                   InjectionDetector injectionDetector,
                                   SensitiveWordService sensitiveWordService,
                                   EmotionDetector emotionDetector,
                                   ToolCallAuditService auditService,
                                   RefundFlowService refundFlowService,
                                   ComplaintFlowService complaintFlowService,
                                   TransferService transferService,
                                   @Qualifier("agentSseExecutor") Executor sseExecutor) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.reActEngine = reActEngine;
        this.sseManager = sseManager;
        this.trackEventService = trackEventService;
        this.glmClient = glmClient;
        this.props = props;
        this.plannerService = plannerService;
        this.injectionDetector = injectionDetector;
        this.sensitiveWordService = sensitiveWordService;
        this.emotionDetector = emotionDetector;
        this.auditService = auditService;
        this.refundFlowService = refundFlowService;
        this.complaintFlowService = complaintFlowService;
        this.transferService = transferService;
        this.sseExecutor = sseExecutor;
    }

    /** SSE 适配（建连阶段携带 token 与复用标记） */
    public record ConnectContext(String token, Boolean reused) {
    }

    /** 建连 + 欢迎语（FR-01 交互 2/3） */
    public void handleConnect(AgentSession session, ConnectContext ctx) {
        sseExecutor.execute(() -> {
            try {
                AgentTokenHolder.set(ctx.token());
                MDC.put("traceId", ToolCallAuditService.currentTraceId());
                sseManager.send(session.getId(), "session", Map.of(
                        "sessionId", String.valueOf(session.getId()),
                        "status", session.getStatus(),
                        "reused", Boolean.TRUE.equals(ctx.reused()),
                        "aiNotice", "本服务由 AI 提供，内容由人工智能生成"));
                // 欢迎语流式输出（能力提示，FR-01 交互 3）
                for (String part : InputPreprocessor.WELCOME_MESSAGE.split("\n")) {
                    sseManager.send(session.getId(), "delta", Map.of("text", part + "\n"));
                }
                sseManager.send(session.getId(), "done", Map.of("roundNo", 0));
            } catch (Exception e) {
                log.error("会话建立处理失败: sessionId={}", session.getId(), e);
                sseManager.send(session.getId(), "error", Map.of(
                        "code", "CONNECT_FAIL", "friendlyText", "连接失败，请重试"));
            } finally {
                AgentTokenHolder.clear();
                MDC.remove("traceId");
            }
        });
    }

    /** 一轮对话（异步执行） */
    public void handleChat(AgentSession session, String rawMessage, String token) {
        sseExecutor.execute(() -> {
            try {
                AgentTokenHolder.set(token);
                MDC.put("traceId", ToolCallAuditService.currentTraceId());
                doChat(session, rawMessage);
            } catch (Exception e) {
                log.error("对话处理失败: sessionId={}", session.getId(), e);
                sseManager.send(session.getId(), "error", Map.of(
                        "code", "CHAT_FAIL", "friendlyText", "服务暂时繁忙，请稍后再试"));
                sseManager.send(session.getId(), "done", Map.of("finishReason", "ERROR"));
            } finally {
                AgentTokenHolder.clear();
                MDC.remove("traceId");
            }
        });
    }

    private void doChat(AgentSession session, String rawMessage) {
        Long sessionId = session.getId();
        long roundStartMs = System.currentTimeMillis();

        // 1. 输入预处理（FR-02 边界：空/纯表情引导，超长截断）
        InputPreprocessor.PreprocessResult pp = InputPreprocessor.preprocess(rawMessage, props);
        if (pp.verdict() == InputPreprocessor.Verdict.GUIDE_EMOJI) {
            sseManager.send(sessionId, "delta", Map.of("text", pp.message()));
            sseManager.send(sessionId, "done", Map.of("roundNo", session.getMsgCount()));
            return;
        }
        String message = pp.message();

        // 1.2 消息数频控（单会话 100 条，FR-11）——置于安全检测之前，被拦截消息也计入会话消息数
        try {
            sessionService.checkAndIncrMsg(session);
        } catch (Exception e) {
            sseManager.send(sessionId, "error", Map.of(
                    "code", "MSG_LIMIT", "friendlyText", e.getMessage()));
            sseManager.send(sessionId, "done", Map.of("finishReason", "MSG_LIMIT"));
            return;
        }

        // 1.6 输入安全检测（FR-11 T4.11：命中不进 LLM，固定话术 + 双留痕）
        String injectionRule = injectionDetector.matchRule(message);
        if (injectionRule == null && sensitiveWordService.firstHit(message).isPresent()) {
            injectionRule = "sensitive-word";
        }
        if (injectionRule != null) {
            log.warn("输入安全拦截: sessionId={}, rule={}", sessionId, injectionRule);
            trackEventService.track("m5_input_blocked", sessionId, session.getUserId(),
                    Map.of("rule", injectionRule));
            auditService.recordSecurityBlock(sessionId, session.getUserId(), "INPUT:" + injectionRule);
            String notice = "您的消息包含不太合适的内容，请换种方式描述。若属误会，可回复\"投诉\"提交申诉由人工核实。";
            sseManager.send(sessionId, "delta", Map.of("text", notice));
            sseManager.send(sessionId, "done", Map.of("roundNo", session.getMsgCount(), "finishReason", "INPUT_BLOCKED"));
            memoryService.append(sessionId, "assistant", notice);
            return;
        }

        trackEventService.track("m5_msg_send", sessionId, session.getUserId(),
                Map.of("msgLen", message.length(), "roundNo", session.getMsgCount()));

        // 3. 短期记忆组装（system prompt + 摘要 + 最近 10 轮 + 当前消息）
        List<ChatMemoryService.LlmTypesMsg> history = memoryService.loadHistory(sessionId);
        memoryService.append(sessionId, "user", message);

        ToolContext ctx = ToolContext.builder()
                .sessionId(sessionId)
                .userId(session.getUserId())
                .traceId(ToolCallAuditService.currentTraceId())
                .focusOrderId(memoryService.getFocusOrder(sessionId))
                .build();

        StringBuilder answer = new StringBuilder();
        Consumer<String> onDelta = delta -> {
            if (answer.isEmpty()) {
                // 首 token 延迟 = 本轮消息接收 → 首个 delta（D1.2 §2.2，支撑 4.1 P90 ≤ 1.5s 指标）
                trackEventService.track("m5_first_token", sessionId, session.getUserId(),
                        Map.of("latencyMs", System.currentTimeMillis() - roundStartMs));
            }
            answer.append(delta);
            sseManager.send(sessionId, "delta", Map.of("text", delta));
        };

        // 1.4 转人工状态锁（FR-10 T4.8：触发后零 LLM 输出；消息已入历史随移交包带给人工。
        //  落位于 onDelta 定义之后、Planner 之前——核心约束是"不进 LLM/不跑 Planner"，语义等价）
        if ("TRANSFERRED".equals(session.getStatus())) {
            String ack = "您的消息已记录，将随工单一并转交人工客服。";
            memoryService.append(sessionId, "assistant", ack);
            onDelta.accept(ack);
            sseManager.send(sessionId, "done", Map.of("roundNo", session.getMsgCount(),
                    "finishReason", "TRANSFERRED"));
            log.info("TRANSFERRED 会话消息入队（零 LLM 输出）: sessionId={}", sessionId);
            return;
        }

        // 1.7 情绪检测（FR-10 触发条件 4，R8 高置信才触发；命中直接转人工绕过 Planner）
        if (emotionDetector.isHighlyNegative(message)) {
            transferService.trigger(session, "NEGATIVE_EMOTION");
            String text = "已为您转接人工客服，请确认移交信息。";
            finishAfterTransfer(session, answer, onDelta, text);
            memoryService.append(sessionId, "assistant", Desensitizer.mask(text));
            sseManager.send(sessionId, "done", Map.of("roundNo", session.getMsgCount(),
                    "finishReason", "TRANSFERRED"));
            return;
        }

        // 4. Planner 意图识别与路由（FR-03，Phase 3 主链路收口）
        PlanDecision decision = plannerService.plan(session, message, history);
        if (decision.interruptNotice() != null) {
            // T3.5 上下文冲突：中断提示先于回答推送
            sseManager.send(sessionId, "delta", Map.of("text", decision.interruptNotice() + "\n"));
            answer.append(decision.interruptNotice()).append('\n');
        }
        ReActEngine.ReactResult result = dispatch(decision, session, ctx, history, message, answer, onDelta);

        // 5. 兜底话术（空回答 / 工具连败建议建单，FR-05 边界）
        if (result.answer() == null || result.answer().isBlank()) {
            String fallback = result.needTicketFallback()
                    ? "查询暂时遇到问题，您可以稍后再试，或提交工单由人工跟进。"
                    : "请描述您的问题，我来帮您查询。";
            answer.append(fallback);
            sseManager.send(sessionId, "delta", Map.of("text", fallback));
        }
        sseManager.send(sessionId, "done", Map.of(
                "roundNo", session.getMsgCount(),
                "finishReason", result.reason() == null ? "OK" : result.reason()));

        // 6. 回写记忆（assistant 消息脱敏后入历史，4.2 数据红线）
        // 回写完整编排文本（含中断提示/退款后缀/兜底文案），保证下一轮分类器可见（Code Review Important #2）
        String reply = answer.isEmpty() ? result.answer() : answer.toString();
        memoryService.append(sessionId, "assistant",
                Desensitizer.mask(reply == null ? "" : reply));

        // 7. 摘要压缩触发（超 10 轮，异步不阻塞，FR-02 交互 3）
        triggerSummaryIfNeeded(session);

        // 8. token 成本记账（T3.13/R5）：分类 + 回答用量累加回写
        sessionService.addTokenCost(sessionId, result.promptTokens(),
                result.completionTokens() + decision.classifyPromptTokens() + decision.classifyCompletionTokens());
    }

    /** 按 Planner 决策分发（FR-03；T3.4 复合意图共享 8 步预算） */
    private ReActEngine.ReactResult dispatch(PlanDecision decision, AgentSession session, ToolContext ctx,
                                             List<ChatMemoryService.LlmTypesMsg> history, String message,
                                             StringBuilder answer, Consumer<String> onDelta) {
        Long sessionId = session.getId();
        switch (decision.type()) {
            case REACT -> {
                List<String> subtasks = decision.subtasks().isEmpty() ? List.of(message) : decision.subtasks();
                int budget = props.getReact().getMaxSteps();
                ReActEngine.ReactResult last = null;
                for (int i = 0; i < subtasks.size() && budget > 0; i++) {
                    if (i > 0) {
                        answer.append("\n\n");
                        sseManager.send(sessionId, "delta", Map.of("text", "\n\n"));
                    }
                    last = reActEngine.run(ctx, history, session.getSummary(), subtasks.get(i),
                            budget, onDelta, (event, data) -> sseManager.send(sessionId, event, data));
                    budget -= last.stepsUsed();
                }
                if (last == null) {
                    // 预算耗尽兜底：直答收敛
                    last = reActEngine.chatDirect(history, session.getSummary(),
                            "预算已用完，请基于已知信息简要回答用户：" + message, onDelta,
                            (event, data) -> sseManager.send(sessionId, event, data));
                }
                if ("TOOL_CONSECUTIVE_FAIL".equals(last.reason())) {
                    // T4.8：连续 2 次工具失败触发转人工（ReActEngine 硬中断后接管）
                    transferService.trigger(session, "TOOL_FAIL");
                    finishAfterTransfer(session, answer, onDelta,
                            "查询服务连续异常，已为您转接人工客服，请确认移交信息。");
                    return new ReActEngine.ReactResult("TRANSFERRED", false, "TRANSFERRED",
                            last.stepsUsed(), last.promptTokens(), last.completionTokens());
                }
                return last;
            }
            case REFUND -> {
                // T4.2：退款编排 → 确认卡片（替换 Phase 3 "即将开放"桩）
                refundFlowService.handle(session, ctx, answer, onDelta);
                return new ReActEngine.ReactResult("REFUND_FLOW", false, "REFUND_FLOW", 0, 0, 0);
            }
            case CHAT_DIRECT -> {
                // T3.6/T3.13：寒暄/闲聊/超范围免工具直答（light 档，超范围引导在 system prompt 硬约束 6）
                return reActEngine.chatDirect(history, session.getSummary(), message, onDelta,
                        (event, data) -> sseManager.send(sessionId, event, data));
            }
            case CLARIFY -> {
                // T3.3：澄清话术（模板直出，不再进 LLM 生成）
                answer.append(decision.clarifyText());
                sseManager.send(sessionId, "delta", Map.of("text", decision.clarifyText()));
                return new ReActEngine.ReactResult(decision.clarifyText(), false, "CLARIFY", 0, 0, 0);
            }
            case FALLBACK_MENU -> {
                // T3.2/T3.3：降级菜单卡片（按钮：查订单/查券/退款/投诉/转人工）
                sseManager.send(sessionId, "delta", Map.of("text", "抱歉，我没理解您的意思，请选择您需要的服务："));
                sseManager.send(sessionId, "card", Map.of(
                        "cardType", "CLARIFY_MENU",
                        "payload", Map.of("options", List.of("查订单", "查券", "退款", "投诉", "转人工"))));
                return new ReActEngine.ReactResult("请选择您需要的服务", false, "FALLBACK_MENU", 0, 0, 0);
            }
            case COMPLAINT -> {
                // T4.6：要素收集状态机（替换安抚桩，话术经 onDelta 流出）
                complaintFlowService.handle(session, message, answer, onDelta);
                return new ReActEngine.ReactResult("COMPLAINT_FLOW", false, "COMPLAINT", 0, 0, 0);
            }
            case HUMAN_DEMAND -> {
                // T4.8：显式要求转人工（实装 FR-10）；done 由主流程统一发送（finishReason=TRANSFERRED）
                transferService.trigger(session, "HUMAN_DEMAND");
                finishAfterTransfer(session, answer, onDelta,
                        "即将为您转接人工客服，请在卡片上确认移交信息。");
                return new ReActEngine.ReactResult("TRANSFERRED", false, "TRANSFERRED", 0, 0, 0);
            }
            case TRANSFER -> {
                // T4.8：澄清 2 轮超限触发转人工；done 由主流程统一发送
                transferService.trigger(session, "CLARIFY_EXCEED");
                finishAfterTransfer(session, answer, onDelta,
                        "多次未能确认您的需求，已为您转接人工客服。");
                return new ReActEngine.ReactResult("TRANSFERRED", false, "TRANSFERRED", 0, 0, 0);
            }
        }
        // switch 穷举后不可达（Java 语句 switch 需显式返回）
        return new ReActEngine.ReactResult("", false, "UNKNOWN", 0, 0, 0);
    }

    /** 转人工触发后的固定话术收口（走 onDelta 管道保证记忆/首 token 埋点一致；不进 LLM） */
    private void finishAfterTransfer(AgentSession session, StringBuilder answer,
                                     Consumer<String> onDelta, String text) {
        answer.append(text);
        onDelta.accept(text);
    }

    /** 摘要压缩：结果回写 agent_session.summary；失败降级仅保留最近 6 条（FR-02 边界） */
    private void triggerSummaryIfNeeded(AgentSession session) {
        Long sessionId = session.getId();
        if (memoryService.size(sessionId) < props.getSession().getMaxHistoryRounds() * 2) {
            return;
        }
        sseExecutor.execute(() -> {
            try {
                String raw = memoryService.readRawJson(sessionId);
                String prompt = "请将以下客服对话历史压缩为不超过512字的客观摘要，"
                        + "保留：用户身份、诉求、已查到的事实（订单号/状态等）、未解决的问题。只输出摘要正文。\n\n"
                        + (session.getSummary() == null ? "" : "已有摘要（请合并）：" + session.getSummary() + "\n\n")
                        + "对话历史：\n" + raw;
                com.hmdp.agent.llm.LlmTypes.Response r = glmClient.complete(com.hmdp.agent.llm.LlmTypes.Request.builder()
                        .model("glm-4-flash")
                        .messages(List.of(com.hmdp.agent.llm.LlmTypes.Message.user(prompt)))
                        .temperature(0.2)
                        .build());
                String summary = r.getContent();
                if (summary != null && !summary.isBlank()) {
                    sessionService.updateSummary(sessionId, summary.trim());
                    memoryService.trimKeepLast(sessionId, props.getSession().getDegradedHistoryRounds());
                    log.info("会话摘要压缩完成: sessionId={}", sessionId);
                } else {
                    memoryService.trimKeepLast(sessionId, props.getSession().getDegradedHistoryRounds());
                    log.warn("摘要压缩失败，降级保留最近 {} 条: sessionId={}",
                            props.getSession().getDegradedHistoryRounds(), sessionId);
                }
            } catch (Exception e) {
                memoryService.trimKeepLast(sessionId, props.getSession().getDegradedHistoryRounds());
                log.warn("摘要压缩异常，降级处理: sessionId={}", sessionId, e);
            }
        });
    }
}
