package com.hmdp.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.order.entity.SeckillConsistencyAudit;

/**
 * 秒杀一致性修复审计 Mapper（SPEC-04 §5.5）
 *
 * <p>由 {@code @MapperScan("com.hmdp.order.mapper")} 扫描，无需额外注册。
 */
public interface SeckillConsistencyAuditMapper extends BaseMapper<SeckillConsistencyAudit> {
}
