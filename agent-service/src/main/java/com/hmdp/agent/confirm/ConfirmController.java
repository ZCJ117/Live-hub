package com.hmdp.agent.confirm;

import com.hmdp.agent.dto.ConfirmRequest;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 退款确认提交接口（FR-08 T4.3：POST /agent/chat/{sessionId}/confirm）
 * 同步 JSON 响应（非 SSE）；前端点击卡片按钮后调用
 */
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
@Slf4j
public class ConfirmController {

    private final ConfirmService confirmService;

    @PostMapping("/{sessionId}/confirm")
    public Result confirm(@PathVariable Long sessionId, @Valid @RequestBody ConfirmRequest req) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        log.info("退款确认提交: sessionId={}, userId={}, decision={}", sessionId, user.getId(), req.getDecision());
        ConfirmService.ConfirmOutcome o = confirmService.confirm(user.getId(), sessionId, req);
        if (!o.success()) {
            return Result.fail(o.message());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message", o.message());
        data.put("refundNo", o.refundNo());
        data.put("ticketNo", o.ticketNo());
        data.put("expectedSla", o.expectedSla());
        return Result.ok(data);
    }
}
