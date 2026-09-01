package com.hmdp.agent.controller;

import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.ticket.TicketService;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工单基础模块 REST API（创建/查询/更新/状态流转）
 * Phase 4 的 create_ticket 工具复用 TicketService；本阶段先提供独立 API 便于联调验收
 */
@RestController
@RequestMapping("/agent/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final AgentSessionService sessionService;

    /** 创建工单（sessionId 必填，用于会话内去重） */
    @PostMapping
    public Result create(@RequestBody TicketRequest req,
                         @RequestParam("sessionId") Long sessionId) {
        UserDTO user = requireLogin();
        AgentSession session = sessionService.getOwned(sessionId, user.getId());
        AgentTicket ticket = ticketService.create(user.getId(), session.getId(), req);
        return Result.ok(ticket);
    }

    /** 我的工单列表（FR-13 我的工单） */
    @GetMapping("/list")
    public Result listMine() {
        UserDTO user = requireLogin();
        List<AgentTicket> tickets = ticketService.listByUser(user.getId());
        return Result.ok(tickets, (long) tickets.size());
    }

    /** 凭工单号查询（FR-09 验收 4 / FR-13 工单进度） */
    @GetMapping("/{ticketNo}")
    public Result getByNo(@PathVariable("ticketNo") String ticketNo) {
        UserDTO user = requireLogin();
        return Result.ok(ticketService.getByTicketNo(user.getId(), ticketNo));
    }

    /** 状态流转（OPEN→ROUTED→RESOLVED，非法跳转拒绝；RESOLVED 需处理备注） */
    @PutMapping("/{ticketNo}/status")
    public Result transition(@PathVariable("ticketNo") String ticketNo,
                             @RequestBody Map<String, String> body) {
        UserDTO user = requireLogin();
        String target = body.get("targetStatus");
        if (target == null || target.isBlank()) {
            return Result.fail("targetStatus 不能为空");
        }
        return Result.ok(ticketService.transition(user.getId(), ticketNo, target, body.get("handleResult")));
    }

    private UserDTO requireLogin() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new com.hmdp.agent.exception.BusinessException("未登录，请先登录");
        }
        return user;
    }
}
