-- =====================================================================
-- agent_service 库 DDL v1.0
-- 对应设计文档：docs/dev-plans/phase1-deliverables/D1.3-数据模型设计文档.md
-- 依据：PRD-智能客服工单Agent.md v1.0 §6.1（四张表 + 三个补充字段）
-- 执行方式：Phase 2 T2.1 在 MySQL 既有实例上创建独立 schema
-- =====================================================================

CREATE DATABASE IF NOT EXISTS agent_service DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE agent_service;

-- ---------------------------------------------------------------------
-- 1. agent_session 会话主表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session (
    id              BIGINT       NOT NULL COMMENT '雪花ID，即 sessionId',
    user_id         BIGINT       NOT NULL COMMENT '归属用户（Sa-Token 强绑定）',
    module          VARCHAR(8)   NOT NULL DEFAULT 'M5' COMMENT '模块标识',
    status          VARCHAR(16)  NOT NULL COMMENT 'ACTIVE/CLOSED/TRANSFERRED',
    entry           VARCHAR(16)  NOT NULL COMMENT '入口：my/order_detail/voucher_detail',
    context_json    JSON         NULL COMMENT '入口预注入 {orderId|voucherId|shopId}',
    summary         VARCHAR(1024) NULL COMMENT '第11轮起 LLM 压缩摘要（≤512字）',
    flow_state      VARCHAR(16)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/CLARIFYING/REFUNDING/COMPLAINING/TRANSFERRING',
    rating          TINYINT      NULL COMMENT '评价 1-5，NULL=未评价（PRD 补充字段）',
    rating_tags     VARCHAR(128) NULL COMMENT '不满意多选标签，逗号分隔',
    transfer_reason VARCHAR(32)  NULL COMMENT 'HUMAN_DEMAND/TOOL_FAIL/CLARIFY_EXCEED/NEGATIVE_EMOTION（PRD 补充字段）',
    token_cost      DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '会话累计成本（元）',
    msg_count       INT          NOT NULL DEFAULT 0 COMMENT '单会话消息数（频控上限100）',
    snapshot_uri    VARCHAR(256) NULL COMMENT '回放快照存储引用（Phase 5 使用）',
    close_reason    VARCHAR(16)  NULL COMMENT 'USER/TIMEOUT/TRANSFER',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_user_time (user_id, create_time),
    KEY idx_status_close (status, update_time)
) ENGINE = InnoDB COMMENT 'Agent 会话主表';

-- ---------------------------------------------------------------------
-- 2. agent_tool_call 工具调用审计表（仅追加：应用账号仅授予 INSERT/SELECT）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_tool_call (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    session_id     BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    tool_name      VARCHAR(64)  NOT NULL COMMENT 'query_my_orders/query_voucher/query_shop/search_shop_by_name/kb_search/create_ticket',
    args_json      JSON         NULL COMMENT '入参（脱敏后）',
    result_summary VARCHAR(1024) NULL COMMENT '结果摘要（脱敏后）',
    success        TINYINT      NOT NULL COMMENT '1成功/0失败',
    latency_ms     INT          NOT NULL COMMENT '耗时毫秒',
    trace_id       VARCHAR(64)  NOT NULL COMMENT '全链路追踪ID',
    step_no        TINYINT      NULL COMMENT 'ReAct 步数（1-8）',
    error_code     VARCHAR(32)  NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, create_time)
) ENGINE = InnoDB COMMENT '工具调用审计（仅追加，不可篡改）';

-- 审计不可篡改：应用账号仅授 INSERT/SELECT（账号名按环境替换）
-- REVOKE UPDATE, DELETE ON agent_service.agent_tool_call FROM 'agent_app'@'%';

-- ---------------------------------------------------------------------
-- 3. agent_task 确认卡片/退款申请表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_task (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    session_id     BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    task_type      VARCHAR(32)  NOT NULL COMMENT 'REFUND_REQUEST（预留扩展）',
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING_CONFIRM/ADOPTED/REJECTED/EXPIRED',
    action_id      VARCHAR(64)  NOT NULL COMMENT '幂等凭证，一次性消费',
    payload_json   JSON         NULL COMMENT '卡片内容（订单摘要/原因选项/预计时效）',
    confirm_reason VARCHAR(256) NULL COMMENT '用户选择的退款原因',
    expire_time    DATETIME     NOT NULL COMMENT '生成时间+10分钟',
    ticket_id      BIGINT       NULL COMMENT 'ADOPTED 后联动的复核工单ID',
    confirm_time   DATETIME     NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_id (action_id),
    KEY idx_session_status (session_id, status)
) ENGINE = InnoDB COMMENT '确认卡片/退款申请';

-- ---------------------------------------------------------------------
-- 4. agent_ticket 工单表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_ticket (
    id             BIGINT       NOT NULL COMMENT '雪花ID',
    ticket_no      VARCHAR(24)  NOT NULL COMMENT '工单号 TK+yyyyMMdd+6位序号（Redis INCR 按日生成）',
    session_id     BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    category       VARCHAR(16)  NOT NULL COMMENT 'ORDER/VOUCHER/MERCHANT_SERVICE/ACCOUNT_SECURITY/OTHER',
    priority       VARCHAR(8)   NOT NULL COMMENT 'HIGH/MEDIUM/LOW（涉资金=高，普通=中，建议=低）',
    summary        VARCHAR(256) NOT NULL COMMENT 'LLM ≤200字客观摘要（失败时模板兜底）',
    refs_json      JSON         NULL COMMENT '{orderId?, voucherId?, shopId?}',
    dedup_key      VARCHAR(64)  NOT NULL COMMENT 'MD5(category+sorted(refs)+sessionId)（PRD 补充字段）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN→ROUTED→RESOLVED（非法跳转应用层拒绝）',
    assignee_group VARCHAR(16)  NOT NULL COMMENT 'ORDER_GROUP/MARKETING_GROUP/MERCHANT_GROUP/SECURITY_GROUP',
    expected_sla   VARCHAR(8)   NOT NULL COMMENT '4h/24h/72h（写入时按 priority 固化）',
    notify_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'MQ 通知：PENDING/SENT/FAILED',
    handle_result  VARCHAR(512) NULL COMMENT 'RESOLVED 时的处理备注（P2 工作台填写）',
    resolve_time   DATETIME     NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    UNIQUE KEY uk_dedup_key (dedup_key),
    KEY idx_group_priority_status (assignee_group, priority, status),
    KEY idx_user (user_id),
    KEY idx_session (session_id)
) ENGINE = InnoDB COMMENT '客服工单';

-- ---------------------------------------------------------------------
-- 归档表结构说明：*_archive 与主表同构（Phase 5 T5.3 建表），依赖 create_time 归档
-- ---------------------------------------------------------------------
