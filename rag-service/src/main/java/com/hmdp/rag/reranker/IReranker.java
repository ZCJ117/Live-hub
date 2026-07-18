package com.hmdp.rag.reranker;

import com.hmdp.rag.dto.RetrievedChunk;

import java.util.List;

public interface IReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
