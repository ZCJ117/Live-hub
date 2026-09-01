-- =====================================================================
-- Phase 4：每日对账 —— 退款受理数 vs 确认卡片数（P4-R1 补偿网）
-- 口径：当日 ADOPTED 的 agent_task 数 == 当日 order 库 status=5 的新增退款数
-- =====================================================================
USE agent_service;
SELECT DATE(confirm_time) AS d, COUNT(*) AS adopted_tasks
FROM agent_task
WHERE task_type = 'REFUND_REQUEST' AND status = 'ADOPTED'
GROUP BY DATE(confirm_time)
ORDER BY d DESC;

USE hmdp;
SELECT DATE(refund_time) AS d, COUNT(*) AS refunded_orders
FROM tb_voucher_order
WHERE status = 5
GROUP BY DATE(refund_time)
ORDER BY d DESC;
-- 两口径按日比对，差值 > 0 需人工核查（退款成功但建卡/确认缺失，或反之）
