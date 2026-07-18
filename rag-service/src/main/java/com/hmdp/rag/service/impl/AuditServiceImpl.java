package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.rag.entity.AuditLog;
import com.hmdp.rag.repository.AuditLogMapper;
import com.hmdp.rag.service.IAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements IAuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void log(Long userId, String action, String resourceType, Long resourceId,
                    Map<String, String> detail, String ip) {
        try {
            AuditLog logEntry = new AuditLog();
            logEntry.setUserId(userId);
            logEntry.setAction(action);
            logEntry.setResourceType(resourceType);
            logEntry.setResourceId(resourceId);
            logEntry.setDetail(objectMapper.writeValueAsString(detail));
            logEntry.setIp(ip);
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("Failed to write audit log: action={}, userId={}", action, userId, e);
        }
    }

    @Override
    public Page<AuditLog> query(Long userId, Long kbId, String action, int pageNum, int pageSize) {
        Page<AuditLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) wrapper.eq(AuditLog::getUserId, userId);
        if (action != null) wrapper.eq(AuditLog::getAction, action);
        wrapper.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(page, wrapper);
    }
}
