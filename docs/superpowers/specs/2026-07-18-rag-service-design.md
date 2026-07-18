# RAG Service 微服务设计文档

> 日期: 2026-07-18  
> 项目: hm-dianping (live-hub)  
> 版本: 0.1.0

---

## 1. 概述

为 hm-dianping 点评美食平台新增 **rag-service** 微服务模块，提供基于 RAG（Retrieval-Augmented Generation）的智能知识问答能力。服务独立部署，通过 RocketMQ 消息驱动实现离线文档处理，通过 SSE 流式接口提供在线问答。

### 1.1 业务场景

- **商户知识管理**：每个商户（餐厅）可创建多个知识库，上传菜单、活动公告、菜品介绍等文档
- **智能问答**：用户按商户查询，"这家店的招牌菜是什么？""有没有包间？"
- **权限隔离**：商户管理员只能管理自己的知识库，用户按商户维度查询

### 1.2 核心功能

| 功能 | 描述 |
|------|------|
| 知识库管理 | 创建/查询/更新/删除知识库，商户级隔离 |
| 文档管理 | 上传 PDF/Word/Excel/TXT/MD，异步解析入库 |
| 异步处理 | RocketMQ 驱动文档解析→分块→向量化→索引 |
| 智能问答 | 混合检索 + RRF 融合 + GLM-5 生成 + SSE 流式输出 |
| 权限隔离 | 基于 Sa-Token，商户级别数据隔离 |
| 审计日志 | 记录所有关键操作（上传/删除/查询） |

---

## 2. 技术选型

### 2.1 技术栈总览

| 组件 | 选型 | 版本 | 理由 |
|------|------|------|------|
| **框架** | Spring Boot + Spring Cloud Alibaba | 3.1.12 / 2022.0.0.0 | 与项目统一 |
| **向量数据库** | PgVector (PostgreSQL 16) | pgvector 0.7 | 向量+全文检索一体，运维成本低；项目已有 MySQL，PG 仅用于向量场景 |
| **全文检索** | tsvector + zhparser | PG 原生 | PostgreSQL 原生全文检索，加中文分词插件 |
| **混合检索** | RRF（倒数排名融合） | k=60 | 简单有效，无需训练，平衡向量和关键词贡献 |
| **LLM** | GLM-5（智谱） | glm-4-flash | 兼容 OpenAI 协议，中文能力强，API 稳定 |
| **Embedding** | GLM Embedding | embedding-2 (1024d) | 与 GLM-5 同生态，1024 维平衡精度与存储开销 |
| **文档解析** | Apache Tika | 2.9.x | Java 原生，自动格式检测，覆盖所有要求格式 |
| **HTTP 客户端** | OkHttp | Spring Boot 内置 | 轻量高效，内置连接池和超时重试 |
| **SSE 流式** | Spring WebFlux + Reactor | 与 Boot 一致 | 原生 ServerSentEvent 支持，非阻塞 |
| **异步消息** | RocketMQ | 2.2.3（项目已有） | 复用基础设施 |
| **鉴权** | Sa-Token + Redis | 1.44.0（项目已有） | 网关统一校验，服务间会话共享 |
| **API 文档** | SpringDoc OpenAPI | 2.1.x | Spring Boot 3 最佳实践 |
| **中文分块** | Hutool + 自研 | 5.8.22（项目已有） | 语义段落分割 + 滑动窗口重叠 |

### 2.2 关于 BM25 与 tsvector

用户需求中提到 BM25 关键词检索。PostgreSQL 原生 tsvector 使用的是 TF-IDF 加权（`ts_rank`），不是严格的 BM25，但搜索结果质量接近。RRF 融合算法依赖排名而非绝对分数，因此 tsvector + RRF 能达到与 BM25 + RRF 同等的检索效果。如果后续需要严格 BM25，可考虑集成 ParadeDB 或替换为 Elasticsearch。

### 2.3 关于 zhparser 中文分词

`pgvector/pgvector:pg16` 官方镜像不包含 zhparser 扩展。安装方案：在 init.sql 中先 `CREATE EXTENSION zhparser`，Docker 启动时若扩展不存在则回退使用 `simple` 分词器。完整 zhparser 需要编译安装，作为可选项。简单场景下，基于 2-gram 的搜索也能满足中文关键词检索需求。

### 2.4 为什么不用 Elasticsearch？

- 项目规模不需要独立 ES 集群的运维开销
- PostgreSQL tsvector + zhparser 已满足中文全文检索需求
- PgVector 在同一数据库内完成向量检索，RRF 融合直接在 SQL 层完成
- 减少部署组件 = 降低运维复杂度，符合"简单优先"原则

