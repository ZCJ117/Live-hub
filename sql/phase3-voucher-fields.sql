-- Phase 3 T3.8/C2：tb_voucher 补充使用门槛与适用范围字段（D1.4 §3）
-- 可空设计：未录入 = NULL，agent-service 按"规则暂未录入"话术处理，禁止默认值编造语义
ALTER TABLE tb_voucher
    ADD COLUMN threshold DECIMAL(10,2) NULL COMMENT '使用门槛（满X元可用），NULL=未录入' AFTER actual_value,
    ADD COLUMN applicable_scope VARCHAR(512) NULL COMMENT '适用范围：店铺ID列表JSON，NULL=未录入' AFTER threshold;
