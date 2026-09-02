package com.hmdp.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.metrics.TrackEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 会话评价（FR-12 T5.1/T5.2）
 * 会话结束（关闭/转人工）后推送评价卡片；提交即落 agent_session.rating/rating_tags；
 * 每会话仅一次（rate 条件更新）；评价卡片一次性推送、无提醒调度——忽略即不再提醒（24h 规则由构造保证）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RatingService {

    /** 不满意原因标签白名单（FR-12 交互：满意/不满意 + 不满意时的多选标签） */
    public static final List<String> ALLOWED_TAGS = List.of("没解决问题", "答非所问", "操作太复杂", "其他");

    /** 评价卡片负载（关闭/转人工两处推送共用；静态方法供 AgentSessionService 复用，避免 Bean 循环依赖） */
    public static Map<String, Object> ratingCard(Long sessionId) {
        return Map.of(
                "cardType", "RATING",
                "payload", Map.of(
                        "sessionId", String.valueOf(sessionId),
                        "hint", "请对本次服务作出评价",
                        "satisfactionLabels", List.of("满意", "不满意"),
                        "tags", ALLOWED_TAGS));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSessionService sessionService;
    private final TrackEventService trackEventService;

    /** 同步结果（RatingController 转 Result 输出） */
    public record RatingOutcome(boolean success, String message, Integer rating, List<String> tags) {
    }

    public RatingOutcome rate(Long userId, Long sessionId, Integer score, List<String> tags) {
        sessionService.getOwned(sessionId, userId); // 归属强绑定（4.2）：越权即拒
        AgentSession session = sessionService.getById(sessionId);
        if (!"CLOSED".equals(session.getStatus()) && !"TRANSFERRED".equals(session.getStatus())) {
            return new RatingOutcome(false, "会话结束后才能评价", null, null);
        }
        if (score == null || (score != 5 && score != 1)) {
            return new RatingOutcome(false, "score 仅支持 5（满意）或 1（不满意）", null, null);
        }
        // 不满意才有原因标签，白名单外过滤
        List<String> validTags = score == 1 && tags != null
                ? tags.stream().filter(ALLOWED_TAGS::contains).toList()
                : List.of();
        String tagsJson = null;
        if (!validTags.isEmpty()) {
            try {
                tagsJson = MAPPER.writeValueAsString(validTags);
            } catch (Exception e) {
                log.warn("评价标签序列化失败: sessionId={}", sessionId);
            }
        }
        if (!sessionService.rate(sessionId, userId, score, tagsJson)) {
            return new RatingOutcome(false, "本会话已评价过，感谢您的反馈", null, null);
        }
        // m5_rating_submit（T5.2）：score + tags
        trackEventService.track("m5_rating_submit", sessionId, userId,
                Map.of("score", score, "tags", validTags));
        return new RatingOutcome(true, "感谢您的反馈", score, validTags);
    }
}
