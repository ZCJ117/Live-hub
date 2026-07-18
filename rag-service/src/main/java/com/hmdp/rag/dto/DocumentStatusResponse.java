package com.hmdp.rag.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentStatusResponse {
    private Long id;
    private String filename;
    private String fileType;
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createdAt;
}
