package com.hmdp.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀一致性修复审计（SPEC-04 §5.5 / §6 步骤 8）
 *
 * <p>每一次真正的库存修复写入一条，用于回答"谁在什么时候把库存改成了多少"。
 * 只读诊断（无分歧）**不**记审计，避免把正常的定时对账刷成噪音。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_consistency_audit")
public class SeckillConsistencyAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long voucherId;

    /** DB_TO_REDIS / SYNC_REDIS_TO_DB / REBUILD_STOCK_KEY */
    private String action;

    private Integer beforeStock;

    private Integer afterStock;

    private String detail;

    private LocalDateTime createTime;
}
