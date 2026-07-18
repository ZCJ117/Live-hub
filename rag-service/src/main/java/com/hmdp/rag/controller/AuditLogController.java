package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.dto.AuditLogQuery;
import com.hmdp.rag.service.IAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final IAuditService auditService;

    @GetMapping
    public Result query(@ModelAttribute AuditLogQuery query) {
        return Result.ok(auditService.query(
                query.getUserId(), query.getKbId(), query.getAction(),
                query.getPageNum(), query.getPageSize()));
    }
}
