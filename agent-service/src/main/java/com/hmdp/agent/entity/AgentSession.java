package com.hmdp.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会话主表（PRD 6.1 / D1.3）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_session")
public class AgentSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 雪花ID，即 sessionId */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String module;

    /** ACTIVE / CLOSED / TRANSFERRED */
    private String status;

    /** 入口：my / order_detail / voucher_detail */
    private String entry;

    /** 入口预注入 JSON：{orderId|voucherId|shopId} */
    private String contextJson;

    /** 第 11 轮起 LLM 压缩摘要（≤512字） */
    private String summary;

    /** IDLE / CLARIFYING / REFUNDING / COMPLAINING / TRANSFERRING */
    private String flowState;

    /** 评价 1-5，NULL=未评价 */
    private Integer rating;

    private String ratingTags;

    private String transferReason;

    private BigDecimal tokenCost;

    /** 单会话消息数（上限 100） */
    private Integer msgCount;

    /** 回放快照引用（Phase 5） */
    private String snapshotUri;

    /** USER / TIMEOUT / TRANSFER */
    private String closeReason;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
