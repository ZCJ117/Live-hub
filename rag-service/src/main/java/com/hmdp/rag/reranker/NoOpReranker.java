package com.hmdp.rag.reranker;

import com.hmdp.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NoOpReranker implements IReranker {
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        return chunks.size() <= topK ? chunks : chunks.subList(0, topK);
    }
}
