package com.hmdp.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateKbRequest {
    @NotNull(message = "商户ID不能为空")
    private Long merchantId;
    @NotBlank(message = "知识库名称不能为空")
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
