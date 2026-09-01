# 阶段二：核心数据模型与工单基础模块开发计划

| 项 | 内容 |
|---|---|
| 所属产品 | LiveHub 智能客服 Agent（模块 M5） |
| 上游文档 | 《PRD-智能客服工单Agent.md》v1.0 |
| 阶段编号 | Phase 2 / 5（对齐里程碑 MS1，W1-W2） |
| 前置阶段 | Phase 1 需求分析与架构设计（T1.5 order-service 接口确认） |
| 后续阶段 | Phase 3 Agent 对话与智能路由模块 |

---

## 1. 阶段目标

1. 建成 agent-service 工程底座：会话管理、SSE 全链路、ToolRegistry、ReAct 循环骨架、短期记忆（Redis）、审计表落库，全部可运行。
2. 交付 FR-01（客服入口与会话建立）、FR-02（多轮对话与上下文管理）、FR-04（工具调用过程可视化）基础版，达到 PRD 验收标准。
3. 打通第一个端到端工具调用：`query_my_orders`（Feign → order-service 新增接口），从用户提问到订单卡片输出的完整链路。
4. 完成 MS1 退出标准验证："curl 走 SSE 完成一次带工具调用的问答；agent_tool_call 完整留痕"。

**PRD 原文依据（第 7 章 MS1）**：agent-service 骨架 = 会话/SSE/ToolRegistry/ReAct 循环/短期记忆/审计表；FR-01/02/04 基础版；1 个工具（query_my_orders）端到端。

---

## 2. 任务拆解

| 任务编号 | 任务 | 对应 PRD 条目 | 说明 |
|---|---|---|---|
| T2.1 | 建库建表：agent_service 库四张表 DDL 落库 | 6.1 | agent_session / agent_tool_call / agent_task / agent_ticket；含补充字段 rating、transfer_reason、dedup_key；agent_tool_call 仅追加约束 |
| T2.2 | agent-service 服务骨架与配置接入 | 4.4 | Java 21 / Spring Boot 3.1.12；注册 Nacos；Redis / RocketMQ / MySQL 客户端接入；Feign 客户端声明 |
| T2.3 | 会话管理模块（agent_session 生命周期） | FR-01 | 创建会话（ACTIVE）/ 30 分钟空闲自动 CLOSED / 并发会话检测与复用提示逻辑 |
| T2.4 | SSE 通道与异步链路 | FR-01、4.1 | POST /agent/chat 建立 SSE；异步 Servlet 全链路；首 token 埋点（m5_first_token）；3s 建连失败前端降级轮询的后端配合（重试 1 次语义） |
| T2.5 | 欢迎语流式输出与上下文预注入 | FR-01 | 能力提示话术；entry 入口埋点（my/order_detail/voucher_detail）；context={orderId\|voucherId\|shopId} 预注入会话 |
| T2.6 | 短期记忆模块（Redis） | FR-02 | 最近 10 轮历史存取、TTL 管理、会话关闭后 key 过期 |
| T2.7 | LLM 摘要压缩机制 | FR-02 | 超 10 轮触发历史 → 512 字摘要写入 agent_session.summary；失败降级"仅保留最近 6 轮"不阻塞对话 |
| T2.8 | 消息输入预处理 | FR-02 | >500 字截断提示；空消息/纯表情返回引导话术不进 LLM |
| T2.9 | ToolRegistry 工具注册机制 | 7 章 MS1 | 工具定义（名称/参数 schema/友好文案/权限注解）、注册与发现；为 Phase 3 批量工具接入提供统一入口 |
| T2.10 | ReAct 循环骨架 | 7 章 MS1、附录 A | Thought / Action / Observation 循环，最大 8 步上限；工具结果以"数据"格式回填（非指令，4.2 注入加固） |
| T2.11 | order-service 新增"按 userId 批量查询"接口 | 附录 B、FR-05 | Feign 接口 + 服务端实现（含归属校验），本 PRD 唯一业务服务改造点 |
| T2.12 | 工具实现：query_my_orders(orderId?, status?, timeRange?) | FR-05 | 结果 → LLM 生成结构化订单卡片列表（券名/金额/状态/时间，倒序，默认 5 条）；空结果引导扩大范围（7 天→30 天） |
| T2.13 | 工具审计留痕 | 4.2、6.1 | 每次调用写 agent_tool_call（tool_name/args/result/latency_ms/success/trace_id），支持按会话回放追溯 |
| T2.14 | 工具调用可视化事件（FR-04 基础版） | FR-04 | SSE 推送 tool_call（友好文案）/ tool_result（结果摘要）事件；失败态文案；摘要脱敏（手机号 138****1234） |
| T2.15 | 基础埋点接入 | 6.2 | m5_session_start / m5_msg_send / m5_first_token / m5_tool_call 四个事件服务端直写 |
| T2.16 | MS1 退出验证 | 第 7 章 | curl SSE 全链路演示脚本 + agent_tool_call 留痕核查 + FR-01/02/04 基础验收用例执行 |

---

## 3. 任务依赖关系

