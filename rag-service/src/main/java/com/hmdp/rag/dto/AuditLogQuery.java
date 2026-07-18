package com.hmdp.rag.dto;

import lombok.Data;

@Data
public class AuditLogQuery {
    private Long userId;
    private Long kbId;
    private String action;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
