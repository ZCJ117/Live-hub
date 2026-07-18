package com.hmdp.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.rag.config.GlmApiConfig;
import com.hmdp.rag.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GlmLlmClient implements ILlmClient {

    private final GlmApiConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public Flux<String> streamChat(String systemPrompt, List<ChatMessage> history, String userQuestion) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        Thread.startVirtualThread(() -> {
            try {
                List<Map<String, String>> messages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(Map.of("role", "system", "content", systemPrompt));
                }
                if (history != null) {
                    for (ChatMessage msg : history) {
                        messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
                    }
                }
                messages.add(Map.of("role", "user", "content", userQuestion));

                Map<String, Object> body = Map.of(
                        "model", config.getLlmModel(),
                        "messages", messages,
                        "stream", true
                );

                String json = objectMapper.writeValueAsString(body);
                Request request = new Request.Builder()
                        .url(config.getBaseUrl() + "/chat/completions")
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .post(RequestBody.create(json, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        log.error("LLM API error: HTTP {} body={}", response.code(), errorBody);
                        sink.emitError(new IOException("LLM API error: HTTP " + response.code()),
                                Sinks.EmitFailureHandler.FAIL_FAST);
                        return;
                    }

                    BufferedSource source = response.body().source();
                    String line;
                    while ((line = source.readUtf8Line()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                                return;
                            }
                            try {
                                JsonNode root = objectMapper.readTree(data);
                                JsonNode choices = root.get("choices");
                                if (choices != null && choices.size() > 0) {
                                    JsonNode delta = choices.get(0).get("delta");
                                    if (delta != null) {
                                        JsonNode content = delta.get("content");
                                        if (content != null && !content.asText().isEmpty()) {
                                            sink.emitNext(content.asText(),
                                                    Sinks.EmitFailureHandler.FAIL_FAST);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Skip non-json SSE line: {}", data);
                            }
                        }
                    }
                    sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
                }
            } catch (Exception e) {
                log.error("LLM streaming error", e);
                sink.emitError(e, Sinks.EmitFailureHandler.FAIL_FAST);
            }
        });

        return sink.asFlux();
    }
}
