package com.hmdp.agent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 前端埋点批量上报请求（D8：POST /agent/track，批量 ≤ 20 条/次）
 */
@Data
public class TrackBatchRequest {

    private List<TrackEventItem> events;

    @Data
    public static class TrackEventItem {
        /** 事件名（白名单校验：m5_session_start / m5_msg_send / m5_first_token） */
        private String eventName;
        private Long sessionId;
        /** 事件属性（6.2 字典：msgLen/roundNo/latencyMs/entry 等） */
        private Map<String, Object> props;
    }
}
