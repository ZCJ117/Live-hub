package com.hmdp.agent.tool;

import com.hmdp.dto.UserDTO;
import lombok.Builder;
import lombok.Getter;

/**
 * 工具执行上下文（服务端构造）
 * 授权红线（PRD 4.2）：userId 由服务端注入，LLM/前端传入的归属参数一律被覆盖
 */
@Getter
@Builder
public class ToolContext {

    private final Long sessionId;
    private final Long userId;
    private final UserDTO user;
    private final String traceId;
    /** 会话焦点对象（用户点选订单后写入，PRD FR-05） */
    private final Long focusOrderId;
}
