package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
