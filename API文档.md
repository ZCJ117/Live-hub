# API 接口文档

> 基于 OpenAPI 3.1.0 规范  
> 服务地址: `http://localhost:18088`  
> Swagger UI: `http://localhost:18088/swagger-ui/index.html`  
> 接口总数: **367 个**

---

## 目录

1. [工作区管理 (Workspace)](#1-工作区管理)
2. [工作流管理 (Workflow)](#2-工作流管理)
3. [Agent管理](#3-agent管理)
4. [Agent能力绑定](#4-agent能力绑定)
5. [会话管理 (Conversation)](#5-会话管理)
6. [Web聊天](#6-web聊天)
7. [渠道管理 (Channel)](#7-渠道管理)
8. [模型配置管理 (Model)](#8-模型配置管理)
9. [MCP Server管理](#9-mcp-server管理)
10. [工具管理 (Tool)](#10-工具管理)
11. [技能管理 (Skill)](#11-技能管理)
12. [技能安装](#12-技能安装)
13. [触发器管理 (Trigger)](#13-触发器管理)
14. [定时任务管理 (Cron Job)](#14-定时任务管理)
15. [Wiki 知识库](#15-wiki-知识库)
16. [Wiki Transformations](#16-wiki-transformations)
17. [Wiki Deep Research / Admin / Hot Cache](#17-wiki-其他)
18. [认证管理 (Auth)](#18-认证管理)
19. [系统设置 (Settings)](#19-系统设置)
20. [安全管理 (Security)](#20-安全管理)
21. [数据源管理 (Datasource)](#21-数据源管理)
22. [Dashboard](#22-dashboard)
23. [Agent Runtime](#23-agent-runtime)
24. [LLM Provider池](#24-llm-provider池)
25. [OAuth管理](#25-oauth管理)
26. [记忆管理 (Memory)](#26-记忆管理)
27. [其他模块](#27-其他模块)

---

## 1. 工作区管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workspaces` | 获取当前用户的工作区列表 |
| POST | `/api/v1/workspaces` | 创建工作区 |
| GET | `/api/v1/workspaces/{id}` | 获取工作区详情 |
| PUT | `/api/v1/workspaces/{id}` | 更新工作区 |
| DELETE | `/api/v1/workspaces/{id}` | 删除工作区 |
| GET | `/api/v1/workspaces/{id}/access` | 获取当前用户在指定工作区的访问能力 |
| GET | `/api/v1/workspaces/{id}/members` | 获取工作区成员列表 |
| POST | `/api/v1/workspaces/{id}/members` | 添加工作区成员 |
| PUT | `/api/v1/workspaces/{id}/members/{memberId}` | 更新成员角色 |
| DELETE | `/api/v1/workspaces/{id}/members/{memberId}` | 移除工作区成员 |

---

## 2. 工作流管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/workflows` | 列出工作区中的工作流 |
| POST | `/api/v1/workflows` | 创建新的工作流行 |
| GET | `/api/v1/workflows/{id}` | 按ID获取工作流 |
| PUT | `/api/v1/workflows/{id}` | 更新工作流元数据 |
| DELETE | `/api/v1/workflows/{id}` | 软删除工作流 |
| POST | `/api/v1/workflows/{id}/compile` | 编译草稿并返回诊断信息 |
| PUT | `/api/v1/workflows/{id}/draft` | 保存内联草稿 graph_json |
| POST | `/api/v1/workflows/{id}/publish` | 编译草稿并持久化发布新版本 |
| GET | `/api/v1/workflows/{id}/runs` | 列出工作流的最近运行记录 |
| POST | `/api/v1/workflows/draft/generate` | 从自然语言描述生成工作流草稿 |
| POST | `/api/v1/workflows/draft/preview-compile` | 编译任意草稿 JSON 预览 |
| GET | `/api/v1/workflows/draft/templates` | 列出标准工作流模板 |
| GET | `/api/v1/workflows/runs/{runId}` | 检查单次运行及步骤详情 |
| POST | `/api/v1/workflows/runs/{runId}/resume` | 恢复暂停的工作流运行 |
| GET | `/api/v1/workflows/runs/paused` | 列出工作区中暂停的运行 |

---

## 3. Agent管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents` | 获取Agent列表 |
| POST | `/api/v1/agents` | 创建Agent |
| GET | `/api/v1/agents/{id}` | 获取Agent详情 |
| PUT | `/api/v1/agents/{id}` | 更新Agent |
| DELETE | `/api/v1/agents/{id}` | 删除Agent |
| GET | `/api/v1/agents/{id}/capabilities` | 获取Agent当前能力 |
| POST | `/api/v1/agents/{id}/chat` | 同步对话 |
| GET | `/api/v1/agents/{id}/chat/stream` | 流式对话 (SSE) |
| POST | `/api/v1/agents/{id}/execute` | 执行复杂任务 (Plan-Execute) |
| GET | `/api/v1/agents/{id}/state` | 获取Agent运行状态 |

---

## 4. Agent能力绑定

### Provider偏好
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents/{agentId}/provider-preferences` | 获取Agent的偏好Provider顺序 |
| PUT | `/api/v1/agents/{agentId}/provider-preferences` | 批量设置偏好Provider |

### Skill绑定
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents/{agentId}/skills` | 获取Agent已绑定的Skills |
| PUT | `/api/v1/agents/{agentId}/skills` | 批量设置Skill绑定 |
| POST | `/api/v1/agents/{agentId}/skills/{skillId}` | 绑定单个Skill |
| DELETE | `/api/v1/agents/{agentId}/skills/{skillId}` | 解绑单个Skill |

### Tool绑定
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents/{agentId}/tools` | 获取Agent已绑定的Tools |
| PUT | `/api/v1/agents/{agentId}/tools` | 批量设置Tool绑定 |

---

## 5. 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/conversations` | 获取会话列表 |
| GET | `/api/v1/conversations/page` | 分页查询会话列表 |
| DELETE | `/api/v1/conversations/{conversationId}` | 删除会话 |
| DELETE | `/api/v1/conversations/{conversationId}/messages` | 清空会话消息 |
| GET | `/api/v1/conversations/{conversationId}/messages` | 获取会话消息历史 |
| PUT | `/api/v1/conversations/{conversationId}/model` | 切换会话使用的模型 |
| PUT | `/api/v1/conversations/{conversationId}/pin` | 置顶/取消置顶会话 |
| PUT | `/api/v1/conversations/{conversationId}/title` | 重命名会话 |
| GET | `/api/v1/conversations/{conversationId}/status` | 获取会话流状态 |
| POST | `/api/v1/conversations/batch-delete` | 批量删除会话 |

---

## 6. Web聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat/stream` | 结构化SSE流式对话 |
| POST | `/api/v1/chat/{conversationId}/interrupt` | 排队后续消息 |
| POST | `/api/v1/chat/{conversationId}/stop` | 停止流式生成 |
| POST | `/api/v1/chat/upload` | 上传聊天附件 |
| GET | `/api/v1/chat/files/{conversationId}/{storedName}` | 读取聊天附件 |

### 工具审批
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/chat/{conversationId}/pending-approvals` | 查询待审批记录 |

---

## 7. 渠道管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/channels` | 获取渠道列表 |
| POST | `/api/v1/channels` | 创建渠道 |
| GET | `/api/v1/channels/{id}` | 获取渠道详情 |
| PUT | `/api/v1/channels/{id}` | 更新渠道 |
| DELETE | `/api/v1/channels/{id}` | 删除渠道 |
| PUT | `/api/v1/channels/{id}/toggle` | 启用/禁用渠道 |
| GET | `/api/v1/channels/{id}/health` | 获取指定渠道的实时健康状态 |
| GET | `/api/v1/channels/health` | 批量获取所有渠道健康状态 |
| GET | `/api/v1/channels/status` | 获取渠道运行状态 |
| GET | `/api/v1/channels/type/{channelType}` | 按类型获取渠道列表 |
| POST | `/api/v1/channels/preflight` | 验证草稿渠道配置 |

### WebChat嵌入
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/channels/webchat/config` | 获取WebChat配置 |
| POST | `/api/v1/channels/webchat/stream` | WebChat SSE流式对话 |

### Webhook
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/channels/webhook/dingtalk` | 钉钉消息回调 |
| POST | `/api/v1/channels/webhook/feishu` | 飞书消息回调 |
| POST | `/api/v1/channels/webhook/slack` | Slack Events API回调 |
| POST | `/api/v1/channels/webhook/telegram` | Telegram消息回调 |
| POST | `/api/v1/channels/webhook/wecom` | 企业微信消息回调 |
| GET | `/api/v1/channels/webhook/status` | 获取渠道运行状态 |

### QR扫码授权
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/channels/qrcode/{channelType}/begin` | 启动扫码授权流程 |
| GET | `/api/v1/channels/qrcode/{channelType}/status` | 查询扫码授权状态 |

---

## 8. 模型配置管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/models` | 获取Provider列表 (仅enabled) |
| POST | `/api/v1/models` | 创建模型 |
| GET | `/api/v1/models/{id}` | 获取模型详情 |
| PUT | `/api/v1/models/{id}` | 更新模型 |
| DELETE | `/api/v1/models/{id}` | 删除模型 |
| POST | `/api/v1/models/{id}/default` | 设置默认模型 |
| GET | `/api/v1/models/default` | 获取默认模型 |
| PUT | `/api/v1/models/{providerId}/config` | 更新Provider配置 |
| POST | `/api/v1/models/{providerId}/disable` | 禁用Provider |
| POST | `/api/v1/models/{providerId}/enable` | 启用Provider |
| POST | `/api/v1/models/{providerId}/discover` | 发现远程模型 |
| POST | `/api/v1/models/{providerId}/discover/apply` | 批量添加发现的模型 |
| POST | `/api/v1/models/{providerId}/models` | 向Provider添加模型 |
| DELETE | `/api/v1/models/{providerId}/models` | 从Provider删除模型 |
| POST | `/api/v1/models/{providerId}/models/test` | 测试单个模型可用性 |
| POST | `/api/v1/models/{providerId}/test-connection` | 测试供应商连接 |
| GET | `/api/v1/models/active` | 获取当前激活模型 |
| PUT | `/api/v1/models/active` | 设置当前激活模型 |
| GET | `/api/v1/models/by-type` | 按类型筛选模型 |
| GET | `/api/v1/models/catalog` | 获取Provider全量目录 |
| GET | `/api/v1/models/enabled` | 获取启用模型列表 |
| POST | `/api/v1/models/custom-providers` | 创建自定义Provider |
| DELETE | `/api/v1/models/custom-providers/{providerId}` | 删除自定义Provider |

### Embedding模型
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/models/embedding/default` | 获取系统默认Embedding模型ID |
| POST | `/api/v1/models/embedding/default` | 设置默认Embedding模型 |
| POST | `/api/v1/models/embedding/{modelId}/test` | 测试Embedding模型连通性 |

---

## 9. MCP Server管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/mcp/servers` | 获取MCP Server列表 |
| POST | `/api/v1/mcp/servers` | 创建MCP Server |
| GET | `/api/v1/mcp/servers/{id}` | 获取MCP Server详情 |
| PUT | `/api/v1/mcp/servers/{id}` | 更新MCP Server |
| DELETE | `/api/v1/mcp/servers/{id}` | 删除MCP Server |
| POST | `/api/v1/mcp/servers/{id}/test` | 测试MCP Server连接 |
| PUT | `/api/v1/mcp/servers/{id}/toggle` | 启用/禁用MCP Server |
| GET | `/api/v1/mcp/servers/{id}/tools` | 列出MCP Server已发现的工具 |
| POST | `/api/v1/mcp/servers/refresh` | 刷新所有MCP Server连接 |

---

## 10. 工具管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/tools` | 获取工具列表 |
| POST | `/api/v1/tools` | 创建工具 (MCP) |
| GET | `/api/v1/tools/{id}` | 获取工具详情 |
| PUT | `/api/v1/tools/{id}` | 更新工具 |
| DELETE | `/api/v1/tools/{id}` | 删除工具 |
| PUT | `/api/v1/tools/{id}/toggle` | 启用/禁用工具 |
| GET | `/api/v1/tools/available` | 获取全部可绑定原子工具 |
| GET | `/api/v1/tools/enabled` | 获取已启用工具列表 |

---

## 11. 技能管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills` | 获取技能分页列表 |
| POST | `/api/v1/skills` | 创建技能 |
| GET | `/api/v1/skills/{id}` | 获取技能详情 |
| PUT | `/api/v1/skills/{id}` | 更新技能 |
| DELETE | `/api/v1/skills/{id}` | 硬删除技能 |
| PUT | `/api/v1/skills/{id}/toggle` | 启用/禁用技能 |
| POST | `/api/v1/skills/{id}/archive` | 手动归档技能 |
| POST | `/api/v1/skills/{id}/restore` | 恢复已归档技能 |
| POST | `/api/v1/skills/{id}/pin` | 钉住/取消钉住技能 |
| POST | `/api/v1/skills/{id}/rescan` | 重新扫描单个技能 |
| POST | `/api/v1/skills/{id}/sync-files` | 重新同步技能文件 |
| POST | `/api/v1/skills/{id}/export-workspace` | 将skill导出到工作区目录 |
| GET | `/api/v1/skills/{id}/employees` | 列出可使用此技能的Agent |
| GET | `/api/v1/skills/{id}/lessons` | 读取技能LESSONS.md |
| POST | `/api/v1/skills/{id}/lessons/clear` | 清除技能的所有lessons |
| GET | `/api/v1/skills/{id}/requirements` | 技能前置要求状态 |
| GET | `/api/v1/skills/{id}/workspace` | 获取skill工作区信息 |
| GET | `/api/v1/skills/enabled` | 获取已启用技能列表 |
| GET | `/api/v1/skills/counts` | 获取各类型技能计数 |
| GET | `/api/v1/skills/summary` | 获取已启用技能摘要 |
| GET | `/api/v1/skills/type/{skillType}` | 按类型获取技能列表 |
| POST | `/api/v1/skills/sync-files` | 重新同步所有技能文件 |
| POST | `/api/v1/skills/synthesize-from-conversation` | 从对话历史合成Skill |

### Skill Curator
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/curator/status` | curator控制面状态 |
| POST | `/api/v1/skills/curator/activate` | 激活/取消激活curator |
| POST | `/api/v1/skills/curator/pause` | 暂停curator定时扫描 |
| POST | `/api/v1/skills/curator/resume` | 恢复curator定时扫描 |
| POST | `/api/v1/skills/curator/dry-run` | 立即运行一次预览 |
| GET | `/api/v1/skills/curator/reports` | 列出最近运行报告 |
| GET | `/api/v1/skills/curator/reports/{runId}` | 读取某次运行报告 |

### Skill Runtime
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/runtime/active` | 获取active skills运行时视图 |
| GET | `/api/v1/skills/runtime/status` | 获取所有技能运行时解析状态 |
| POST | `/api/v1/skills/runtime/refresh` | 刷新active skills缓存 |

### Skill Secrets
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/{skillId}/secrets` | 列出密钥键和掩码预览 |
| POST | `/api/v1/skills/{skillId}/secrets` | 更新密钥值 |
| DELETE | `/api/v1/skills/{skillId}/secrets/{key}` | 删除单个密钥 |

### Skill Prompt Preview
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/prompt-preview` | 预览技能Prompt增强效果 |

---

## 12. 技能安装

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/skills/install/hub/search` | 搜索ClawHub市场 |
| POST | `/api/v1/skills/install/start` | 开始异步安装skill |
| POST | `/api/v1/skills/install/upload` | 上传ZIP安装skill |
| GET | `/api/v1/skills/install/status/{taskId}` | 查询安装任务状态 |
| POST | `/api/v1/skills/install/cancel/{taskId}` | 取消安装任务 |
| DELETE | `/api/v1/skills/install/{skillName}` | 卸载skill |

---

## 13. 触发器管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/triggers` | 列出工作区中的触发器 |
| POST | `/api/v1/triggers` | 创建触发器 |
| GET | `/api/v1/triggers/{id}` | 获取触发器详情 |
| PUT | `/api/v1/triggers/{id}` | 更新触发器 |
| DELETE | `/api/v1/triggers/{id}` | 删除触发器 |
| POST | `/api/v1/triggers/events` | 接入事件信封 |

---

## 14. 定时任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/cron-jobs` | 获取定时任务列表 |
| POST | `/api/v1/cron-jobs` | 创建定时任务 |
| GET | `/api/v1/cron-jobs/{id}` | 获取定时任务详情 |
| PUT | `/api/v1/cron-jobs/{id}` | 更新定时任务 |
| DELETE | `/api/v1/cron-jobs/{id}` | 删除定时任务 |
| POST | `/api/v1/cron-jobs/{id}/run` | 立即执行 |
| PUT | `/api/v1/cron-jobs/{id}/toggle` | 启用/禁用 |
| GET | `/api/v1/cron-jobs/active-runs` | 查询正在执行的运行 |

---

## 15. Wiki 知识库

### 知识库CRUD
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/wiki/knowledge-bases` | 获取所有知识库 |
| POST | `/api/v1/wiki/knowledge-bases` | 创建知识库 |
| GET | `/api/v1/wiki/knowledge-bases/{id}` | 获取知识库详情 |
| PUT | `/api/v1/wiki/knowledge-bases/{id}` | 更新知识库 |
| DELETE | `/api/v1/wiki/knowledge-bases/{id}` | 删除知识库 |
| GET | `/api/v1/wiki/knowledge-bases/agent/{agentId}` | 按Agent获取知识库 |

### 知识库配置
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/wiki/knowledge-bases/{id}/config` | 获取知识库配置 |
| PUT | `/api/v1/wiki/knowledge-bases/{id}/config` | 更新知识库配置 |
| PUT | `/api/v1/wiki/knowledge-bases/{id}/source-directory` | 设置知识库关联目录 |

### 原始材料管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/raw` | 获取原始材料列表 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/raw/text` | 添加文本材料 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/raw/upload` | 上传文件材料 |
| DELETE | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}` | 删除原始材料 |
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/download` | 下载原始材料 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/reprocess` | 重新处理原始材料 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/raw/{rawId}/cancel` | 取消正在进行的处理 |

### 知识库处理
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/wiki/knowledge-bases/{id}/scan` | 扫描关联目录导入文件 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/process` | 触发知识库处理 |
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/processing-status` | 获取处理状态 |
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/progress` | 订阅处理进度SSE |

### Wiki页面管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/pages` | 获取Wiki页面列表 |
| GET | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | 获取Wiki页面内容 |
| PUT | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | 手动编辑Wiki页面 |
| DELETE | `/api/v1/wiki/knowledge-bases/{kbId}/pages/{slug}` | 删除Wiki页面 |
| DELETE | `/api/v1/wiki/knowledge-bases/{kbId}/pages/batch` | 批量删除Wiki页面 |
| POST | `/api/v1/wiki/knowledge-bases/{kbId}/