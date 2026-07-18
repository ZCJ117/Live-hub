# RAG Service -- 智能知识问答服务

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
docker compose -f docker-compose.yml up -d postgres-rag
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
