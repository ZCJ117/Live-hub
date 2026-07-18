package com.hmdp.rag.retriever;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class HybridRetriever implements IRetriever {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RrfFusion rrfFusion;
    private final RagProperties ragProperties;

    @Override
    public List<RetrievedChunk> retrieve(String query, Long kbId, int topK) {
        int fetchK = topK * 2;

        List<RetrievedChunk> vectorResults = vectorRetriever.retrieve(query, kbId, fetchK);
        List<RetrievedChunk> keywordResults = keywordRetriever.retrieve(query, kbId, fetchK);

        List<RetrievedChunk> merged = rrfFusion.merge(vectorResults, keywordResults, topK);
        log.info("Hybrid search: vector={}, keyword={}, merged={}, kbId={}",
                vectorResults.size(), keywordResults.size(), merged.size(), kbId);

        return merged;
    }
}
