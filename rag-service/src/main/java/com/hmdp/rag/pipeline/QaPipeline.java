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

    public Flux<String> answer(String question, Long kbId, Integer topK, Boolean enableRerank,
                                List<ChatMessage> history) {
        int k = topK != null ? Math.min(topK, ragProperties.getRetrieval().getMaxTopK())
                : ragProperties.getRetrieval().getDefaultTopK();
        boolean doRerank = enableRerank != null && enableRerank;

        List<RetrievedChunk> chunks = hybridRetriever.retrieve(question, kbId, k);
        log.info("Retrieved {} chunks for question: {}", chunks.size(), question);

        if (doRerank && chunks.size() > 1) {
            chunks = reranker.rerank(question, chunks, k);
            log.info("Reranked to {} chunks", chunks.size());
        }

        String systemPrompt = promptBuilder.buildSystemPrompt(chunks);
        String sourcesJson = buildSourcesJson(chunks);
        int maxHistory = ragProperties.getConversation().getMaxHistoryTurns();
        List<ChatMessage> trimmedHistory = (history != null && history.size() > maxHistory * 2)
                ? history.subList(history.size() - maxHistory * 2, history.size())
                : history;

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