### 2.5 为什么复用 RocketMQ 而不是用 Spring Events？

- 文档处理失败需要重试，RocketMQ 自带重试+死信队列
- 异步解耦更彻底：上传 API 只发消息，处理逻辑完全独立
- order-service 已验证 RocketMQ 在项目中的可靠性和配置模式

---

## 3. 架构设计

### 3.1 分层架构（分层策略式）

```
┌─────────────────────────────────────────────────────────┐
│  Controller 层                                           │
│  KnowledgeBaseController · DocumentController · QaController │
├─────────────────────────────────────────────────────────┤
│  Service 层                                              │
│  IKnowledgeBaseService · IDocumentService · IQaService   │
│  IAuditService                                           │
├─────────────────────────────────────────────────────────┤
│  Pipeline 层 — 编排策略组件                              │
│  DocumentPipeline（离线链）    QaPipeline（在线链）       │
├─────────────────────────────────────────────────────────┤
│  Strategy 层 — 可替换的策略接口                          │
│  Parser · Splitter · Embedding · Retriever · Reranker · LLM │
├─────────────────────────────────────────────────────────┤
│  Repository 层                                           │
│  KnowledgeBaseRepo(MyBatis Plus) · DocumentChunkRepo(JDBC) │
├─────────────────────────────────────────────────────────┤
│  Infrastructure                                          │
│  RocketMQ · PostgreSQL(pgvector) · Redis · Nacos          │
└─────────────────────────────────────────────────────────┘
```

### 3.2 数据流

**离线处理链路（异步）：**
```
POST /upload (mutipart)
  → 保存文件到本地 + 写入 rag_document (status=PROCESSING)
  → 发送 RocketMQ 消息 (documentId)
  → 立即返回 {"taskId": 123}

RocketMQ Consumer:
  → DocumentPipeline.process(documentId)
    → TikaDocumentParser.parse(file)  → ParsedDocument
    → TextCleaner.clean(text)         → 清洗后文本
    → SemanticChunkSplitter.split()   → List<Chunk>
    → GlmEmbeddingClient.embed(chunks)→ List<float[]>
    → DocumentChunkRepo.batchInsert() → PgVector + tsvector
    → DocumentRepo.updateStatus(COMPLETED)
    → [失败] → updateStatus(FAILED) + AuditLog
```

**在线问答链路（同步 SSE 流式）：**
```
POST /qa/chat (SSE)
  → Sa-Token 校验 → 获取 userId/merchantId
  → 校验 kbId 属于当前商户（权限隔离）
  → QaPipeline.answer(question, kbId, topK)
    → HybridRetriever.retrieve(question, kbId, topK)
      → VectorRetriever: PgVector cosine_similarity  Top-K1
      → KeywordRetriever: tsquery @@ tsvector          Top-K2
      → RrfFusion.merge(results1, results2)            Top-K
    → [可选] Reranker.rerank(query, chunks)
    → PromptBuilder.build(question, chunks, history)
    → GlmLlmClient.streamChat(prompt)
      → SSE: sources → delta → delta → ... → done
```

---

## 4. 数据模型

### 4.1 表结构

