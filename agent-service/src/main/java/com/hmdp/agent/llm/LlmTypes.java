package com.hmdp.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求/响应（OpenAI 协议抽象，D1.2 §3.1）
 */
public class LlmTypes {

    private LlmTypes() {
    }

    @Data
    @Builder
    public static class Request {
        private String model;
        private List<Message> messages;
        /** JSON mode：强制输出 JSON 对象（R2 结构化输出） */
        private boolean jsonMode;
        private double temperature;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /** system / user / assistant */
        private String role;
        private String content;

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    @Data
    @Builder
    public static class Response {
        private String content;
        /** 本次消耗 token 数（成本统计 R5） */
        private Long promptTokens;
        private Long completionTokens;
    }

    /** 非流式调用异常 */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseJsonArray(String content, String key) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            com.fasterxml.jackson.databind.JsonNode arr = root.get(key);
            if (arr == null || !arr.isArray()) return List.of();
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(arr, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
