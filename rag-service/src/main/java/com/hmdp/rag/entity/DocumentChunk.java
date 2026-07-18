package com.hmdp.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentChunk {
    private Long id;
    private Long documentId;
    private Long kbId;
    private Integer chunkIndex;
    private String content;
    private String metadata;
    private LocalDateTime createdAt;
}
