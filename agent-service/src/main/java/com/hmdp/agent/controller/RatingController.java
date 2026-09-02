package com.hmdp.agent.controller;

import com.hmdp.agent.dto.RatingRequest;
import com.hmdp.agent.service.RatingService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
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
 * 会话评价接口（FR-12 T5.1：POST /agent/chat/{sessionId}/rating）
 * 同步 JSON 响应；评价卡片在会话关闭/转人工确认后由 SSE 推送
 */
@RestController
@RequestMapping("/agent/chat")
@RequiredArgsConstructor
@Slf4j
public class RatingController {

    private final RatingService ratingService;

    @PostMapping("/{sessionId}/rating")
    public Result rate(@PathVariable Long sessionId, @RequestBody(required = false) RatingRequest req) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        if (req == null || req.getScore() == null) {
            return Result.fail("score 不能为空");
        }
        log.info("会话评价提交: sessionId={}, userId={}, score={}, tags={}",
                sessionId, user.getId(), req.getScore(), req.getTags());
        RatingService.RatingOutcome o = ratingService.rate(user.getId(), sessionId, req.getScore(), req.getTags());
        if (!o.success()) {
            return Result.fail(o.message());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message", o.message());
        data.put("rating", o.rating());
        data.put("ratingTags", o.tags());
        return Result.ok(data);
    }
}
