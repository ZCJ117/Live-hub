-- =====================================================================
-- Phase 4：站内信表（FR-09 工单通知，social-service 消费 agent-m5-ticket-route）
-- =====================================================================
USE hmdp;

CREATE TABLE IF NOT EXISTS tb_notification (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    user_id     BIGINT       NOT NULL COMMENT '接收用户',
    type        VARCHAR(32)  NOT NULL COMMENT 'TICKET=工单通知（预留扩展）',
    title       VARCHAR(128) NOT NULL,
    content     VARCHAR(512) NOT NULL,
    related_id  BIGINT       NULL COMMENT '关联业务ID（工单ID）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB COMMENT '站内信';
