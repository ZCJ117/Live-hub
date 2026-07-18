# rag-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build rag-service — a RAG microservice for hm-dianping with document processing, hybrid search (PgVector + tsvector + RRF), and SSE-streamed QA using GLM-5.

**Architecture:** Layered strategy pattern — Controller → Service → Pipeline → Strategy interfaces (Parser/Splitter/Embedding/Retriever/Reranker/LLM), with RocketMQ-driven async document processing, PostgreSQL pgvector for vector+fulltext search, and Sa-Token for auth.

**Tech Stack:** Java 21 · Spring Boot 3.1.12 · Spring Cloud Alibaba 2022.0.0.0 · MyBatis Plus 3.5.6 · PgVector · Apache Tika 2.9 · OkHttp · RocketMQ 2.2.3 · Sa-Token 1.44.0 · Hutool 5.8.22 · SpringDoc OpenAPI 2.1

---

### Task 1: Project scaffolding — pom.xml, main class, config files

**Files:**
- Create: `rag-service/pom.xml`
- Create: `rag-service/src/main/java/com/hmdp/rag/RagApplication.java`
- Create: `rag-service/src/main/resources/bootstrap.yaml`
- Create: `rag-service/src/main/resources/application.yaml`
- Modify: `pom.xml` (root, add rag-service module)

- [ ] **Step 1: Add rag-service module to root pom.xml**

```xml
<!-- In root pom.xml, add after social-service module -->
<module>rag-service</module>
```

- [ ] **Step 2: Create rag-service/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.hmdp</groupId>
        <artifactId>hm-dianping</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>rag-service</artifactId>
    <name>rag-service</name>
    <description>RAG智能知识问答服务</description>

    <dependencies>
        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-pool2</artifactId>
        </dependency>

        <!-- WebFlux for SSE streaming -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- PostgreSQL + PgVector -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- MyBatis Plus -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>

        <!-- Nacos -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>

        <!-- OpenFeign + LoadBalancer -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>

        <!-- Apache Tika (document parsing) -->
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
            <version>2.9.2</version>
        </dependency>
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-parsers-standard-package</artifactId>
            <version>2.9.2</version>
        </dependency>

        <!-- OkHttp (HTTP client for GLM API) -->
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
        </dependency>

        <!-- RocketMQ -->
        <dependency>
            <groupId>org.apache.rocketmq</groupId>
            <artifactId>rocketmq-spring-boot-starter</artifactId>
        </dependency>

        <!-- Common -->
        <dependency>
            <groupId>com.hmdp</groupId>
            <artifactId>common</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>

        <!-- Sa-Token -->
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-spring-boot3-starter</artifactId>
            <version>${sa-token.version}</version>
        </dependency>
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-redis-jackson</artifactId>
            <version>${sa-token.version}</version>
        </dependency>

        <!-- Bootstrap -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bootstrap</artifactId>
            <version>4.1.2</version>
        </dependency>

        <!-- SpringDoc OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.1.0</version>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create RagApplication.java**

```java
package com.hmdp.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * RAG智能知识问答服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.hmdp.rag.repository")
@ComponentScan("com.hmdp")
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
```

- [ ] **Step 4: Create bootstrap.yaml**

```yaml
spring:
  application:
    name: rag-service
  cloud:
    nacos:
      server-addr: localhost:8848
      discovery:
        namespace: public
      config:
        namespace: public
        file-extension: yaml
        import-check:
          enabled: false
  config:
    import: optional:nacos:${spring.application.name}.yaml
```

- [ ] **Step 5: Create application.yaml**

```yaml
server:
  port: 8087

spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5433/rag_db
    username: ${RAG_DB_USER:rag_user}
    password: ${RAG_DB_PASSWORD}
  jackson:
    default-property-inclusion: non_null

mybatis-plus:
  type-aliases-package: com.hmdp.rag.entity

glm:
  api-key: ${GLM_API_KEY}
  base-url: https://open.bigmodel.cn/api/paas/v4
  llm-model: glm-4-flash
  embedding-model: embedding-2
  connect-timeout: 30s
  read-timeout: 120s
  max-retries: 3

rag:
  chunk:
    default-size: 500
    default-overlap: 50
  retrieval:
    default-top-k: 5
    max-top-k: 20
    rrf-k: 60
  conversation:
    max-history-turns: 10
  upload:
    dir: ${RAG_UPLOAD_DIR:./data/rag-uploads}
    max-size: 20MB

rocketmq:
  name-server: 127.0.0.1:9876
  consumer:
    group: rag-doc-process-group
    topic: rag-document-process

logging:
  level:
    com.hmdp.rag: debug

sa-token:
  token-name: Authorization
  timeout: 2592000
  active-timeout: -1
  is-concurrent: true
  is-share: true
  token-style: uuid
  is-log: false
```

- [ ] **Step 6: Verify project compiles**

Run: `cd D:/hm-dianping && mvn compile -pl rag-service -am`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add pom.xml rag-service/
git commit -m "feat(rag): add rag-service module scaffolding with pom.xml, config, main class"
```

---

### Task 2: Database initialization SQL

**Files:**
- Create: `sql/init-rag.sql`

- [ ] **Step 1: Create sql/init-rag.sql**

```sql
-- RAG Service 数据库初始化
-- PostgreSQL 16 + pgvector

-- 扩展
CREATE EXTENSION IF NOT EXISTS pgvector;

-- 尝试创建 zhparser（若不可用则回退使用 simple）
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS zhparser;
    CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
    ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l WITH simple;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'zhparser not available, using simple tokenizer';
END $$;

-- 知识库
CREATE TABLE IF NOT EXISTS rag_knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     BIGINT NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    chunk_size      INT DEFAULT 500,
    chunk_overlap   INT DEFAULT 50,
    embedding_model VARCHAR(100) DEFAULT 'embedding-2',
    created_by      BIGINT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kb_merchant ON rag_knowledge_base(merchant_id);

