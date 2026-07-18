package com.hmdp.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Conversation conversation = new Conversation();
    private Upload upload = new Upload();

    @Data
    public static class Chunk {
        private int defaultSize = 500;
        private int defaultOverlap = 50;
    }

    @Data
    public static class Retrieval {
        private int defaultTopK = 5;
        private int maxTopK = 20;
        private int rrfK = 60;
    }

    @Data
    public static class Conversation {
        private int maxHistoryTurns = 10;
    }

    @Data
    public static class Upload {
        private String dir = "./data/rag-uploads";
        private String maxSize = "20MB";
    }
}
