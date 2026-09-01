package com.hmdp.agent.controller;

import cn.hutool.core.util.StrUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.agent.dto.ChatRequest;
import com.hmdp.agent.dto.SessionInfoDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.service.ChatOrchestratorService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 客服会话接口（FR-01/FR-02）
 * SSE 全链路基于异步 Servlet：请求线程只注册 emitter 与提交异步任务（PRD 4.1）
 */
@RestController
@RequestMapping("/agent/chat")
@Slf4j
public class AgentChatController {

    private final AgentSessionService sessionService;
    private final ChatOrchestratorService orchestrator;
    private final SseSessionManager sseManager;
    private final ChatMemoryService memoryService;

    public AgentChatController(AgentSessionService sessionService,
                               ChatOrchestratorService orchestrator,
                               SseSessionManager sseManager,
                               ChatMemoryService memoryService) {
        this.sessionService = sessionService;
        this.orchestrator = orchestrator;
        this.sseManager = sseManager;
        this.memoryService = memoryService;
    }

    /**
     * 建立会话 / 发送消息（统一入口，SSE 响应）
     * - message 为空：建连，流式输出欢迎语（FR-01）
     * - message 非空：一轮对话（FR-02，含 FR-04 工具可视化事件）
     * 未登录由网关拦截（SaTokenGatewayConfig），此处 UserDTO 兜底校验
     */
    @PostMapping
    public SseEmitter chat(@RequestBody(required = false) ChatRequest req) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new com.hmdp.agent.exception.BusinessException("未登录，请先登录");
        }
        if (req == null) {
            req = new ChatRequest();
        }
        // 断线重连（5 分钟内）：sessionId 复用，上下文不丢失（FR-01 验收 3）
        AgentSession session;
        boolean reused = false;
        if (req.getSessionId() != null) {
            session = sessionService.getOwned(req.getSessionId(), user.getId());
            reused = true;
        } else {
            session = sessionService.createOrReuse(user, req);
            reused = isReused(session);
        }

        String token = StpUtil.getTokenValue();
        SseEmitter emitter = sseManager.createEmitter(session.getId());

        if (StrUtil.isBlank(req.getMessage())) {
            // 建连：欢迎语
            orchestrator.handleConnect(session, new ChatOrchestratorService.ConnectContext(token, reused));
        } else {
            // 带上下文建连后的首句/常规消息
            if (req.getContext() != null && req.getContext().getOrderId() != null) {
                // 入口预注入：仅写入焦点候选，归属校验在工具层兜底（PRD FR-01 边界 4 / D1.5 §4）
                memoryService.setFocusOrder(session.getId(), req.getContext().getOrderId());
            }
            orchestrator.handleChat(session, req.getMessage(), token);
        }
        return emitter;
    }

    /** 主动结束会话（FR-12 评价推送时点之一，评价功能 Phase 5） */
    @PostMapping("/close")
    public Result close(@RequestBody ChatRequest req) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        if (req == null || req.getSessionId() == null) {
            return Result.fail("sessionId 不能为空");
        }
        sessionService.getOwned(req.getSessionId(), user.getId());
        sessionService.close(req.getSessionId(), "USER");
        return Result.ok(SessionInfoDTO.of(req.getSessionId(), "CLOSED", true));
    }

    /** 判断是否复用了既有 ACTIVE 会话（FR-01 边界 3） */
    private boolean isReused(AgentSession session) {
        return session.getMsgCount() > 0;
    }
}
