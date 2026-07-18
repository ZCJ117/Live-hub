package com.hmdp.rag.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KbResponse {
    private Long id;
    private Long merchantId;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer documentCount;
    private LocalDateTime createdAt;
}
