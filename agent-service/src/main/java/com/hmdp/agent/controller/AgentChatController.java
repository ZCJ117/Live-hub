package com.hmdp.agent.controller;

import cn.hutool.core.util.StrUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.agent.dto.ChatRequest;
import com.hmdp.agent.dto.SessionInfoDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.service.ChatOrchestratorService;
import com.hmdp.agent.snapshot.SessionSnapshotService;
import com.hmdp.agent.sse.PollRoundCollector;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服会话接口（FR-01/FR-02）+ 客服记录（FR-13）
 * SSE 全链路基于异步 Servlet：请求线程只注册 emitter 与提交异步任务（PRD 4.1）
 */
@RestController
@RequestMapping("/agent/chat")
@Slf4j
public class AgentChatController {

    /** D2：请求线程限时等待上限（FR-01 边界 2：3s 超时返回 partial 供前端继续轮询） */
    private static final long POLL_WAIT_MS = 3000;

    private final AgentSessionService sessionService;
    private final ChatOrchestratorService orchestrator;
    private final SseSessionManager sseManager;
    private final ChatMemoryService memoryService;
    private final SessionSnapshotService snapshotService;

    public AgentChatController(AgentSessionService sessionService,
                               ChatOrchestratorService orchestrator,
                               SseSessionManager sseManager,
                               ChatMemoryService memoryService,
                               SessionSnapshotService snapshotService) {
        this.sessionService = sessionService;
        this.orchestrator = orchestrator;
        this.sseManager = sseManager;
        this.memoryService = memoryService;
        this.snapshotService = snapshotService;
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

    /**
     * D2 降级轮询（FR-01 边界 2 / 4.4）：SSE 建连失败/不支持时的 JSON 同步通道
     * - message 非空或无 sessionId：与 /agent/chat 相同入口（建连/发消息）。轮次经 sseExecutor 异步执行
     *   （PRD 4.1：不在 Tomcat 工作线程同步阻塞等待 LLM），请求线程仅限时等待聚合结果（3s）
     * - message 为空且携带 sessionId：继续轮询在途轮次（不重发消息）
     * - 超时未完成返回 partial:true，收集器保留，前端继续 3s 间隔轮询直至 finishReason 返回
     */
    @PostMapping("/poll")
    public Result poll(@RequestBody(required = false) ChatRequest req) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        if (req == null) {
            req = new ChatRequest();
        }
        PollRoundCollector collector;
        AgentSession session;
        boolean reused;
        if (req.getSessionId() != null && StrUtil.isBlank(req.getMessage())) {
            // 继续轮询在途轮次
            session = sessionService.getOwned(req.getSessionId(), user.getId());
            collector = sseManager.getPollCollector(session.getId());
            if (collector == null) {
                // 无在途轮次（上一轮已取走/重连）：空响应
                return Result.ok(Map.of("partial", false, "finishReason", "OK",
                        "text", "", "cards", List.of()));
            }
            reused = isReused(session);
        } else {
            session = req.getSessionId() == null
                    ? sessionService.createOrReuse(user, req)
                    : sessionService.getOwned(req.getSessionId(), user.getId());
            reused = isReused(session);
            collector = new PollRoundCollector();
            sseManager.registerPollCollector(session.getId(), collector);
            String token = StpUtil.getTokenValue();
            if (StrUtil.isBlank(req.getMessage())) {
                orchestrator.handleConnect(session, new ChatOrchestratorService.ConnectContext(token, reused));
            } else {
                if (req.getContext() != null && req.getContext().getOrderId() != null) {
                    memoryService.setFocusOrder(session.getId(), req.getContext().getOrderId());
                }
                orchestrator.handleChat(session, req.getMessage(), token);
            }
        }

        boolean completed = collector.await(POLL_WAIT_MS);
        if (!completed) {
            // 收集器保留：前端 3s 间隔继续轮询
            return Result.ok(Map.of("partial", true, "sessionId", String.valueOf(session.getId())));
        }
        sseManager.removePollCollector(session.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partial", false);
        data.put("sessionId", String.valueOf(session.getId()));
        data.put("reused", reused);
        data.put("text", collector.text());
        data.put("cards", collector.cards());
        data.put("finishReason", collector.finishReason());
        return Result.ok(data);
    }

    /** 主动结束会话（FR-12 评价推送时点之一；FR-13 回放快照固化时点之一） */
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

    /** FR-13：客服记录列表（最近 30 天会话，含状态与评价标记） */
    @GetMapping("/history")
    public Result history() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        List<Map<String, Object>> items = sessionService.listRecent(user.getId(), 30).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", s.getId());
                    m.put("status", s.getStatus());
                    m.put("rating", s.getRating());
                    m.put("ratingTags", s.getRatingTags());
                    m.put("summary", s.getSummary());
                    m.put("transferReason", s.getTransferReason());
                    m.put("msgCount", s.getMsgCount());
                    m.put("createTime", s.getCreateTime());
                    return m;
                }).toList();
        return Result.ok(Map.of("sessions", items, "windowDays", 30));
    }

    /** FR-13：会话回放（静态快照：文本+卡片，工具过程不回放；不可继续对话） */
    @GetMapping("/history/{sessionId}")
    public Result replay(@PathVariable Long sessionId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        AgentSession session = sessionService.getOwned(sessionId, user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("status", session.getStatus());
        data.put("summary", session.getSummary());
        data.put("rating", session.getRating());
        data.put("ratingTags", session.getRatingTags());
        data.put("snapshot", snapshotService.loadSnapshot(sessionId)); // null=无快照（如 90 天前归档前未生成）
        data.put("resumeSessionId", sessionId); // 「基于此会话继续咨询」按钮携带值
        return Result.ok(data);
    }

    /** 判断是否复用了既有 ACTIVE 会话（FR-01 边界 3） */
    private boolean isReused(AgentSession session) {
        return session.getMsgCount() > 0;
    }
}
