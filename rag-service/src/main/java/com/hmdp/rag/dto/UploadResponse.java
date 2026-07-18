package com.hmdp.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UploadResponse {
    private Long documentId;
    private String status;
    private String message;
}