-- 文档
CREATE TABLE IF NOT EXISTS rag_document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    filename    VARCHAR(255) NOT NULL,
    file_type   VARCHAR(20) NOT NULL,
    file_size   BIGINT,
    file_path   VARCHAR(500),
    status      VARCHAR(20) DEFAULT 'PROCESSING',
    chunk_count INT DEFAULT 0,
    error_msg   TEXT,
    uploaded_by BIGINT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON rag_document(kb_id);

-- 分块（核心检索表）
CREATE TABLE IF NOT EXISTS rag_document_chunk (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    kb_id       BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1024),
    tsv         tsvector,
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON rag_document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON rag_document_chunk USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON rag_document_chunk(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON rag_document_chunk(document_id);

-- 审计日志
CREATE TABLE IF NOT EXISTS rag_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    action        VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id   BIGINT,
    detail        JSONB DEFAULT '{}',
    ip            VARCHAR(45),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user ON rag_audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_time ON rag_audit_log(created_at DESC);
```

- [ ] **Step 2: Commit**

```bash
git add sql/init-rag.sql
git commit -m "feat(rag): add PostgreSQL init SQL with pgvector and zhparser"
```

---

### Task 3: Entity classes

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/entity/KnowledgeBase.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/entity/Document.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/entity/DocumentChunk.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/entity/AuditLog.java`

- [ ] **Step 1: Create KnowledgeBase.java**

```java
package com.hmdp.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_knowledge_base")
public class KnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String embeddingModel;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create Document.java**

```java
package com.hmdp.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String filename;
    private String fileType;
    private Long fileSize;
    private String filePath;
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Create DocumentChunk.java**

```java
package com.hmdp.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentChunk {
    private Long id;
    private Long documentId;
    private Long kbId;
    private Integer chunkIndex;
    private String content;
    private String metadata;
    private LocalDateTime createdAt;
    // embedding is handled via JDBC, not mapped here
}
```

- [ ] **Step 4: Create AuditLog.java**

```java
package com.hmdp.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rag_audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/entity/
git commit -m "feat(rag): add entity classes — KnowledgeBase, Document, DocumentChunk, AuditLog"
```

---

### Task 4: DTO classes

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/CreateKbRequest.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/KbResponse.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/UploadResponse.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/DocumentStatusResponse.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/ChatRequest.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/ChatMessage.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/RetrievedChunk.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/dto/AuditLogQuery.java`

- [ ] **Step 1: Create CreateKbRequest.java**

```java
package com.hmdp.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateKbRequest {
    @NotNull(message = "商户ID不能为空")
    private Long merchantId;
    @NotBlank(message = "知识库名称不能为空")
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
```

- [ ] **Step 2: Create KbResponse.java**

```java
package com.hmdp.rag.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class KbResponse {
    private Long id;
    private Long merchantId;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer documentCount;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Create UploadResponse.java**

```java
package com.hmdp.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UploadResponse {
    private Long documentId;
    private String status;
    private String message;
}
```

- [ ] **Step 4: Create DocumentStatusResponse.java**

```java
package com.hmdp.rag.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocumentStatusResponse {
    private Long id;
    private String filename;
    private String fileType;
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: Create ChatMessage.java**

```java
package com.hmdp.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;    // "user" | "assistant"
    private String content;
}
```

- [ ] **Step 6: Create ChatRequest.java**

```java
package com.hmdp.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    @NotNull(message = "知识库ID不能为空")
    private Long kbId;
    @NotBlank(message = "问题不能为空")
    private String question;
    private List<ChatMessage> messages;
    private Integer topK;
    private Boolean enableRerank;
    private Long merchantId;
}
```

- [ ] **Step 7: Create RetrievedChunk.java**

```java
package com.hmdp.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {
    private Long id;
    private String content;
    private Double score;
    private String metadata;
}
```

- [ ] **Step 8: Create AuditLogQuery.java**

```java
package com.hmdp.rag.dto;

import lombok.Data;

@Data
public class AuditLogQuery {
    private Long userId;
    private Long kbId;
    private String action;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
```

- [ ] **Step 9: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/dto/
git commit -m "feat(rag): add DTO classes for KB, document, chat, and audit"
```

---

### Task 5: Repository layer — MyBatis Plus mappers

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/repository/KnowledgeBaseMapper.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/repository/DocumentMapper.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/repository/AuditLogMapper.java`

- [ ] **Step 1: Create KnowledgeBaseMapper.java**

```java
package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
```

- [ ] **Step 2: Create DocumentMapper.java**

```java
package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
```

- [ ] **Step 3: Create AuditLogMapper.java**

```java
package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
```

- [ ] **Step 4: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/repository/
git commit -m "feat(rag): add MyBatis Plus mappers for KB, Document, AuditLog"
```

---

### Task 6: Repository layer — DocumentChunkRepo (JDBC for PgVector)

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/repository/DocumentChunkRepository.java`

- [ ] **Step 1: Create DocumentChunkRepository.java**

```java
package com.hmdp.rag.repository;

import com.hmdp.rag.entity.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL =
        "INSERT INTO rag_document_chunk (document_id, kb_id, chunk_index, content, embedding, tsv, metadata) " +
        "VALUES (?, ?, ?, ?, ?::vector, to_tsvector('chinese', ?), ?::jsonb)";

    private static final String VECTOR_SEARCH_SQL =
        "SELECT id, document_id, kb_id, chunk_index, content, metadata, " +
        "1 - (embedding <=> ?::vector) AS similarity " +
        "FROM rag_document_chunk WHERE kb_id = ? " +
        "ORDER BY embedding <=> ?::vector LIMIT ?";

    private static final String KEYWORD_SEARCH_SQL =
        "SELECT id, document_id, kb_id, chunk_index, content, metadata, " +
        "ts_rank(tsv, plainto_tsquery('chinese', ?)) AS score " +
        "FROM rag_document_chunk WHERE kb_id = ? AND tsv @@ plainto_tsquery('chinese', ?) " +
        "ORDER BY score DESC LIMIT ?";

    private static final String DELETE_BY_DOC_ID =
        "DELETE FROM rag_document_chunk WHERE document_id = ?";

    public void batchInsert(List<DocumentChunk> chunks, List<String> embeddingStrs) {
        jdbcTemplate.batchUpdate(INSERT_SQL, chunks, chunks.size(),
            (ps, chunk) -> {
                int idx = chunks.indexOf(chunk);
                ps.setLong(1, chunk.getDocumentId());
                ps.setLong(2, chunk.getKbId());
                ps.setInt(3, chunk.getChunkIndex());
                ps.setString(4, chunk.getContent());
                ps.setString(5, embeddingStrs.get(idx));
                ps.setString(6, chunk.getContent());
                ps.setString(7, chunk.getMetadata() != null ? chunk.getMetadata() : "{}");
            });
        log.debug("Batch inserted {} chunks", chunks.size());
    }

    public List<DocumentChunk> vectorSearch(String embeddingStr, Long kbId, int topK) {
        return jdbcTemplate.query(VECTOR_SEARCH_SQL,
            new ChunkWithScoreRowMapper(), embeddingStr, kbId, embeddingStr, topK);
    }

    public List<DocumentChunk> keywordSearch(String query, Long kbId, int topK) {
        return jdbcTemplate.query(KEYWORD_SEARCH_SQL,
            new ChunkWithScoreRowMapper(), query, kbId, query, topK);
    }

    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update(DELETE_BY_DOC_ID, documentId);
    }

    static class ChunkWithScoreRowMapper implements RowMapper<DocumentChunk> {
        @Override
        public DocumentChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(rs.getLong("id"));
            chunk.setDocumentId(rs.getLong("document_id"));
            chunk.setKbId(rs.getLong("kb_id"));
            chunk.setChunkIndex(rs.getInt("chunk_index"));
            chunk.setContent(rs.getString("content"));
            chunk.setMetadata(rs.getString("metadata"));
            return chunk;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/repository/DocumentChunkRepository.java
git commit -m "feat(rag): add DocumentChunkRepository with PgVector vector and keyword search"
```

---

### Task 7: Configuration classes

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/config/GlmApiConfig.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/config/RagProperties.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/config/OkHttpConfig.java`

- [ ] **Step 1: Create RagProperties.java**

```java
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
```

- [ ] **Step 2: Create GlmApiConfig.java**

```java
package com.hmdp.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "glm")
public class GlmApiConfig {
    private String apiKey;
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    private String llmModel = "glm-4-flash";
    private String embeddingModel = "embedding-2";
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration readTimeout = Duration.ofSeconds(120);
    private int maxRetries = 3;
}
```

- [ ] **Step 3: Create OkHttpConfig.java**

```java
package com.hmdp.rag.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient(GlmApiConfig glmConfig) {
        return new OkHttpClient.Builder()
                .connectTimeout(glmConfig.getConnectTimeout())
                .readTimeout(glmConfig.getReadTimeout())
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/config/
git commit -m "feat(rag): add config classes — RagProperties, GlmApiConfig, OkHttpConfig"
```

---

### Task 8: Strategy interfaces

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/parser/IDocumentParser.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/splitter/ITextSplitter.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/embedding/IEmbeddingClient.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/llm/ILlmClient.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/retriever/IRetriever.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/reranker/IReranker.java`

- [ ] **Step 1: Create IDocumentParser.java**

```java
package com.hmdp.rag.parser;

import java.io.InputStream;

public interface IDocumentParser {
    String parse(InputStream inputStream, String filename) throws Exception;
}
```

- [ ] **Step 2: Create ITextSplitter.java**

```java
package com.hmdp.rag.splitter;

import java.util.List;

public interface ITextSplitter {
    List<String> split(String text, int chunkSize, int overlap);
}
```

- [ ] **Step 3: Create IEmbeddingClient.java**

```java
package com.hmdp.rag.embedding;

import java.util.List;

public interface IEmbeddingClient {
    List<float[]> embed(List<String> texts);
}
```

- [ ] **Step 4: Create ILlmClient.java**

```java
package com.hmdp.rag.llm;

import com.hmdp.rag.dto.ChatMessage;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ILlmClient {
    Flux<String> streamChat(String systemPrompt, List<ChatMessage> history, String userQuestion);
}
```

- [ ] **Step 5: Create IRetriever.java**

```java
package com.hmdp.rag.retriever;

import com.hmdp.rag.dto.RetrievedChunk;

import java.util.List;

public interface IRetriever {
    List<RetrievedChunk> retrieve(String query, Long kbId, int topK);
}
```

- [ ] **Step 6: Create IReranker.java**

```java
package com.hmdp.rag.reranker;

import com.hmdp.rag.dto.RetrievedChunk;

import java.util.List;

public interface IReranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
```

- [ ] **Step 7: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/parser/ rag-service/src/main/java/com/hmdp/rag/splitter/ rag-service/src/main/java/com/hmdp/rag/embedding/ rag-service/src/main/java/com/hmdp/rag/llm/ rag-service/src/main/java/com/hmdp/rag/retriever/ rag-service/src/main/java/com/hmdp/rag/reranker/
git commit -m "feat(rag): add strategy interfaces for parser, splitter, embedding, llm, retriever, reranker"
```

---

### Task 9: Tika document parser implementation

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/parser/TikaDocumentParser.java`

- [ ] **Step 1: Create TikaDocumentParser.java**

```java
package com.hmdp.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

@Component
@Slf4j
public class TikaDocumentParser implements IDocumentParser {

    @Override
    public String parse(InputStream inputStream, String filename) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(Metadata.RESOURCE_NAME_KEY, filename);
        ParseContext context = new ParseContext();

        try {
            parser.parse(inputStream, handler, metadata, context);
            String text = handler.toString().trim();
            log.info("Parsed '{}': {} chars extracted", filename, text.length());
            return text;
        } catch (IOException | SAXException | TikaException e) {
            log.error("Failed to parse document: {}", filename, e);
            throw new Exception("文档解析失败: " + filename, e);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/parser/TikaDocumentParser.java
git commit -m "feat(rag): implement TikaDocumentParser for PDF/Word/Excel/TXT/MD parsing"
```

---

### Task 10: Semantic chunk splitter implementation

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/splitter/SemanticChunkSplitter.java`

- [ ] **Step 1: Create SemanticChunkSplitter.java**

```java
package com.hmdp.rag.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Slf4j
public class SemanticChunkSplitter implements ITextSplitter {

    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\\n\\s*\\n");

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Split by paragraphs first
        String[] paragraphs = PARAGRAPH_SEP.split(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String cleaned = para.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) continue;

            if (current.length() + cleaned.length() + 1 > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                // Create overlapping context
                if (overlap > 0 && current.length() > overlap) {
                    String overlapText = current.substring(Math.max(0, current.length() - overlap));
                    current = new StringBuilder(overlapText + " " + cleaned);
                } else {
                    current = new StringBuilder(cleaned);
                }
            } else {
                if (current.length() > 0) current.append(" ");
                current.append(cleaned);
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        log.debug("Split text ({} chars) into {} chunks (size={}, overlap={})",
                text.length(), chunks.size(), chunkSize, overlap);
        return chunks;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/splitter/SemanticChunkSplitter.java
git commit -m "feat(rag): implement SemanticChunkSplitter with paragraph-aware sliding window"
```

---

### Task 11: GLM Embedding client implementation

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/embedding/GlmEmbeddingClient.java`

- [ ] **Step 1: Create GlmEmbeddingClient.java**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/embedding/GlmEmbeddingClient.java
git commit -m "feat(rag): implement GlmEmbeddingClient with retry logic and pgvector format conversion"
```

---

### Task 12: GLM LLM client (SSE streaming)

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/llm/GlmLlmClient.java`

- [ ] **Step 1: Create GlmLlmClient.java**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/llm/GlmLlmClient.java
git commit -m "feat(rag): implement GlmLlmClient with virtual-thread SSE streaming via OkHttp+Reactor"
```

---

### Task 13: Retrievers — Vector, Keyword, Hybrid, RRF

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/retriever/VectorRetriever.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/retriever/KeywordRetriever.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/retriever/RrfFusion.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/retriever/HybridRetriever.java`

- [ ] **Step 1: Create VectorRetriever.java**

```java
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
import java.util.stream.Collectors;

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
                    .score(1.0 - i * 0.05) // placeholder score
                    .build());
        }
        return results;
    }
}
```

- [ ] **Step 2: Create KeywordRetriever.java**

```java
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
```

- [ ] **Step 3: Create RrfFusion.java**

```java
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

        // Process vector results
        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievedChunk c = vectorResults.get(i);
            chunkMap.put(c.getId(), c);
            double score = 1.0 / (k + i + 1);
            rrfScores.merge(c.getId(), score, Double::sum);
        }

        // Process keyword results
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
```

- [ ] **Step 4: Create HybridRetriever.java**

```java
package com.hmdp.rag.retriever;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class HybridRetriever implements IRetriever {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RrfFusion rrfFusion;
    private final RagProperties ragProperties;

    @Override
    public List<RetrievedChunk> retrieve(String query, Long kbId, int topK) {
        int fetchK = topK * 2; // fetch more for fusion

        List<RetrievedChunk> vectorResults = vectorRetriever.retrieve(query, kbId, fetchK);
        List<RetrievedChunk> keywordResults = keywordRetriever.retrieve(query, kbId, fetchK);

        List<RetrievedChunk> merged = rrfFusion.merge(vectorResults, keywordResults, topK);
        log.info("Hybrid search: vector={}, keyword={}, merged={}, kbId={}",
                vectorResults.size(), keywordResults.size(), merged.size(), kbId);

        return merged;
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/retriever/
git commit -m "feat(rag): implement retrievers — Vector, Keyword, Hybrid with RRF fusion"
```

---

### Task 14: Reranker implementation

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/reranker/NoOpReranker.java`

- [ ] **Step 1: Create NoOpReranker.java**

```java
package com.hmdp.rag.reranker;

import com.hmdp.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NoOpReranker implements IReranker {
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        // Pass-through: no reranking
        return chunks.size() <= topK ? chunks : chunks.subList(0, topK);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/reranker/NoOpReranker.java
git commit -m "feat(rag): add NoOpReranker as default pass-through reranker"
```

---

### Task 15: Prompt builder utility

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/llm/PromptBuilder.java`

- [ ] **Step 1: Create PromptBuilder.java**

```java
package com.hmdp.rag.llm;

import com.hmdp.rag.dto.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    public String buildSystemPrompt(List<RetrievedChunk> chunks) {
        return "你是一个美食点评平台的智能客服助手。请基于以下餐厅知识库内容回答用户问题。" +
                "如果知识库中没有相关信息，请如实告知"没有找到相关信息"。\n\n" +
                "知识库参考内容：\n" + formatChunks(chunks);
    }

    public String formatChunks(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(c -> "【来源 " + c.getId() + "】" + c.getContent())
                .collect(Collectors.joining("\n\n"));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/llm/PromptBuilder.java
git commit -m "feat(rag): add PromptBuilder for constructing RAG prompts with citations"
```

---

### Task 16: Pipeline implementations

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/pipeline/DocumentPipeline.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/pipeline/QaPipeline.java`

- [ ] **Step 1: Create DocumentPipeline.java**

```java
package com.hmdp.rag.pipeline;

import com.hmdp.rag.embedding.GlmEmbeddingClient;
import com.hmdp.rag.embedding.IEmbeddingClient;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.DocumentChunk;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.parser.IDocumentParser;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.splitter.ITextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentPipeline {

    private final IDocumentParser parser;
    private final ITextSplitter splitter;
    private final IEmbeddingClient embeddingClient;
    private final DocumentChunkRepository chunkRepo;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;

    public void process(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.error("Document not found: id={}", documentId);
            return;
        }

        try {
            KnowledgeBase kb = kbMapper.selectById(doc.getKbId());
            if (kb == null) {
                failDocument(doc, "知识库不存在");
                return;
            }

            int chunkSize = kb.getChunkSize() != null ? kb.getChunkSize() : 500;
            int overlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50;

            // Step 1: Parse
            String text;
            try (InputStream is = new FileInputStream(doc.getFilePath())) {
                text = parser.parse(is, doc.getFilename());
            }
            log.info("Parsed document {}: {} chars", doc.getId(), text.length());

            // Step 2: Split
            List<String> chunks = splitter.split(text, chunkSize, overlap);
            log.info("Split into {} chunks", chunks.size());

            // Step 3: Embed
            List<float[]> embeddings = embeddingClient.embed(chunks);
            log.info("Generated {} embeddings", embeddings.size());

            // Step 4: Store
            List<DocumentChunk> chunkEntities = new ArrayList<>();
            List<String> embeddingStrs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setKbId(doc.getKbId());
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setMetadata("{}");
                chunk.setCreatedAt(LocalDateTime.now());
                chunkEntities.add(chunk);
                embeddingStrs.add(GlmEmbeddingClient.toPgVectorString(embeddings.get(i)));
            }
            chunkRepo.batchInsert(chunkEntities, embeddingStrs);

            // Step 5: Update status
            doc.setStatus("COMPLETED");
            doc.setChunkCount(chunks.size());
            documentMapper.updateById(doc);
            log.info("Document {} processed: {} chunks stored", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("Document processing failed: id={}", documentId, e);
            failDocument(doc, e.getMessage());
        }
    }

    private void failDocument(Document doc, String error) {
        doc.setStatus("FAILED");
        doc.setErrorMsg(error);
        documentMapper.updateById(doc);
    }
}
```

- [ ] **Step 2: Create QaPipeline.java**

```java
package com.hmdp.rag.pipeline;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.ChatMessage;
import com.hmdp.rag.dto.RetrievedChunk;
import com.hmdp.rag.llm.ILlmClient;
import com.hmdp.rag.llm.PromptBuilder;
import com.hmdp.rag.reranker.IReranker;
import com.hmdp.rag.retriever.HybridRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class QaPipeline {

    private final HybridRetriever hybridRetriever;
    private final IReranker reranker;
    private final ILlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final RagProperties ragProperties;

    public Flux<String> answer(String question, Long kbId, Integer topK, Boolean enableRerank,
                                List<ChatMessage> history) {
        int k = topK != null ? Math.min(topK, ragProperties.getRetrieval().getMaxTopK())
                : ragProperties.getRetrieval().getDefaultTopK();
        boolean doRerank = enableRerank != null && enableRerank;

        // Step 1: Hybrid retrieval
        List<RetrievedChunk> chunks = hybridRetriever.retrieve(question, kbId, k);
        log.info("Retrieved {} chunks for question: {}", chunks.size(), question);

        // Step 2: Optional rerank
        if (doRerank && chunks.size() > 1) {
            chunks = reranker.rerank(question, chunks, k);
            log.info("Reranked to {} chunks", chunks.size());
        }

        // Step 3: Build prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(chunks);

        // Step 4: SSE stream
        // First emit sources event, then LLM deltas
        String sourcesJson = buildSourcesJson(chunks);
        int maxHistory = ragProperties.getConversation().getMaxHistoryTurns();
        List<ChatMessage> trimmedHistory = history != null && history.size() > maxHistory * 2
                ? history.subList(history.size() - maxHistory * 2, history.size())
                : history;

        return Mono.just("event: sources\n" + sourcesJson + "\n\n")
                .concatWith(llmClient.streamChat(systemPrompt, trimmedHistory, question)
                        .map(delta -> delta));
    }

    private String buildSourcesJson(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("data: {\"type\":\"sources\",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append(",");
            RetrievedChunk c = chunks.get(i);
            sb.append(String.format(
                    "{\"id\":%d,\"content\":\"%s\",\"score\":%.4f}",
                    c.getId(), escapeJson(c.getContent()), c.getScore() != null ? c.getScore() : 0));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/pipeline/
git commit -m "feat(rag): implement DocumentPipeline (async processing) and QaPipeline (hybrid search + SSE)"
```

---

### Task 17: Service layer — KnowledgeBase & Document services

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/service/IKnowledgeBaseService.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/service/IDocumentService.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/service/impl/KnowledgeBaseServiceImpl.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/service/impl/DocumentServiceImpl.java`

- [ ] **Step 1: Create IKnowledgeBaseService.java**

```java
package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.dto.KbResponse;
import com.hmdp.rag.entity.KnowledgeBase;

public interface IKnowledgeBaseService {
    KnowledgeBase create(CreateKbRequest request, Long userId);
    KnowledgeBase getById(Long id);
    Page<KnowledgeBase> listByMerchant(Long merchantId, int pageNum, int pageSize);
    KnowledgeBase update(Long id, CreateKbRequest request, Long userId);
    void delete(Long id);
}
```

- [ ] **Step 2: Create IDocumentService.java**

```java
package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.UploadResponse;
import com.hmdp.rag.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface IDocumentService {
    UploadResponse upload(MultipartFile file, Long kbId, Long userId);
    Page<Document> listByKb(Long kbId, int pageNum, int pageSize);
    Document getStatus(Long documentId);
    void delete(Long documentId);
}
```

- [ ] **Step 3: Create KnowledgeBaseServiceImpl.java**

```java
package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.exception.BusinessException;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl implements IKnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;

    @Override
    public KnowledgeBase create(CreateKbRequest request, Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setMerchantId(request.getMerchantId());
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500);
        kb.setChunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 50);
        kb.setCreatedBy(userId);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        log.info("Knowledge base created: id={}, name={}, merchantId={}", kb.getId(), kb.getName(), kb.getMerchantId());
        return kb;
    }

    @Override
    public KnowledgeBase getById(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        return kb;
    }

    @Override
    public Page<KnowledgeBase> listByMerchant(Long merchantId, int pageNum, int pageSize) {
        Page<KnowledgeBase> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getMerchantId, merchantId)
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return kbMapper.selectPage(page, wrapper);
    }

    @Override
    public KnowledgeBase update(Long id, CreateKbRequest request, Long userId) {
        KnowledgeBase kb = getById(id);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getChunkSize() != null) kb.setChunkSize(request.getChunkSize());
        if (request.getChunkOverlap() != null) kb.setChunkOverlap(request.getChunkOverlap());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
        return kb;
    }

    @Override
    public void delete(Long id) {
        getById(id);
        kbMapper.deleteById(id);
        log.info("Knowledge base deleted: id={}", id);
    }
}
```

- [ ] **Step 4: Create DocumentServiceImpl.java**

```java
package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.UploadResponse;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.exception.BusinessException;
import com.hmdp.rag.mq.DocumentProcessProducer;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.service.IDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements IDocumentService {

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final DocumentChunkRepository chunkRepo;
    private final DocumentProcessProducer producer;
    private final RagProperties ragProperties;

    @Override
    public UploadResponse upload(MultipartFile file, Long kbId, Long userId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }

        String originalName = file.getOriginalFilename();
        String fileType = getFileType(originalName);
        String storedName = UUID.randomUUID() + "_" + originalName;

        Path uploadDir = Paths.get(ragProperties.getUpload().getDir());
        try {
            Files.createDirectories(uploadDir);
            Path filePath = uploadDir.resolve(storedName);
            file.transferTo(filePath);

            Document doc = new Document();
            doc.setKbId(kbId);
            doc.setFilename(originalName);
            doc.setFileType(fileType);
            doc.setFileSize(file.getSize());
            doc.setFilePath(filePath.toString());
            doc.setStatus("PROCESSING");
            doc.setUploadedBy(userId);
            doc.setCreatedAt(LocalDateTime.now());
            documentMapper.insert(doc);

            // Send to RocketMQ for async processing
            producer.sendProcessMessage(doc.getId());

            log.info("Document uploaded: id={}, name={}, kbId={}", doc.getId(), originalName, kbId);
            return new UploadResponse(doc.getId(), "PROCESSING", "文档已上传，正在处理中");
        } catch (IOException e) {
            log.error("Failed to save uploaded file: {}", originalName, e);
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public Page<Document> listByKb(Long kbId, int pageNum, int pageSize) {
        Page<Document> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt);
        return documentMapper.selectPage(page, wrapper);
    }

    @Override
    public Document getStatus(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        return doc;
    }

    @Override
    public void delete(Long documentId) {
        Document doc = getStatus(documentId);
        chunkRepo.deleteByDocumentId(documentId);
        documentMapper.deleteById(documentId);
        // Clean up file
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", doc.getFilePath(), e);
        }
        log.info("Document deleted: id={}", documentId);
    }

    private String getFileType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".xlsx")) return "xlsx";
        if (lower.endsWith(".xls")) return "xls";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".md")) return "md";
        return "unknown";
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/service/
git commit -m "feat(rag): implement KB and Document services with upload, CRUD, and async processing trigger"
```

---

### Task 18: RocketMQ — Producer and Consumer

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/mq/DocumentProcessProducer.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/mq/DocumentProcessConsumer.java`

