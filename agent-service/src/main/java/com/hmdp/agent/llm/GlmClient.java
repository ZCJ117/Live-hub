package com.hmdp.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.GlmProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 协议兼容 LLM 客户端（GLM-5 / glm-4-flash，接口可替换 DeepSeek/通义）
 * 流式：虚拟线程 + OkHttp 阻塞读 SSE（沿用 rag-service 既有范式，PRD 4.1 异步约束）
 */
@Component
@Slf4j
public class GlmClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final GlmProperties props;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlmClient(GlmProperties props) {
        this.props = props;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .build();
    }

    /**
     * 流式对话：逐 delta 回调，最后返回完整文本与 token 统计
     * 注意：必须在非 Tomcat 工作线程调用（D1.2 §2.1 线程模型）
     */
    public StreamResult streamChat(LlmTypes.Request request, Consumer<String> onDelta) {
        Map<String, Object> body = baseBody(request);
        body.put("stream", true);
        return executeStream(request.getModel(), body, onDelta);
    }

    /** 非流式调用（意图分类 / 摘要 / 卡片参数） */
    public LlmTypes.Response complete(LlmTypes.Request request) {
        Map<String, Object> body = baseBody(request);
        body.put("stream", false);
        Request httpRequest = buildRequest(body);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new LlmTypes.LlmException("LLM API error: HTTP " + (response.code()));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode choices = root.path("choices");
            String content = choices.isEmpty() ? "" :
                    choices.get(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            return LlmTypes.Response.builder()
                    .content(content)
                    .promptTokens(usage.path("prompt_tokens").asLong(0))
                    .completionTokens(usage.path("completion_tokens").asLong(0))
                    .build();
        } catch (IOException e) {
            throw new LlmTypes.LlmException("LLM 调用失败", e);
        }
    }

    private Map<String, Object> baseBody(LlmTypes.Request request) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmTypes.Message m : request.getMessages()) {
            Map<String, String> msg = new HashMap<>();
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());
            messages.add(msg);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModel());
        body.put("messages", messages);
        if (request.isJsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (request.getTemperature() > 0) {
            body.put("temperature", request.getTemperature());
        }
        return body;
    }

    private Request buildRequest(Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmTypes.LlmException("请求序列化失败", e);
        }
        return new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .post(RequestBody.create(json, JSON))
                .build();
    }

    private StreamResult executeStream(String model, Map<String, Object> body, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        long[] tokens = {0, 0};
        Request request = buildRequest(body);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new LlmTypes.LlmException("LLM API error: HTTP " + response.code());
            }
            BufferedSource source = response.body().source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    JsonNode root = objectMapper.readTree(data);
                    JsonNode choices = root.path("choices");
                    if (!choices.isEmpty()) {
                        String delta = choices.get(0).path("delta").path("content").asText("");
                        if (!delta.isEmpty()) {
                            full.append(delta);
                            if (onDelta != null) {
                                onDelta.accept(delta);
                            }
                        }
                    }
                    JsonNode usage = root.path("usage");
                    if (usage.isObject()) {
                        tokens[0] = usage.path("prompt_tokens").asLong(tokens[0]);
                        tokens[1] = usage.path("completion_tokens").asLong(tokens[1]);
                    }
                } catch (Exception parseEx) {
                    log.warn("SSE chunk 解析失败（忽略）: {}", data);
                }
            }
        } catch (IOException e) {
            throw new LlmTypes.LlmException("LLM 流式调用失败", e);
        }
        return new StreamResult(full.toString(), tokens[0], tokens[1]);
    }

    public record StreamResult(String content, long promptTokens, long completionTokens) {
    }
}