```sql
-- PostgreSQL 数据库 rag_db

CREATE EXTENSION IF NOT EXISTS pgvector;
CREATE EXTENSION IF NOT EXISTS zhparser;
CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l WITH simple;

-- 知识库
CREATE TABLE rag_knowledge_base (
    id            BIGSERIAL PRIMARY KEY,
    merchant_id   BIGINT NOT NULL,              -- 商户 ID，权限隔离键
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    chunk_size    INT DEFAULT 500,
    chunk_overlap INT DEFAULT 50,
    embedding_model VARCHAR(100) DEFAULT 'embedding-2',
    created_by    BIGINT,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_kb_merchant ON rag_knowledge_base(merchant_id);

-- 文档
CREATE TABLE rag_document (
    id            BIGSERIAL PRIMARY KEY,
    kb_id         BIGINT NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    filename      VARCHAR(255) NOT NULL,
    file_type     VARCHAR(20) NOT NULL,          -- pdf/docx/xlsx/txt/md
    file_size     BIGINT,
    file_path     VARCHAR(500),
    status        VARCHAR(20) DEFAULT 'PROCESSING', -- PROCESSING/COMPLETED/FAILED
    chunk_count   INT DEFAULT 0,
    error_msg     TEXT,
    uploaded_by   BIGINT,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_doc_kb ON rag_document(kb_id);

-- 分块（核心检索表）
CREATE TABLE rag_document_chunk (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    kb_id         BIGINT NOT NULL,              -- 冗余，加速按知识库过滤
    chunk_index   INT NOT NULL,
    content       TEXT NOT NULL,
    embedding     vector(1024),                 -- GLM Embedding 向量
    tsv           tsvector,                     -- 中文全文索引
    metadata      JSONB DEFAULT '{}',           -- 来源页码/行号等
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
-- 向量索引（IVFFlat，100 个聚类中心）
CREATE INDEX idx_chunk_embedding ON rag_document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
-- 全文检索索引
CREATE INDEX idx_chunk_tsv ON rag_document_chunk USING GIN (tsv);
-- 加速按知识库过滤的向量检索
CREATE INDEX idx_chunk_kb ON rag_document_chunk(kb_id);
-- 加速按文档清理
CREATE INDEX idx_chunk_doc ON rag_document_chunk(document_id);

-- 审计日志
CREATE TABLE rag_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    action        VARCHAR(50) NOT NULL,          -- UPLOAD/DELETE/QUERY/CREATE_KB/DELETE_KB
    resource_type VARCHAR(50) NOT NULL,          -- KB/DOCUMENT
    resource_id   BIGINT,
    detail        JSONB DEFAULT '{}',            -- 操作详情
    ip            VARCHAR(45),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_audit_user ON rag_audit_log(user_id);
CREATE INDEX idx_audit_time ON rag_audit_log(created_at DESC);
```

### 4.2 ER 关系

```
rag_knowledge_base (1) ──→ (N) rag_document (1) ──→ (N) rag_document_chunk
       │                            │                         ├── embedding vector(1024)
       │                            │                         └── tsv tsvector
       └── merchant_id (权限隔离键)
rag_audit_log (独立)
```

---

## 5. API 设计

### 5.1 知识库管理

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/rag/knowledge-bases` | 创建知识库 | 商户管理员 |
| GET | `/api/rag/knowledge-bases` | 商户知识库列表（按 merchantId 过滤） | 登录用户 |
| GET | `/api/rag/knowledge-bases/{id}` | 知识库详情 | 登录用户 |
| PUT | `/api/rag/knowledge-bases/{id}` | 更新配置（名称、分块参数） | 商户管理员 |
| DELETE | `/api/rag/knowledge-bases/{id}` | 删除（级联文档+分块） | 商户管理员 |

### 5.2 文档管理

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/rag/knowledge-bases/{kbId}/documents` | 上传文档(multipart) → 返回 taskId | 商户管理员 |
| GET | `/api/rag/knowledge-bases/{kbId}/documents` | 文档列表（分页） | 登录用户 |
| GET | `/api/rag/documents/{id}/status` | 查询处理状态 | 登录用户 |
| DELETE | `/api/rag/documents/{id}` | 删除文档及所有分块 | 商户管理员 |

### 5.3 问答（SSE 流式）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/qa/chat` | SSE 问答，Accept: text/event-stream |

**请求体：**
```json
{
  "kbId": 1,
  "question": "这家店的招牌菜是什么？",
  "messages": [
    {"role": "user", "content": "你好"},
    {"role": "assistant", "content": "您好！有什么可以帮您的？"}
  ],
  "topK": 5,
  "enableRerank": false
}
```

**SSE 事件类型：**
```
event: thinking    → {"type":"thinking","content":"正在检索相关知识..."}
event: sources     → {"type":"sources","chunks":[{"id":1,"content":"...","score":0.92},...]}
event: delta       → {"type":"delta","content":"这"}  // 逐 token 推送
event: done        → {"type":"done"}
event: error       → {"type":"error","message":"..."}
```

### 5.4 审计日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/rag/audit-logs` | 分页查询，参数：userId/kbId/action/pageNum/pageSize |

### 5.5 多轮对话策略

客户端维护对话上下文，每次请求带上完整 `messages` 数组。服务端取最近 N 轮（默认 10 轮）构建 Prompt。当前版本不持久化对话。

---

## 6. 配置管理

### 6.1 配置分层

```
bootstrap.yml (本地)     → Nacos 地址 + 服务名
application.yml (本地)   → 端口 + 数据源 + 外部 API 配置（敏感信息走环境变量）
Nacos rag-service.yaml   → 可动态刷新配置（分块大小、检索数量、模型切换）
```