- [ ] **Step 1: Create DocumentProcessProducer.java**

```java
package com.hmdp.rag.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessProducer {

    public static final String TOPIC = "rag-document-process";

    private final RocketMQTemplate rocketMQTemplate;

    public void sendProcessMessage(Long documentId) {
        rocketMQTemplate.convertAndSend(TOPIC, documentId);
        log.info("Sent document process message: documentId={}", documentId);
    }
}
```

- [ ] **Step 2: Create DocumentProcessConsumer.java**

```java
package com.hmdp.rag.mq;

import com.hmdp.rag.pipeline.DocumentPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 文档处理消息消费者
 *
 * 负责异步消费文档上传后产生的处理消息，完成：
 * 1. 文档解析（Tika）
 * 2. 语义分块
 * 3. 向量化（GLM Embedding）
 * 4. 写入 PgVector + tsvector
 *
 * 失败后 RocketMQ 自动重试
 */
@Component
@RocketMQMessageListener(
        topic = DocumentProcessProducer.TOPIC,
        consumerGroup = "rag-doc-process-consumer-group",
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessConsumer implements RocketMQListener<Long> {

    private final DocumentPipeline documentPipeline;

    @Override
    public void onMessage(Long documentId) {
        log.info("Received document process message: documentId={}", documentId);
        try {
            documentPipeline.process(documentId);
        } catch (Exception e) {
            log.error("Document processing failed: documentId={}", documentId, e);
            throw new RuntimeException("Document processing failed: " + documentId, e);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/mq/
git commit -m "feat(rag): implement RocketMQ producer and consumer for async document processing"
```

