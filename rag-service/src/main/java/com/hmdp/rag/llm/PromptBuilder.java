package com.hmdp.rag.llm;

import com.hmdp.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    public String buildSystemPrompt(List<RetrievedChunk> chunks) {
        return "你是一个美食点评平台的智能客服助手。请基于以下餐厅知识库内容回答用户问题。" +
                "如果知识库中没有相关信息，请如实告知"没有找到相关信息"。\n\n" +
                "知识库参考内容：\n" + formatChunks(chunks);
    }

    public String formatChunks(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(c -> "【来源 " + c.getId() + "】" + c.getContent())
                .collect(Collectors.joining("\n\n"));
    }
}
