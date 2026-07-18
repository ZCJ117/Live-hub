package com.hmdp.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.rag.config.GlmApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GlmEmbeddingClient implements IEmbeddingClient {

    private final GlmApiConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        try {
            String requestBody = objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "model", config.getEmbeddingModel(),
                            "input", texts
                    ));

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/embeddings")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        if (isRetryable(response.code()) && attempt < config.getMaxRetries()) {
                            long waitMs = (long) Math.pow(2, attempt) * 1000;
                            log.warn("Embedding API retry {}/{}: HTTP {}, waiting {}ms",
                                    attempt, config.getMaxRetries(), response.code(), waitMs);
                            Thread.sleep(waitMs);
                            continue;
                        }
                        throw new IOException("Embedding API error: HTTP " + response.code() +
                                " body=" + (response.body() != null ? response.body().string() : ""));
                    }

                    JsonNode root = objectMapper.readTree(response.body().string());
                    JsonNode data = root.get("data");
                    if (data != null) {
                        for (JsonNode item : data) {
                            JsonNode embeddingArray = item.get("embedding");
                            float[] vec = new float[embeddingArray.size()];
                            for (int i = 0; i < embeddingArray.size(); i++) {
                                vec[i] = (float) embeddingArray.get(i).asDouble();
                            }
                            embeddings.add(vec);
                        }
                    }
                    return embeddings;
                }
            }
        } catch (Exception e) {
            log.error("Embedding API call failed after {} retries", config.getMaxRetries(), e);
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
        return embeddings;
    }

    private boolean isRetryable(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503;
    }

    /**
     * Convert float[] to pgvector-compatible string: '[0.1,0.2,...]'
     */
    public static String toPgVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
