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
 * 客服工单表（PRD 6.1 / FR-09 数据模型）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("agent_ticket")
public class AgentTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 工单号 TK+yyyyMMdd+6位序号 */
    private String ticketNo;

    private Long sessionId;
    private Long userId;

    /** ORDER / VOUCHER / MERCHANT_SERVICE / ACCOUNT_SECURITY / OTHER */
    private String category;

    /** HIGH / MEDIUM / LOW（涉资金=高，普通=中，建议=低） */
    private String priority;

    /** ≤200字客观摘要 */
    private String summary;

    /** JSON：{orderId?, voucherId?, shopId?} */
    private String refsJson;

    /** MD5(category+sorted(refs)+sessionId)，同会话去重键 */
    private String dedupKey;

    /** OPEN → ROUTED → RESOLVED（非法跳转拒绝） */
    private String status;

    /** ORDER_GROUP / MARKETING_GROUP / MERCHANT_GROUP / SECURITY_GROUP */
    private String assigneeGroup;

    /** 4h / 24h / 72h */
    private String expectedSla;

    /** PENDING / SENT / FAILED */
    private String notifyStatus;

    private String handleResult;
    private LocalDateTime resolveTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
