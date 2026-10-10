-- =====================================================================
-- SPEC-04 §5.5 / §6 步骤 8：秒杀一致性修复审计表
--
-- 只记"真正发生了写入"的修复动作；只读诊断不落库，避免定时对账把表刷成噪音。
-- 对应接口：
--   POST /seckill/consistency/repair/{voucherId}        → DB_TO_REDIS / REBUILD_STOCK_KEY
--   POST /seckill/consistency/stock/sync/{voucherId}    → SYNC_REDIS_TO_DB
-- =====================================================================
USE hmdp;

CREATE TABLE IF NOT EXISTS tb_seckill_consistency_audit
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    voucher_id   BIGINT UNSIGNED NOT NULL COMMENT '秒杀券ID',
    action       VARCHAR(32)     NOT NULL COMMENT '修复动作：DB_TO_REDIS/SYNC_REDIS_TO_DB/REBUILD_STOCK_KEY',
    before_stock INT             NULL COMMENT '修复前库存（key 缺失时为空）',
    after_stock  INT             NOT NULL COMMENT '修复后库存',
    detail       VARCHAR(255)    NULL COMMENT '修复口径说明',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_voucher_time (voucher_id, create_time DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀一致性修复审计';
