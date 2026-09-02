package com.hmdp.agent.controller;

import com.hmdp.agent.dto.TrackBatchRequest;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 前端埋点统一上报接口（D8，PRD 6.2）
 * 服务端关键事件由 agent-service 直写（不依赖前端）；本接口只承接前端侧三事件
 * （m5_session_start / m5_msg_send / m5_first_token），复用 TrackEventService 落库；
 * userId 取登录态（不信任前端传参）；任何失败返回 success，不阻断业务
 */
@RestController
@RequestMapping("/agent/track")
@RequiredArgsConstructor
@Slf4j
public class AgentTrackController {

    /** 6.2 前端侧事件白名单 */
    private static final Set<String> ALLOWED_EVENTS =
            Set.of("m5_session_start", "m5_msg_send", "m5_first_token");

    /** 批量上限（≤ 20 条/次） */
    private static final int MAX_BATCH = 20;

    private final TrackEventService trackEventService;

    @PostMapping
    public Result report(@RequestBody(required = false) TrackBatchRequest req) {
        int accepted = 0;
        try {
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                // 埋点不阻断业务：未登录静默丢弃
                return Result.ok(Map.of("accepted", 0));
            }
            if (req != null && req.getEvents() != null) {
                for (TrackBatchRequest.TrackEventItem item : req.getEvents()) {
                    if (accepted >= MAX_BATCH) {
                        break; // 批量 ≤ 20 条/次，超出丢弃
                    }
                    if (item == null || !ALLOWED_EVENTS.contains(item.getEventName())) {
                        continue; // 白名单外丢弃
                    }
                    trackEventService.track(item.getEventName(), item.getSessionId(), user.getId(), item.getProps());
                    accepted++;
                }
            }
        } catch (Exception e) {
            log.warn("前端埋点上报处理异常（不阻断业务）");
        }
        return Result.ok(Map.of("accepted", accepted));
    }
}
