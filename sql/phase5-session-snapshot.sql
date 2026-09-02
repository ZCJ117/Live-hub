-- Phase 5 P1 功能迁移（T5.3 FR-13 会话回放快照）
-- 快照存储：会话关闭/转人工确认时固化 消息+卡片（静态回放，工具过程不回放）
CREATE TABLE IF NOT EXISTS agent_service.agent_session_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL COMMENT '会话ID（唯一：快照会话生命周期内仅固化一次）',
  snapshot_json LONGTEXT COMMENT '快照JSON {messages,cards,summary,msgCount,closedAt}',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话回放快照（FR-13）';
