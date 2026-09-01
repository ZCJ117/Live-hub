package com.hmdp.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具调用审计表（仅追加，4.2 审计要求）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_tool_call")
public class AgentToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;
    private Long userId;

    /** 工具名；安全拦截记录固定为 __security_block__ */
    private String toolName;

    /** 入参 JSON（脱敏后） */
    private String argsJson;

    /** 结果摘要（脱敏后） */
    private String resultSummary;

    /** 1成功 / 0失败 */
    private Integer success;

    private Integer latencyMs;

    private String traceId;

    /** ReAct 步数 1-8 */
    private Integer stepNo;

    private String errorCode;

    private LocalDateTime createTime;
}
