package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.entity.AuditLog;

import java.util.Map;

public interface IAuditService {
    void log(Long userId, String action, String resourceType, Long resourceId, Map<String, String> detail, String ip);
    Page<AuditLog> query(Long userId, Long kbId, String action, int pageNum, int pageSize);
}
