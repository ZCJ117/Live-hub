package com.hmdp.agent.dto;

import lombok.Data;

/**
 * 会话信息（建连返回）
 */
@Data
public class SessionInfoDTO {

    private Long sessionId;
    private String status;
    /** 灰度分桶由服务端计算返回，前端不自判 */
    private Boolean agentEnabled;

    public static SessionInfoDTO of(Long sessionId, String status, boolean enabled) {
        SessionInfoDTO dto = new SessionInfoDTO();
        dto.setSessionId(sessionId);
        dto.setStatus(status);
        dto.setAgentEnabled(enabled);
        return dto;
    }
}
