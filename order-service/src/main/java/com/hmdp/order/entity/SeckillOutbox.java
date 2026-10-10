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
 * 秒杀本地事件行（SPEC-15 P2-1 方案 a）
 *
 * <p>语义：一行 = 一条"必须被投递到 MQ"的承诺。落库先于投递，所以
 * 「投递决策」的持久化边界是这条 INSERT，而不是 MQ 的 ack。
 *
 * <p><b>为什么 id 用 {@link IdType#INPUT}</b>：orderId 由 {@code RedisIdWorker} 生成，
 * 不是数据库自增。设成 AUTO 会让 MyBatis-Plus 忽略传入值、回填一个自增主键，
 * 与 {@code tb_voucher_order.id} 就此错位——两个表再也对不上。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_outbox")
public class SeckillOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long voucherId;

    /** 0-待投递；1-已投递 */
    private Integer status;

    /** 补投器投递失败次数；超过上限后停止自动补投并告警 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