```
T2.1 建表 ──→ T2.3 会话管理 ──→ T2.4 SSE 通道 ──→ T2.5 欢迎语/预注入
T2.2 服务骨架 ─┬─↗                    │
               │                      ↓
               │            T2.6 短期记忆 → T2.7 摘要压缩
               │            T2.8 输入预处理
               │
               ├─→ T2.9 ToolRegistry → T2.10 ReAct 骨架 → T2.12 query_my_orders
               │                                          │
               │        T2.11 order-service 接口 ─────────┘
               │
               └─→ T2.13 审计留痕 ←（工具调用）← T2.12
                            ↓
               T2.14 可视化事件 → T2.15 埋点 → T2.16 MS1 退出验证

关键路径：T2.2 → T2.9 → T2.10 → T2.11 → T2.12 → T2.13 → T2.16
```

- T2.11 是外部依赖任务，应最先启动（接口评审在 Phase 1 已确认，此处为编码实现）。
- T2.4 / T2.6 / T2.7 / T2.8（对话线）与 T2.9 / T2.10 / T2.12（工具线）可双线并行。
- T2.16 必须在 T2.13、T2.14 完成后执行，对应 MS1 退出标准。

---

## 4. 关键交付物

| # | 交付物 | 形式 | 对应任务 |
|---|---|---|---|
| D2.1 | agent_service 库 DDL 脚本（4 表 + 索引） | SQL | T2.1 |
| D2.2 | agent-service 可运行服务（含会话/SSE/记忆/摘要/预处理） | 代码 + 单测 | T2.2~T2.8 |
| D2.3 | ToolRegistry + ReAct 循环骨架（含 8 步上限与数据回填格式） | 代码 + 单测 | T2.9 / T2.10 |
| D2.4 | order-service 订单批量查询接口（含归属校验与接口文档） | 代码 + Markdown | T2.11 |
| D2.5 | query_my_orders 工具端到端链路（含订单卡片渲染协议） | 代码 + 联调报告 | T2.12~T2.14 |
| D2.6 | 工具审计模块（agent_tool_call 全量留痕，trace_id 贯通） | 代码 | T2.13 |
| D2.7 | MS1 验证报告（curl 演示记录 + FR-01/02/04 基础用例结果） | Markdown | T2.16 |

---

## 5. 验收标准（对齐 PRD 条目级验收标准）

**MS1 退出标准（第 7 章）**
1. curl 走 SSE 完成一次带工具调用的问答（演示脚本可复现）。
2. agent_tool_call 完整留痕：参数/结果/耗时/trace_id 均可查。

**FR-01 验收标准（3.1）**
3. 登录态下从任一入口进入，2s 内出现欢迎语首字。
4. 带订单上下文进入后，首句"这个单子怎么退款了"能正确关联该订单（集成测试用例覆盖）。
5. 断网重连后（5 分钟内）会话上下文不丢失。

**FR-02 验收标准（3.2）**
6. 10 轮以内指代消解正确率 ≥ 90%（测试集 50 组，初版抽测 20 组）。
7. 第 11 轮起摘要生效，单请求 prompt Token 不超过 4K（日志抽查）。
8. 会话关闭后 Redis key 过期，无内存泄漏（压测后 Redis 内存回归基线）。

**FR-04 基础验收标准（3.4）**
9. 每次工具调用前端可见状态条，tool_call → tool_result 的 UI 反馈间隔 ≤ 100ms。
10. 工具结果摘要中敏感字段脱敏率 100%。
11. 状态条文案与实际调用工具语义一致（抽查 20 次无错位）。

**FR-05 首工具验收标准（3.5 部分，完整验收在 Phase 3）**
12. 查询结果与 order-service 数据库直查 100% 一致（先做 20 条对拍冒烟，全量 100 条在 Phase 3 补齐）。
13. 越权查询（他人订单号）100% 被拦截，响应不泄露订单存在性。

---

## 6. 潜在风险与应对措施

| # | 风险 | 概率 | 影响 | 应对措施 |
|---|---|---|---|---|
| P2-R1 | SSE 异步链路实现踩坑（线程模型/超时/断连重连），拖慢进度 | 中 | 高 | Phase 1 Spike 结论复用；先做最小可运行链路再叠加功能；问题 3 次未解即升级架构评审 |
| P2-R2 | order-service 接口延期 | 中 | 高 | T2.12 先对接 Mock Feign；接口联调排期前置确认；每日站会跟踪该项状态 |
| P2-R3 | LLM 输出不稳定导致卡片渲染协议解析失败（R2 前兆） | 中 | 中 | 本阶段即引入 JSON mode + schema 校验 + 重试 2 次的输出解析组件，供 Phase 3 复用 |
| P2-R4 | Redis 记忆 TTL/摘要时序 bug 引发上下文丢失 | 中 | 中 | T2.6/T2.7 配套集成测试（断连恢复、11 轮压缩、过期清理三类场景）；验收标准 8 作为门禁 |
| P2-R5 | 摘要压缩增加首响应延迟，冲击 P90 ≤ 1.5s | 低 | 中 | 摘要异步执行（不阻塞当前轮响应）；m5_first_token 埋点监控 |
| P2-R6 | 范围蔓延：提前实现意图识别/退款（属 Phase 3/4） | 高 | 中 | 严格按本阶段任务清单执行；新想法记入 Phase 3/4 备忘（对齐 R9） |
