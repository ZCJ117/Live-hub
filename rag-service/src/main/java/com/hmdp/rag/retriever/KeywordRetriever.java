package com.hmdp.rag.retriever;

import com.hmdp.rag.dto.RetrievedChunk;
import com.hmdp.rag.entity.DocumentChunk;
import com.hmdp.rag.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeywordRetriever implements IRetriever {

    private final DocumentChunkRepository chunkRepo;

    @Override
    public List<RetrievedChunk> retrieve(String query, Long kbId, int topK) {
        List<DocumentChunk> chunks = chunkRepo.keywordSearch(query, kbId, topK);
        log.debug("Keyword search returned {} chunks for kbId={}", chunks.size(), kbId);

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
