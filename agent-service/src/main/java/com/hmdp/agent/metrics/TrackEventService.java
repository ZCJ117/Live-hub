package com.hmdp.agent.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.entity.TrackEvent;
import com.hmdp.agent.mapper.TrackEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 业务埋点（D1.8 字典）：服务端关键事件直写，不依赖前端上报
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrackEventService {

    private final TrackEventMapper trackEventMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void track(String eventName, Long sessionId, Long userId, Map<String, Object> props) {
        try {
            TrackEvent event = new TrackEvent()
                    .setEventName(eventName)
                    .setSessionId(sessionId)
                    .setUserId(userId)
                    .setPropsJson(props == null ? null : objectMapper.writeValueAsString(props))
                    .setServerTime(LocalDateTime.now());
            trackEventMapper.insert(event);
        } catch (Exception e) {
            // 埋点失败不阻断业务
            log.warn("埋点写入失败: event={}, sessionId={}", eventName, sessionId);
        }
    }
}
