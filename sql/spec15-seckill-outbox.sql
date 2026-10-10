-- =====================================================================
-- SPEC-15 P2-1：秒杀本地事件表（outbox）
--
-- 形态 D1-b（行内投递 + 定时补投）：
--   秒杀入口在调用 Lua 预扣成功之后、发送 MQ 之前，先把这一行落库。
--   落库成功即代表"这条订单一定会被投递"——即使进程随后崩溃，
--   SeckillOutboxDeliverer 也会在后续轮次把它补投出去。
--
-- 状态：0-待投递（status=0 且超时未变更的行才补投）  1-已投递（MQ 已确认）
--
-- 为什么订单号就是主键：orderId 由 RedisIdWorker 生成且全局唯一，
-- 天然充当幂等键 —— 重复落库会因主键冲突被拦下，无需额外唯一索引。
-- =====================================================================
USE hmdp;

CREATE TABLE IF NOT EXISTS tb_seckill_outbox
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '订单ID（与 tb_voucher_order.id 同源，RedisIdWorker 生成）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    voucher_id  BIGINT UNSIGNED NOT NULL COMMENT '秒杀券ID',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待投递 1-已投递',
    retry_count INT             NOT NULL DEFAULT 0 COMMENT '补投器投递失败次数',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间（补投按它做退避）',
    PRIMARY KEY (id),
    KEY idx_status_update (status, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀本地事件表（事务消息 outbox，SPEC-15 P2-1）';
