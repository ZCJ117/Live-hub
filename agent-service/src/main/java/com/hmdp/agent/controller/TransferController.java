package com.hmdp.agent.controller;

import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.transfer.TransferService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 转人工确认接口（FR-10 T4.9）
 * 用户点击转人工卡片「确认」→ 移交包组装 + 无人值守建单（D-5）
 * 「取消」由前端直接收起卡片（会话未 TRANSFERRED，无需后端动作）
 */
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
@Slf4j
public class TransferController {

    private final TransferService transferService;
    private final AgentSessionService sessionService;

    @PostMapping("/{sessionId}/transfer/confirm")
    public Result confirm(@PathVariable("sessionId") Long sessionId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        log.info("转人工确认: sessionId={}, userId={}", sessionId, user.getId());
        sessionService.getOwned(sessionId, user.getId());
        TransferService.TransferOutcome o = transferService.confirmTransfer(user.getId(), sessionId);
        if (!o.success()) {
            return Result.fail(o.message());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message", o.message());
        data.put("ticketNo", o.ticketNo());
        data.put("expectedSla", o.expectedSla());
        return Result.ok(data);
    }
}
