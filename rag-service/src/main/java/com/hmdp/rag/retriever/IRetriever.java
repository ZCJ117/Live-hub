package com.hmdp.rag.retriever;

import com.hmdp.rag.dto.RetrievedChunk;

import java.util.List;

public interface IRetriever {
    List<RetrievedChunk> retrieve(String query, Long kbId, int topK);
}