### 6.2 环境变量（敏感信息）

| 变量 | 说明 |
|------|------|
| `GLM_API_KEY` | 智谱 API Key |
| `RAG_DB_PASSWORD` | PostgreSQL 密码 |

### 6.3 关键配置项

```yaml
# application.yml
server.port: 8087

spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5433/rag_db
    username: ${RAG_DB_USER:rag_user}
    password: ${RAG_DB_PASSWORD}

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
    dir: ${RAG_UPLOAD_DIR:./data/rag-uploads}   # 文档上传目录
    max-size: 20MB

rocketmq:
  name-server: 127.0.0.1:9876
  consumer:
    group: rag-doc-process-group
    topic: rag-document-process
```

---

## 7. 部署清单

### 7.1 新增基础设施

```yaml
# docker-compose 追加片段
services:
  postgres-rag:
    image: pgvector/pgvector:pg16
    container_name: postgres-rag
    ports:
      - "5433:5432"
    environment:
      POSTGRES_DB: rag_db
      POSTGRES_USER: rag_user
      POSTGRES_PASSWORD: ${RAG_DB_PASSWORD}
    volumes:
      - postgres_rag_data:/var/lib/postgresql/data
      - ./sql/init-rag.sql:/docker-entrypoint-initdb.d/init.sql

volumes:
  postgres_rag_data:
```

### 7.2 初始化 SQL

`sql/init-rag.sql`：创建 pgvector 扩展 + 建表 + 索引。zhparser 扩展若未安装则回退使用 `simple` 分词（通过 DO 块判断），不影响核心功能。

### 7.3 服务注册

- 服务名：`rag-service`
- 端口：`8087`
- Nacos 命名空间：`public`
- 网关路由：需在 gateway-service 配置中添加 rag-service 路由（注意 SSE 长连接超时设长）

---

## 8. 包结构

```
com.hmdp.rag
├── controller          // KnowledgeBaseController, DocumentController, QaController
├── service             // IKnowledgeBaseService, IDocumentService, IQaService, IAuditService
│   └── impl
├── pipeline            // DocumentPipeline, QaPipeline
├── parser              // TikaDocumentParser (接口 IDocumentParser)
├── splitter            // SemanticChunkSplitter (接口 ITextSplitter)
├── embedding           // GlmEmbeddingClient (接口 IEmbeddingClient)
├── retriever           // VectorRetriever, KeywordRetriever, HybridRetriever, RrfFusion
├── reranker            // RerankerStrategy 接口, NoOpReranker, GlmReranker(预留)
├── llm                 // GlmLlmClient (接口 ILlmClient)
├── repository          // KnowledgeBaseRepo(MyBatis Plus), DocumentChunkRepo(JDBC)
├── entity              // KnowledgeBase, Document, DocumentChunk, AuditLog
├── dto                 // 请求/响应 DTO
├── config              // PgVectorConfig, GlmApiConfig, RetryConfig, SwaggerConfig
├── mq                  // DocumentProcessConsumer
└── handler             // GlobalExceptionHandler
```

---

## 9. 错误处理

### 9.1 外部 API 重试策略

- GLM API 调用：最多 3 次重试，指数退避（1s → 2s → 4s）
- 超时：连接 30s，读取 120s（LLM 生成可能较慢）
- 可重试错误码：429, 500, 502, 503
- 不可重试：401, 403（直接抛异常）

### 9.2 文档处理异常

- Tika 解析失败 → status=FAILED，记录 error_msg + 审计日志
- Embedding API 失败 → 重试 3 次后 FAILED
- DB 写入失败 → RocketMQ 重试（默认 16 次），最终进入 DLQ

### 9.3 全局异常处理

统一使用 `GlobalExceptionHandler`（同 order-service 模式），返回 `Result.fail()`。

---

## 10. 交付物清单

| 交付物 | 说明 |
|--------|------|
| rag-service/pom.xml | Maven 模块，依赖声明 |
| rag-service/src/main/java/**/*.java | 全部 Java 源码 |
| rag-service/src/main/resources/application.yml | 本地配置（敏感信息用环境变量） |
| rag-service/src/main/resources/bootstrap.yml | Nacos 引导配置 |
| sql/init-rag.sql | 初始化 SQL（扩展+建表+索引） |
| docker-compose.yml | 追加 PostgreSQL pgvector 服务 |
| openapi.json | Swagger API 文档 |
| README-RAG.md | 使用说明 + 选型说明 |
```

**设计文档的范围到此为止。** spec 自审后提交。
