package com.hmdp.rag.retriever;

import com.hmdp.rag.dto.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class RrfFusion {

    @Value("${rag.retrieval.rrf-k:60}")
    private int k;

    public List<RetrievedChunk> merge(List<RetrievedChunk> vectorResults,
                                       List<RetrievedChunk> keywordResults,
                                       int topK) {
        Map<Long, RetrievedChunk> chunkMap = new LinkedHashMap<>();
        Map<Long, Double> rrfScores = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievedChunk c = vectorResults.get(i);
            chunkMap.put(c.getId(), c);
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(c.getId(), score, Double::sum);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            RetrievedChunk c = keywordResults.get(i);
            chunkMap.putIfAbsent(c.getId(), c);
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(c.getId(), score, Double::sum);
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    RetrievedChunk c = chunkMap.get(e.getKey());
                    c.setScore(e.getValue());
                    return c;
                })
                .collect(Collectors.toList());
    }
}
