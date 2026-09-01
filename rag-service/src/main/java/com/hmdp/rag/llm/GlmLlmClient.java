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

    //NOTE 2,8,a 虚拟线程 + OkHttp 阻塞式读取 SSE 数据流
    //spring WebFlux + Reactor 实现流式聊天，使用 OkHttp 发送请求并处理 SSE（Server-Sent Events）响应。
    @Override
    public Flux<String> streamChat(String systemPrompt, List<ChatMessage> history, String userQuestion) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        Thread.startVirtualThread(() -> {
            try {
                //NOTE 这里是构建消息列表，包括系统提示、历史消息和用户问题，然后发送到GLM API的聊天接口，并以流式方式接收响应。
                List<Map<String, String>> messages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(Map.of("role", "system", "content", systemPrompt));
                }
                if (history != null) {
                    for (ChatMessage msg : history) {
                        messages.add(Map.of("role",
                                msg.getRole(), "content",
                                msg.getContent()));
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

        //NOTE 2,8,b spring webflux的Controller返回Flux<String>,意味着它期望一个非阻塞的反应式管道。
        // 但 OkHttp 是阻塞 IO。如何把这两者连起来？
        // Sinks.Many（反应式水槽）+ 虚拟线程。Sinks.Many 就像一个"水管"——虚拟线程在管子一头往里灌水（sink.emitNext("水")），
        // Reactor Flux 在管子另一头接水（前端收到 SSE 消息）。
        //把Sinks.Many转换为Flux返回给前端
        return sink.asFlux();
    }
}
