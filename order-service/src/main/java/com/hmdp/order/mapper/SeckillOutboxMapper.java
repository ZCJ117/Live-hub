package com.hmdp.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.order.entity.SeckillOutbox;

/**
 * 秒杀本地事件表 Mapper（SPEC-15 P2-1）
 *
 * <p>由 {@code @MapperScan("com.hmdp.order.mapper")} 扫描，无需额外注册
 * （与 {@code SeckillConsistencyAuditMapper} 同）。
 */
public interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox> {
}
