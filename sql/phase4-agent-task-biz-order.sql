-- =====================================================================
-- Phase 4：agent_task 补业务订单列（同订单重复退款 O(1) 查询，FR-08 T4.4）
-- =====================================================================
USE agent_service;

ALTER TABLE agent_task
    ADD COLUMN biz_order_id BIGINT NULL COMMENT '业务订单ID（task_type=REFUND_REQUEST 时）' AFTER payload_json,
    ADD KEY idx_order_status (biz_order_id, status);
