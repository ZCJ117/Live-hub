package com.hmdp.rag.pipeline;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.ChatMessage;
import com.hmdp.rag.dto.RetrievedChunk;
import com.hmdp.rag.llm.ILlmClient;
import com.hmdp.rag.llm.PromptBuilder;
import com.hmdp.rag.reranker.IReranker;
import com.hmdp.rag.retriever.HybridRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;


@Component
@RequiredArgsConstructor
@Slf4j
public class QaPipeline {

    private final HybridRetriever hybridRetriever;
    private final IReranker reranker;
    private final ILlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final RagProperties ragProperties;

    //NOTE 2,3 QaPipeline.answer() 方法：问答流程的核心逻辑,总指挥，参数归一化，调用检索器、重排序器、提示词构建器和 LLM 客户端，返回 Flux 流式结果。
    //answer 方法负责问答流程的核心逻辑：检索、重排序、构建提示词、调用 LLM 生成答案，并以 Flux 流的形式返回结果。
    public Flux<String> answer(String question, Long kbId, Integer topK, Boolean enableRerank,
                                List<ChatMessage> history) {
        //这里是参数归一化处理，确保 topK 不超过最大值，并设置默认值，同时判断是否启用重排序。
        int k = topK != null ? Math.min(topK, ragProperties.getRetrieval().getMaxTopK())
                : ragProperties.getRetrieval().getDefaultTopK();
        boolean doRerank = enableRerank != null && enableRerank;

        //NOTE 2,4 调用 HybridRetriever 检索相关知识块，返回 RetrievedChunk 列表。
        List<RetrievedChunk> chunks = hybridRetriever.retrieve(question, kbId, k);
        log.info("Retrieved {} chunks for question: {}", chunks.size(), question);

        //NOTE 2,5 这里调用reranker方法 可选重排序
        if (doRerank && chunks.size() > 1) {
            chunks = reranker.rerank(question, chunks, k);
            log.info("Reranked to {} chunks", chunks.size());
        }

        //NOTE 2,6 拼接提示词，把检索到的 5 个 chunk 拼成一段"知识参考"，
        // 再加上角色指令，组合成发给 LLM 的 system prompt（系统提示词）
        String systemPrompt = promptBuilder.buildSystemPrompt(chunks);
        String sourcesJson = buildSourcesJson(chunks);
        //NOTE 2,7 这里是对历史消息进行裁剪，确保不会超过最大历史轮数，避免上下文过长。
        int maxHistory = ragProperties.getConversation().getMaxHistoryTurns();
        List<ChatMessage> trimmedHistory = (history != null && history.size() > maxHistory * 2)
                ? history.subList(history.size() - maxHistory * 2, history.size())
                : history;

        //NOTE 2,8 这里是调用 llmClient.streamChat() 方法，传入系统提示词、历史消息和用户问题，返回 Flux 流式结果。
        return Mono.just("event: sources\n" + sourcesJson + "\n\n")
                .concatWith(llmClient.streamChat(systemPrompt, trimmedHistory, question)
                        .map(delta -> delta));
    }

    private String buildSourcesJson(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("data: {\"type\":\"sources\",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append(",");
            RetrievedChunk c = chunks.get(i);
            String safeContent = c.getContent() != null
                    ? c.getContent().replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                    : "";
            sb.append(String.format(
                    "{\"id\":%d,\"content\":\"%s\",\"score\":%.4f}",
                    c.getId(), safeContent, c.getScore() != null ? c.getScore() : 0));
        }
        sb.append("]}");
        return sb.toString();
    }
}
