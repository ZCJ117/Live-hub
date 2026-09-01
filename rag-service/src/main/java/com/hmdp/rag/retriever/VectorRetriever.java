package com.hmdp.rag.retriever;

import com.hmdp.rag.dto.RetrievedChunk;
import com.hmdp.rag.embedding.GlmEmbeddingClient;
import com.hmdp.rag.entity.DocumentChunk;
import com.hmdp.rag.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

//NOTE 2,4,a 把问题 query 转成向量 embedding ,用PgVector 做向量检索，找最相似的10个chunk
// 返回 RetrievedChunk 列表。
// VectorRetriever 负责将用户的查询问题转换为向量表示，并使用向量数据库（如 PostgreSQL 的 pgvector）进行相似度搜索，从而检索出与查询最相关的文档块。

@Component
@RequiredArgsConstructor
@Slf4j
public class VectorRetriever implements IRetriever {

    private final DocumentChunkRepository chunkRepo;
    private final GlmEmbeddingClient embeddingClient;

    @Override
    public List<RetrievedChunk> retrieve(String query, Long kbId, int topK) {
        List<float[]> embeddings = embeddingClient.embed(List.of(query));
        if (embeddings.isEmpty()) return List.of();

        String embeddingStr = GlmEmbeddingClient.toPgVectorString(embeddings.get(0));
        List<DocumentChunk> chunks = chunkRepo.vectorSearch(embeddingStr, kbId, topK);
        log.debug("Vector search returned {} chunks for kbId={}", chunks.size(), kbId);

        List<RetrievedChunk> results = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            results.add(RetrievedChunk.builder()
                    .id(c.getId())
                    .content(c.getContent())
                    .metadata(c.getMetadata())
                    .score(1.0 - i * 0.05)
                    .build());
        }
        return results;
    }
}
