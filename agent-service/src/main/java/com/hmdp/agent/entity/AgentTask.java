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
 * 确认卡片/退款申请表（表结构本阶段建好，写操作流程 Phase 4 实现）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_task")
public class AgentTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;
    private Long userId;

    /** REFUND_REQUEST（预留扩展） */
    private String taskType;

    /** PENDING_CONFIRM / ADOPTED / REJECTED / EXPIRED */
    private String status;

    /** 幂等凭证，一次性消费 */
    private String actionId;

    private String payloadJson;
    private String confirmReason;
    private LocalDateTime expireTime;
    private Long ticketId;
    private LocalDateTime confirmTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
