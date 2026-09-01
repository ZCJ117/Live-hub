package com.hmdp.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 事件统一结构（D1.2 §2.3 事件协议）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseEvent {

    /** session / delta / card / tool_call / tool_result / done / error */
    private String event;

    private Object data;

    public static SseEvent of(String event, Object data) {
        return new SseEvent(event, data);
    }
}
