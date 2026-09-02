package com.hmdp.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.ticket.TicketService;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-14 客服工单工作台（D9，演示级）：坐席侧工单查询/状态流转 + 转人工会话接管
 * 坐席身份鉴权沿用 Sa-Token 登录态（PRD 未定义坐席角色体系）；不新增排班、质检等 PRD 之外能力
 */
@RestController
@RequestMapping("/agent/console")
@RequiredArgsConstructor
@Slf4j
public class AgentConsoleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 移交包 Redis key 前缀（与 TransferService.buildHandoverPackage 约定一致） */
    private static final String TRANSFER_KEY_PREFIX = "agent:transfer:";

    private final TicketService ticketService;
    private final AgentSessionService sessionService;
    private final SseSessionManager sseManager;
    private final RedissonClient redisson;
    private final AgentProperties props;

    /** 坐席侧工单查询（组过滤 + 优先级排序） */
    @GetMapping("/tickets")
    public Result tickets(@RequestParam(value = "group", required = false) String group,
                          @RequestParam(value = "priority", required = false) String priority,
                          @RequestParam(value = "status", required = false) String status) {
        requireLogin();
        return Result.ok(ticketService.listForConsole(group, priority, status));
    }

    /** 坐席侧工单状态流转（与用户侧同一状态机：OPEN→ROUTED→RESOLVED，非法跳转拒绝） */
    @PutMapping("/tickets/{ticketNo}/status")
    public Result transition(@PathVariable("ticketNo") String ticketNo,
                             @RequestBody Map<String, String> body) {
        requireLogin();
        String target = body == null ? null : body.get("targetStatus");
        if (target == null || target.isBlank()) {
            return Result.fail("targetStatus 不能为空");
        }
        return Result.ok(ticketService.transitionBySeat(ticketNo, target, body.get("handleResult")));
    }

    /** 转人工会话队列（snapshot_uri 前缀 redis://agent:transfer: 的 TRANSFERRED 会话） */
    @GetMapping("/transfers")
    public Result transfers() {
        requireLogin();
        List<AgentSession> sessions = sessionService.listTransferQueue();
        List<Map<String, Object>> items = sessions.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("userId", s.getUserId());
            m.put("transferReason", s.getTransferReason());
            m.put("summary", s.getSummary());
            m.put("msgCount", s.getMsgCount());
            m.put("createTime", s.getCreateTime());
            return m;
        }).toList();
        return Result.ok(items);
    }

    /** 读移交包（history/summary/toolSnapshots/transferReason，TTL 7 天） */
    @GetMapping("/transfers/{sessionId}")
    public Result handover(@PathVariable("sessionId") Long sessionId) {
        requireLogin();
        AgentSession session = sessionService.getById(sessionId);
        if (session == null || !"TRANSFERRED".equals(session.getStatus())
                || session.getSnapshotUri() == null
                || !session.getSnapshotUri().startsWith("redis://" + TRANSFER_KEY_PREFIX)) {
            return Result.fail("会话不在转人工队列中");
        }
        String raw = redisson.<String>getBucket(TRANSFER_KEY_PREFIX + sessionId).get();
        if (raw == null) {
            return Result.fail("移交包已过期");
        }
        try {
            JsonNode pkg = MAPPER.readTree(raw);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("history", pkg.get("history"));
            data.put("summary", pkg.get("summary") == null ? null : pkg.get("summary").asText());
            data.put("transferReason", pkg.get("transferReason") == null ? null : pkg.get("transferReason").asText());
            data.put("toolSnapshots", pkg.get("toolSnapshots"));
            return Result.ok(data);
        } catch (Exception e) {
            return Result.fail("移交包解析失败");
        }
    }

    /** 人工接管回复：同一 SSE 通道推送（role=human），需 seat-online=true */
    @PostMapping("/transfers/{sessionId}/reply")
    public Result reply(@PathVariable("sessionId") Long sessionId,
                        @RequestBody Map<String, String> body) {
        requireLogin();
        if (!props.getTransfer().isSeatOnline()) {
            return Result.fail("当前未开启人工接管（agent.transfer.seat-online=false）");
        }
        String text = body == null ? null : body.get("text");
        if (text == null || text.isBlank()) {
            return Result.fail("回复内容不能为空");
        }
        AgentSession session = sessionService.getById(sessionId);
        if (session == null || !"TRANSFERRED".equals(session.getStatus())) {
            return Result.fail("会话未处于转人工状态");
        }
        sseManager.send(sessionId, "delta", Map.of("text", text, "role", "human"));
        log.info("坐席回复已推送: sessionId={}", sessionId);
        return Result.ok();
    }

    private void requireLogin() {
        if (UserHolder.getUser() == null) {
            throw new BusinessException("未登录，请先登录");
        }
    }
}