---

### Task 19: Global exception handler

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/handler/GlobalExceptionHandler.java`

- [ ] **Step 1: Create GlobalExceptionHandler.java**

```java
package com.hmdp.rag.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("Exception: {}", e.getMessage(), e);
        return Result.fail("系统繁忙，请稍后重试");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/handler/GlobalExceptionHandler.java
git commit -m "feat(rag): add GlobalExceptionHandler following project convention"
```

---

### Task 20: Controllers

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/controller/KnowledgeBaseController.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/controller/DocumentController.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/controller/QaController.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/controller/AuditLogController.java`

- [ ] **Step 1: Create KnowledgeBaseController.java**

```java
package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.service.IKnowledgeBaseService;
import com.hmdp.utils.UserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final IKnowledgeBaseService kbService;

    @PostMapping
    public Result create(@Valid @RequestBody CreateKbRequest request) {
        Long userId = UserHolder.getUser().getId();
        KnowledgeBase kb = kbService.create(request, userId);
        return Result.ok(kb);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        return Result.ok(kbService.getById(id));
    }

    @GetMapping
    public Result list(@RequestParam Long merchantId,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(kbService.listByMerchant(merchantId, pageNum, pageSize));
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @Valid @RequestBody CreateKbRequest request) {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(kbService.update(id, request, userId));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 2: Create DocumentController.java**

```java
package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.service.IDocumentService;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class DocumentController {

    private final IDocumentService documentService;

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public Result upload(@PathVariable Long kbId,
                          @RequestParam("file") MultipartFile file) {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(documentService.upload(file, kbId, userId));
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public Result list(@PathVariable Long kbId,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(documentService.listByKb(kbId, pageNum, pageSize));
    }

    @GetMapping("/documents/{id}/status")
    public Result status(@PathVariable Long id) {
        return Result.ok(documentService.getStatus(id));
    }

    @DeleteMapping("/documents/{id}")
    public Result delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 3: Create QaController.java**

```java
package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.dto.ChatRequest;
import com.hmdp.rag.pipeline.QaPipeline;
import com.hmdp.rag.service.IAuditService;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/qa")
@RequiredArgsConstructor
@Slf4j
public class QaController {

    private final QaPipeline qaPipeline;
    private final IAuditService auditService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        Long userId = UserHolder.getUser().getId();
        String ip = httpRequest.getRemoteAddr();

        // Audit: record query
        auditService.log(userId, "QUERY", "KB",
                request.getKbId(), Map.of("question", request.getQuestion()), ip);

        Flux<String> answer = qaPipeline.answer(
                request.getQuestion(),
                request.getKbId(),
                request.getTopK(),
                request.getEnableRerank(),
                request.getMessages()
        );

        return Flux.just("event: thinking\ndata: {\"type\":\"thinking\",\"content\":\"正在检索相关知识...\"}\n\n")
                .concatWith(answer)
                .concatWith(Flux.just("event: done\ndata: {\"type\":\"done\"}\n\n"))
                .doOnComplete(() -> log.info("QA completed: userId={}, question={}", userId, request.getQuestion()))
                .doOnError(e -> log.error("QA error: {}", e.getMessage(), e));
    }
}
```

- [ ] **Step 4: Create AuditLogController.java**

```java
package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.dto.AuditLogQuery;
import com.hmdp.rag.service.IAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final IAuditService auditService;

    @GetMapping
    public Result query(@ModelAttribute AuditLogQuery query) {
        return Result.ok(auditService.query(
                query.getUserId(), query.getKbId(), query.getAction(),
                query.getPageNum(), query.getPageSize()));
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/controller/
git commit -m "feat(rag): implement controllers — KB CRUD, document upload/status, SSE QA chat, audit log"
```

---

### Task 21: Audit service

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/service/IAuditService.java`
- Create: `rag-service/src/main/java/com/hmdp/rag/service/impl/AuditServiceImpl.java`

- [ ] **Step 1: Create IAuditService.java**

```java
package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.entity.AuditLog;

import java.util.Map;

public interface IAuditService {
    void log(Long userId, String action, String resourceType, Long resourceId, Map<String, String> detail, String ip);
    Page<AuditLog> query(Long userId, Long kbId, String action, int pageNum, int pageSize);
}
```

- [ ] **Step 2: Create AuditServiceImpl.java**

```java
package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.rag.entity.AuditLog;
import com.hmdp.rag.repository.AuditLogMapper;
import com.hmdp.rag.service.IAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements IAuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void log(Long userId, String action, String resourceType, Long resourceId,
                    Map<String, String> detail, String ip) {
        try {
            AuditLog logEntry = new AuditLog();
            logEntry.setUserId(userId);
            logEntry.setAction(action);
            logEntry.setResourceType(resourceType);
            logEntry.setResourceId(resourceId);
            logEntry.setDetail(objectMapper.writeValueAsString(detail));
            logEntry.setIp(ip);
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("Failed to write audit log: action={}, userId={}", action, userId, e);
        }
    }

    @Override
    public Page<AuditLog> query(Long userId, Long kbId, String action, int pageNum, int pageSize) {
        Page<AuditLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) wrapper.eq(AuditLog::getUserId, userId);
        if (action != null) wrapper.eq(AuditLog::getAction, action);
        wrapper.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(page, wrapper);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/service/IAuditService.java rag-service/src/main/java/com/hmdp/rag/service/impl/AuditServiceImpl.java
git commit -m "feat(rag): implement async audit logging service"
```

---

### Task 22: Swagger / OpenAPI config

**Files:**
- Create: `rag-service/src/main/java/com/hmdp/rag/config/SwaggerConfig.java`

- [ ] **Step 1: Create SwaggerConfig.java**

```java
package com.hmdp.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RAG Service API")
                        .description("RAG智能知识问答服务接口文档")
                        .version("1.0.0"));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/config/SwaggerConfig.java
git commit -m "feat(rag): add Swagger/OpenAPI config"
```

---

### Task 23: Enable async for audit logging

**Files:**
- Modify: `rag-service/src/main/java/com/hmdp/rag/RagApplication.java`

- [ ] **Step 1: Add @EnableAsync to RagApplication.java**

```java
// Add to existing imports:
import org.springframework.scheduling.annotation.EnableAsync;

// Add annotation to class:
@EnableAsync
```

- [ ] **Step 2: Commit**

```bash
git add rag-service/src/main/java/com/hmdp/rag/RagApplication.java
git commit -m "feat(rag): enable @Async for audit logging"
```

---

### Task 24: Docker Compose (PostgreSQL with pgvector)

**Files:**
- Modify/Create: `docker-compose.yml` (in project root, add postgres-rag service)

> **Note:** Check if docker-compose.yml already exists — if not, create one. If it exists, append to it.

- [ ] **Step 1: Check existing docker-compose**

Run: `ls D:/hm-dianping/docker-compose.yml 2>/dev/null || echo "NOT FOUND"`

- [ ] **Step 2: If NOT FOUND, create docker-compose.yml:**

```yaml
version: '3.8'

services:
  # PostgreSQL for RAG Service (pgvector + tsvector)
  postgres-rag:
    image: pgvector/pgvector:pg16
    container_name: postgres-rag
    ports:
      - "5433:5432"
    environment:
      POSTGRES_DB: rag_db
      POSTGRES_USER: rag_user
      POSTGRES_PASSWORD: ${RAG_DB_PASSWORD:-rag_password}
    volumes:
      - postgres_rag_data:/var/lib/postgresql/data
      - ./sql/init-rag.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U rag_user -d rag_db"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_rag_data:
```

If docker-compose.yml EXISTS, append only the `postgres-rag` service and `postgres_rag_data` volume.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(rag): add PostgreSQL pgvector service to docker-compose"
```

---

### Task 25: Build and verify

**Files:** None (verification only)

- [ ] **Step 1: Full project compile**

Run: `cd D:/hm-dianping && mvn clean compile -pl rag-service -am`
Expected: BUILD SUCCESS

- [ ] **Step 2: Package rag-service**

Run: `cd D:/hm-dianping && mvn package -pl rag-service -am -DskipTests`
Expected: BUILD SUCCESS, jar produced in rag-service/target/

- [ ] **Step 3: Verify PostgreSQL container starts**

Run: `docker compose -f D:/hm-dianping/docker-compose.yml up -d postgres-rag`
Expected: Container starts, healthchecks pass

- [ ] **Step 4: Verify DB init**

```bash
docker exec postgres-rag psql -U rag_user -d rag_db -c "\dt"
```
Expected: 4 tables listed: rag_knowledge_base, rag_document, rag_document_chunk, rag_audit_log

- [ ] **Step 5: Commit (if any fixes needed)**

---

### Task 26: README documentation

**Files:**
- Create: `rag-service/README.md`

- [ ] **Step 1: Create rag-service/README.md**

````markdown
# RAG Service — 智能知识问答服务

## 概述

rag-service 是 hm-dianping 的 RAG（Retrieval-Augmented Generation）微服务模块，为点评美食平台提供基于商户知识的智能问答能力。

## 功能

- **知识库管理**：支持多知识库，商户级权限隔离
- **文档处理**：上传 PDF/Word/Excel/TXT/MD → Tika 解析 → 语义分块 → GLM Embedding → PgVector 存储（异步 RocketMQ）
- **智能问答**：混合检索（向量 + tsvector 全文）→ RRF 融合 → GLM-5 生成 → SSE 流式输出
- **审计日志**：所有操作全记录

## 技术栈

| 组件 | 选型 | 理由 |
|------|------|------|
| 向量数据库 | PgVector (PostgreSQL 16) | 向量+全文检索一体，运维简单 |
| 全文检索 | tsvector + zhparser | PostgreSQL 原生，无需额外 ES 集群 |
| 混合检索 | RRF（倒数排名融合）| 简单有效，k=60 |
| LLM | GLM-5 (glm-4-flash) | OpenAI 协议兼容，中文能力强 |
| Embedding | GLM Embedding (1024d) | 与 GLM-5 同生态 |
| 文档解析 | Apache Tika 2.9 | Java 原生，格式覆盖全 |
| 异步消息 | RocketMQ | 项目已有，复用基础设施 |
| 鉴权 | Sa-Token | 项目统一认证方案 |

## 快速启动

### 1. 启动 PostgreSQL（含 pgvector）

```bash
docker compose -f ../docker-compose.yml up -d postgres-rag
```

### 2. 配置环境变量

```bash
export GLM_API_KEY="your-zhipu-api-key"
export RAG_DB_PASSWORD="rag_password"
```

### 3. 启动服务

```bash
cd ..
mvn spring-boot:run -pl rag-service
```

服务端口：**8087**
Swagger UI：http://localhost:8087/swagger-ui/index.html

## API 概览

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/rag/knowledge-bases` | POST/GET | 知识库 CRUD |
| `/api/rag/knowledge-bases/{kbId}/documents` | POST | 上传文档 |
| `/api/rag/documents/{id}/status` | GET | 查询处理状态 |
| `/api/rag/qa/chat` | POST | SSE 流式问答 |
| `/api/rag/audit-logs` | GET | 审计日志查询 |

## 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `rag.chunk.default-size` | 500 | 分块大小（字符） |
| `rag.chunk.default-overlap` | 50 | 重叠大小 |
| `rag.retrieval.default-top-k` | 5 | 检索返回数量 |
| `rag.retrieval.rrf-k` | 60 | RRF 融合参数 |
| `rag.conversation.max-history-turns` | 10 | 多轮对话保留轮数 |

## 项目结构

```
com.hmdp.rag
├── controller     # REST 控制器
├── service        # 业务服务接口 + 实现
├── pipeline       # 离线/在线处理管道
├── parser         # Tika 文档解析
├── splitter       # 语义分块
├── embedding      # GLM Embedding 客户端
├── retriever      # 向量/关键词/混合检索 + RRF
├── reranker       # 重排序（默认 NoOp）
├── llm            # GLM LLM 客户端（SSE 流式）
├── repository     # MyBatis Plus Mapper + JDBC Repository
├── entity         # 数据实体
├── dto            # 请求/响应 DTO
├── config         # 配置类
├── mq             # RocketMQ Producer + Consumer
└── handler        # 全局异常处理
```
````

- [ ] **Step 2: Commit**

```bash
git add rag-service/README.md
git commit -m "docs(rag): add rag-service README with setup instructions and tech rationale"
```

---

### Task 27: Final verification — full build

**Files:** None

- [ ] **Step 1: Clean build entire project**

```bash
cd D:/hm-dianping && mvn clean compile
```
Expected: BUILD SUCCESS for all modules including rag-service

- [ ] **Step 2: Verify Nacos config template exists**

The following Nacos config should be created manually or via Nacos console under `rag-service.yaml`:

```yaml
# Optional dynamic config overrides in Nacos
glm:
  llm-model: glm-4-flash
  embedding-model: embedding-2
rag:
  chunk:
    default-size: 500
  retrieval:
    default-top-k: 5
```

Run (manual step): `echo "Ensure Nacos config rag-service.yaml exists in namespace public"`

- [ ] **Step 3: Final commit**

```bash
git add -A && git status
git commit -m "feat(rag): complete rag-service implementation with all core features"
```

---
