-- =====================================================================
-- agent_service DDL（Phase 2 T2.1）
-- 对应设计：docs/dev-plans/phase1-deliverables/D1.3-数据模型设计文档.md
-- =====================================================================

CREATE DATABASE IF NOT EXISTS agent_service DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE agent_service;

-- 1. 会话主表
CREATE TABLE IF NOT EXISTS agent_session (
    id              BIGINT        NOT NULL COMMENT '雪花ID，即 sessionId',
    user_id         BIGINT        NOT NULL COMMENT '归属用户（Sa-Token 强绑定）',
    module          VARCHAR(8)    NOT NULL DEFAULT 'M5' COMMENT '模块标识',
    status          VARCHAR(16)   NOT NULL COMMENT 'ACTIVE/CLOSED/TRANSFERRED',
    entry           VARCHAR(16)   NOT NULL COMMENT '入口：my/order_detail/voucher_detail',
    context_json    VARCHAR(1024) NULL COMMENT '入口预注入 {orderId|voucherId|shopId}',
    summary         VARCHAR(1024) NULL COMMENT '第11轮起 LLM 压缩摘要（≤512字）',
    flow_state      VARCHAR(16)   NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/CLARIFYING/REFUNDING/COMPLAINING/TRANSFERRING',
    rating          TINYINT       NULL COMMENT '评价 1-5，NULL=未评价',
    rating_tags     VARCHAR(128)  NULL COMMENT '不满意多选标签，逗号分隔',
    transfer_reason VARCHAR(32)   NULL COMMENT 'HUMAN_DEMAND/TOOL_FAIL/CLARIFY_EXCEED/NEGATIVE_EMOTION',
    token_cost      DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '会话累计成本（元）',
    msg_count       INT           NOT NULL DEFAULT 0 COMMENT '单会话消息数（上限100）',
    snapshot_uri    VARCHAR(256)  NULL COMMENT '回放快照引用（Phase 5）',
    close_reason    VARCHAR(16)   NULL COMMENT 'USER/TIMEOUT/TRANSFER',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_user_time (user_id, create_time),
    KEY idx_status_close (status, update_time)
) ENGINE = InnoDB COMMENT 'Agent 会话主表';

-- 2. 工具调用审计表（仅追加：生产环境应用账号 REVOKE UPDATE, DELETE）
CREATE TABLE IF NOT EXISTS agent_tool_call (
    id             BIGINT        NOT NULL COMMENT '雪花ID',
    session_id     BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    tool_name      VARCHAR(64)   NOT NULL,
    args_json      TEXT          NULL COMMENT '入参（脱敏后）',
    result_summary VARCHAR(1024) NULL COMMENT '结果摘要（脱敏后）',
    success        TINYINT       NOT NULL COMMENT '1成功/0失败',
    latency_ms     INT           NOT NULL,
    trace_id       VARCHAR(64)   NOT NULL,
    step_no        TINYINT       NULL COMMENT 'ReAct 步数（1-8）',
    error_code     VARCHAR(32)   NULL,
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, create_time)
) ENGINE = InnoDB COMMENT '工具调用审计（仅追加，不可篡改）';

-- 3. 确认卡片/退款申请表（Phase 4 使用，本阶段建表）
CREATE TABLE IF NOT EXISTS agent_task (
    id             BIGINT        NOT NULL,
    session_id     BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    task_type      VARCHAR(32)   NOT NULL COMMENT 'REFUND_REQUEST（预留扩展）',
    status         VARCHAR(20)   NOT NULL COMMENT 'PENDING_CONFIRM/ADOPTED/REJECTED/EXPIRED',
    action_id      VARCHAR(64)   NOT NULL COMMENT '幂等凭证，一次性消费',
    payload_json   TEXT          NULL,
    confirm_reason VARCHAR(256)  NULL,
    expire_time    DATETIME      NOT NULL COMMENT '生成时间+10分钟',
    ticket_id      BIGINT        NULL COMMENT 'ADOPTED 后联动的复核工单ID',
    confirm_time   DATETIME      NULL,
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_id (action_id),
    KEY idx_session_status (session_id, status)
) ENGINE = InnoDB COMMENT '确认卡片/退款申请';

-- 4. 工单表
CREATE TABLE IF NOT EXISTS agent_ticket (
    id             BIGINT        NOT NULL,
    ticket_no      VARCHAR(24)   NOT NULL COMMENT 'TK+yyyyMMdd+6位序号',
    session_id     BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    category       VARCHAR(16)   NOT NULL COMMENT 'ORDER/VOUCHER/MERCHANT_SERVICE/ACCOUNT_SECURITY/OTHER',
    priority       VARCHAR(8)    NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
    summary        VARCHAR(256)  NOT NULL COMMENT '≤200字客观摘要',
    refs_json      VARCHAR(512)  NULL COMMENT '{orderId?, voucherId?, shopId?}',
    dedup_key      VARCHAR(64)   NOT NULL COMMENT 'MD5(category+sorted(refs)+sessionId)',
    status         VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN→ROUTED→RESOLVED',
    assignee_group VARCHAR(16)   NOT NULL COMMENT 'ORDER_GROUP/MARKETING_GROUP/MERCHANT_GROUP/SECURITY_GROUP',
    expected_sla   VARCHAR(8)    NOT NULL COMMENT '4h/24h/72h',
    notify_status  VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    handle_result  VARCHAR(512)  NULL COMMENT 'RESOLVED 时处理备注',
    resolve_time   DATETIME      NULL,
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    UNIQUE KEY uk_dedup_key (dedup_key),
    KEY idx_group_priority_status (assignee_group, priority, status),
    KEY idx_user (user_id),
    KEY idx_session (session_id)
) ENGINE = InnoDB COMMENT '客服工单';

-- 5. 埋点事件表（D1.8 埋点字典，服务端直写事件）
CREATE TABLE IF NOT EXISTS track_event (
    id           BIGINT       NOT NULL COMMENT '雪花ID',
    event_name   VARCHAR(48)  NOT NULL COMMENT 'm5_session_start 等',
    session_id   BIGINT       NULL,
    user_id      BIGINT       NULL,
    props_json   VARCHAR(1024) NULL COMMENT '事件属性 JSON',
    server_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端时间（口径基准）',
    PRIMARY KEY (id),
    KEY idx_event_time (event_name, server_time),
    KEY idx_session (session_id)
) ENGINE = InnoDB COMMENT '业务埋点事件（服务端直写）';
