package com.hmdp.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    @NotNull(message = "知识库ID不能为空")
    private Long kbId;
    @NotBlank(message = "问题不能为空")
    private String question;
    private List<ChatMessage> messages;
    private Integer topK;
    private Boolean enableRerank;
    private Long merchantId;
}
