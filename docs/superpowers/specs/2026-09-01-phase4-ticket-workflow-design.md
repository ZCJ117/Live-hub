# Phase 4：工单流转与人机协同、安全风控及集成联调 — 设计规格

| 项 | 内容 |
|---|---|
| 日期 | 2026-09-01 |
| 依据 | 《Phase4-工单流转与人机协同集成联调-开发计划.md》T4.1~T4.16 + 《PRD-智能客服工单Agent.md》v1.0（FR-08/09/10/11、附录 A/B/C、§4.2/4.3） |
| 前置 | Phase 2/3 已交付：Planner 7 类意图分流、ReAct 引擎（连败中断钩子）、5 个只读工具、TicketService 基础（dedup/TK 工单号/组路由/SLA/状态机）、agent_task/agent_ticket 表与 DDL（uk_action_id 已建） |
| 范围 | T4.1~T4.16 全部任务；外部改动：order-service 退款接口、social-service 站内信通道、gateway 限流 |
| 明确不含 | P2 客服工作台（R8/R9 冻结至 MS4）；前端页面改造（SSE 协议向后兼容扩展）；真实坐席系统（默认无坐席，见决策 D-5） |

## 0. 已确认的关键决策

| # | 决策 | 结论 |
|---|---|---|
| D-1 | MQ 站内信链路 | **完整实现**：agent-service 发 `agent-m5-ticket-route` + social-service 新增 MQ 依赖、`hmdp.tb_notification` 表、消费端与查询接口（不采用 P4-R5 缓冲方案） |
| D-2 | Sentinel 引入方式 | **Sentinel Core + 编程式规则**（`spring-cloud-starter-alibaba-sentinel`，规则走 yaml/代码），不部署 Dashboard |
| D-3 | 敏感词/规则热更新 | **Nacos 配置 + @RefreshScope**：`agent.security.*` 配置组托管 Nacos，RefreshEvent 重编译 Pattern；本地 yaml 兜底初始值 |
| D-4 | 退款架构 | **双闸门分权，不引入 Seata**：agent confirm 接口第一道复核 + order-service 退款接口第二道独立复核（原子 UPDATE 为最终裁决）；建单失败重试 + 每日对账脚本补偿（P4-R1） |
| D-5 | 转人工坐席 | **默认无坐席，只做无人值守路径**：确认转人工 → TRANSFERRED → 立即建单 + 告知时效；SSE 协议预留 `role` 字段供 P2 接入；移交包存 Redis（TTL 7 天） |

## 1. 总体架构与改动面

| 服务 | 改动 |
|---|---|
| **agent-service**（主） | 新增包：confirm / flow / transfer / security / resilience / mq；改造：ChatOrchestratorService（三桩分支落地 + 检测与过滤挂接 + TRANSFERRED 状态锁）、ReActEngine、GlmClient（重试+备用模型）、AgentProperties；pom 加 rocketmq + sentinel |
| **order-service** | 新增 `POST /voucher-order/refund` 退款受理接口（原子 UPDATE） |
| **social-service** | 新增 rocketmq 依赖 + `hmdp.tb_notification` + `TicketNotifyConsumer` + `GET /notification/my` |
| **gateway-service** | 新增 agent-route 独立令牌桶限流过滤器（Redis + Lua，维度 = loginId）；social-route 路由前缀补 `/notification/**`（站内信接口经网关访问，CLAUDE.md 约束） |
| **common** | 无改动（VoucherOrder.status 已含 5=退款中/6=已退款） |

**新增 SQL：**
- `sql/phase4-notification.sql`：hmdp 库 `tb_notification`（id/user_id/type/title/content/related_id/create_time，idx_user）
- `sql/phase4-agent-task-biz-order.sql`：agent_task 加 `biz_order_id BIGINT NULL` + `KEY idx_order_status(biz_order_id, status)`（支撑"同订单已有退款"O(1) 查询）

**agent-service 新包结构：**
```
com.hmdp.agent
├── confirm/     ConfirmTaskService（agent_task 生命周期）、ConfirmController
├── flow/        RefundFlowService、ComplaintFlowService（要素收集状态机）
├── transfer/    TransferService（四类触发器 + 卡片 + 移交包 + 无人值守建单）
├── security/    SensitiveWordService（Nacos 热更新）、InjectionDetector、
│                OutputFilter、EmotionDetector
├── resilience/  LlmResilience 支持、SentinelRuleConfig、FAQ 降级
└── mq/          TicketNotifyProducer（topic: agent-m5-ticket-route）
```

**一轮对话的新链路（相对 Phase 3 增量）：**
```
doChat: 输入预处理 → 【新增】注入/敏感词检测（命中→固定话术+m5_input_blocked 留痕，不进 LLM）
      → 【新增】TRANSFERRED 状态锁（命中→消息入历史+固定确认，零 LLM 输出）
      → Planner 分流（不变；澄清超限改为触发转人工）
      → dispatch：REFUND→RefundFlowService；COMPLAINT→ComplaintFlowService；
                  HUMAN_DEMAND / TOOL_CONSECUTIVE_FAIL / 情绪负面 → TransferService
      → 【新增】输出过滤（流式滑动窗口；命中→重生成 1 次→兜底+建议转人工）
      → LLM 调用经重试容灾（3 重试→备用模型→FAQ 直答+建单入口）
```

**Phase 3 行为变更（1 处）：** 澄清第 3 轮（超限）原降级 CLARIFY_MENU，按 T4.8 改为触发转人工卡片；JSON 解析 3 连败的降级菜单**保留不变**（解析故障 ≠ 用户表达不清）。

## 2. 退款线（T4.1~T4.5）

### 2.1 确认卡片通用机制（T4.1）

`ConfirmTaskService` 管理 `agent_task` 全生命周期：
- 生成：taskType=REFUND_REQUEST、status=PENDING_CONFIRM、actionId=UUID（唯一键幂等凭证）、expireTime=now+10min、payloadJson={orderId, voucherTitle, payValue, createTime, reasonEnum:["不要了","未收到","与描述不符","其他"], refundNotice}
- 状态机：PENDING_CONFIRM → ADOPTED（确认胜出）/ REJECTED（确认失败或取消）/ EXPIRED（懒过期）
- 埋点：卡片生成 `m5_refund_card_show`

### 2.2 退款编排（T4.2）

REFUND 分支不走通用 ReAct（LLM 文本无法直接产出卡片参数），`RefundFlowService` 直接调用 `QueryMyOrdersTool` 拿结构化订单：

| 候选情况 | 行为 |
|---|---|
| 0 单可退（全部已核销/已完成） | 不生成卡片，明确说明不可退原因（PRD 边界） |
| 恰 1 单 status=2 | 直接生成确认卡片 + SSE `card` 事件 `REFUND_CONFIRM` |
| 多单 status=2 | 复用既有 ORDER_LIST 卡片让用户点选（点选带 context.orderId 下一轮进入，走 focusOrder 既有机制） |
| focusOrder 已定且可退 | 直接生成卡片 |

- 卡片参数**只用工具返回的真实订单数据，LLM 输出的订单号仅作候选**（P4-R3）
- flowState：Planner REFUND 路由已置 REFUNDING；confirm 成功/取消 → 回 IDLE

### 2.3 确认提交接口与二次校验（T4.3）

`POST /agent/chat/{sessionId}/confirm`（同步 JSON，非 SSE），body `{actionId, decision: CONFIRM|CANCEL, reason?, reasonText?}`：

1. 会话归属校验（sessionService.getOwned）
2. 按 actionId + userId 查 task；不存在 → 拦截（越权/篡改）
3. 懒过期：expireTime < now → 置 EXPIRED，提示重新发起
4. **条件更新抢确认**：`UPDATE agent_task SET status='ADOPTED', confirm_time=now() WHERE action_id=? AND status='PENDING_CONFIRM'`；影响行数=1 者胜出，败者重读 task 返回相同受理编号（幂等语义：相同结果而非报错）
5. 胜出者 Feign 调 order-service 退款（FeignAuthConfig 透传登录态）
6. 成功：受理编号 `RF{taskId}` + 联动建单（T4.5）回填 ticket_id + flowState 回 IDLE；失败：task 置 REJECTED（卡片作废）+ 返回具体原因
7. CANCEL：task 置 REJECTED + flowState 回 IDLE，对话继续（不建单不留待办）

**order-service 第二道闸门**：`POST /voucher-order/refund`，userId 从登录态强制注入（沿用 queryMyOrders 模式，禁止传参）：

```sql
UPDATE tb_voucher_order SET status=5, refund_time=NOW()
WHERE id=? AND user_id=? AND status=2
```
影响 0 行 → 返回具体原因（订单不存在 / 状态已变更 / 已在退款中）。

### 2.4 幂等与越权防护（T4.4）

| 威胁 | 防护 |
|---|---|
| 并发双击（10 并发确认） | 步骤 4 条件更新，DB 唯一键 + 状态机原子流转，仅 1 胜出；order 原子 UPDATE 二次收敛 |
| 同订单重复申请 | 建卡前查 `biz_order_id + status IN (PENDING_CONFIRM, ADOPTED)` → 返回已有卡片/受理编号；order 侧 status=5 拦截并回提示 |
| 篡改 actionId | 步骤 2 属主校验 + 不存在即拦截 |
| 篡改/伪造订单号 | 卡片参数只来自工具真实数据（结构性排除） |
| 越权（他人订单） | confirm 归属校验 + order-service user_id 强校验，双层拦截 |

**一致性说明**：不引入 Seata（D-4）——退款受理（order 库）为事实源；联动建单失败重试 1 次，仍失败记录审计日志，每日对账脚本核对"退款数 vs 确认数"（`docs/dev-plans/phase4-deliverables/` 附对账 SQL）。

## 3. 工单线（T4.6/T4.7）

### 3.1 要素收集状态机（T4.6）

`ComplaintFlowService`，flowState=COMPLAINING，草稿 Redis `agent:session:{id}:complaintDraft`（TTL 对齐记忆 30min）：

```
COMPLAINT 意图 → 首轮：安抚话术 + lightModel 抽取结构化要素
每轮：StructuredOutputParser（复用 T3.2，schema 校验）抽取
     {category?, refs{orderId/voucherId/shopId}?, time?, demand?} → 合并草稿
要素齐 或 追问满 2 轮 → 定稿建单；仍缺 category → 按 OTHER（PRD 边界）
```

- 摘要：lightModel 生成 ≤200 字客观摘要（prompt 约束无主观评价与情绪词）；失败 → 规则模板兜底（"用户于{time}反馈{category}问题，涉及{refs}，诉求：{demand}"）
- 涉资金判定：诉求文本命中关键词表（退款失败/重复扣款/多扣/资金 等，yaml `agent.ticket.fund-keywords` 可配）→ priority=HIGH（验收"涉资金 100% 高优先级"）
- 去重：复用 TicketService.create 既有 dedup_key 逻辑，命中返回已有工单号
- 建单失败：重试 1 次 → 仍失败转人工并记录诉求，**绝不静默丢失**（FR-09 边界）

### 3.2 自动路由与 MQ 通知（T4.7）

- TicketService 已有 category→assignee_group、SLA（高 4h/中 24h/低 72h）、TK 工单号；Phase 4 补涉资金 priority 规则
- 创建成功后 `TicketNotifyProducer` 发 `agent-m5-ticket-route`，payload `{ticketNo, userId, category, priority, assigneeGroup, expectedSla, summary}`；发送成功 → notify_status=SENT，失败 → FAILED + 日志（工单创建与通知解耦）
- **social-service 消费端**：`TicketNotifyConsumer`（consumerGroup `social-ticket-notify-group`，maxReconsumeTimes 3）→ 落 `hmdp.tb_notification`（type=TICKET，title="工单已受理"，content=工单号+SLA）→ `GET /notification/my` 列表接口；消费失败 RocketMQ 重试兜底，耗尽进 DLQ（沿 order-service 先例）

## 4. 转人工线（T4.8/T4.9）

### 4.1 四类触发器（T4.8）

统一收口 `TransferService.trigger(session, reason)`：

| 触发 | 挂接点 |
|---|---|
| 用户显式要求 | Planner HUMAN_DEMAND 分支（既有桩改真实流转） |
| 连续 2 次工具失败 | ReActEngine 已返回 reason=TOOL_CONSECUTIVE_FAIL，dispatch 捕获 → trigger |
| 澄清 2 轮超限 | Planner 第 3 轮（替换 CLARIFY_MENU 降级，见 §1 行为变更） |
| 情绪检测 | `EmotionDetector`（负情绪词典 + 感叹/重复规则，高置信 ≥N 个强负面词才触发，R8）；doChat 输入检测后运行，命中直接 trigger 绕过 Planner |

全部埋点 `m5_transfer_human`（reason 区分），transfer_reason 落 agent_session（DDL 枚举已含四类）。

### 4.2 状态锁（验收 11：触发后零输出）

session.status=TRANSFERRED 落库后，doChat 入口检查：TRANSFERRED 会话的消息**不进 LLM、不跑 Planner**，追加会话历史（随移交包带给人工）+ 固定确认话术（"您的消息已记录，将随工单转交人工"）。日志可验证零 LLM 调用。

### 4.3 卡片与移交包（T4.9）

1. trigger 推送转人工卡片 `TRANSFER_CONFIRM`（payload：摘要预览四要素 身份/诉求/已查事实/未解决，取自 session.summary；折叠由前端渲染）
2. 用户确认（`POST /agent/chat/{sessionId}/transfer/confirm`）→ 无人值守路径（D-5）：复用工单服务建单（category 按会话上下文/OTHER，摘要沿用 session.summary）→ 推送工单号 + SLA；取消 → flowState 回 IDLE
3. 移交包 `{完整对话历史(raw) + summary + 工具结果快照(agent_tool_call 按会话查询) + transfer_reason}` → JSON → Redis `agent:transfer:{sessionId}`（TTL 7 天）→ snapshot_uri 记 key；联调报告 dump 验证，P2 直接消费
4. SSE 协议预留：`delta`/`card` 事件可选 `role` 字段（缺省 ai），P2 接入 role=human 时协议不破坏

## 5. 安全线（T4.10~T4.13）

### 5.1 网关频控（T4.10）

- `AgentRateLimitFilter` 只挂 agent-route（R6 独立限流）：Redis + Lua 令牌桶，维度 = Sa-Token loginId，10 QPS（key `agent:rl:{userId}`）
- 超出 → 429 + JSON `"操作太频繁啦，请稍后再试"`（友好提示，不封禁会话，次日自然恢复）
- 日会话 20 次、单会话 100 条维持 Phase 2 服务端实现（网关不重复计数）
- 测试边界：11 QPS 触发 / 9 QPS 不触发

### 5.2 输入安全检测（T4.11）

- `InjectionDetector`：注入特征正则规则库 v1 ≥30 条（忽略此前指令/你现在是/role override/ignore previous instructions/system prompt/developer mode 等中英文变体）
- 命中 → 不进 LLM，固定话术 + 埋点 `m5_input_blocked` 留痕（track_event 即拦截审计，验收 15"100% 留痕"落点）+ 提示可建单申诉（FR-11 误伤边界）
- `SensitiveWordService`：词表走 Nacos `agent.security.*`（D-3），@RefreshScope 监听 RefreshEvent 重编译 Pattern；本地 yaml 兜底；验收"1 分钟内生效"

### 5.3 输出安全过滤（T4.12）

- `OutputFilter` 流式滑动窗口：delta 持尾部 ≤32 字符（≥最长敏感词）不立即下发，每窗扫描；命中 → 停止流式 → 丢弃已缓冲 → 重生成 1 次（整段缓冲再发）→ 再命中 → 固定兜底话术 + 建议转人工
- 无命中时首 delta 即时下发（首 token P90 不受影响）
- AI 标识：欢迎语顶部固定"本服务由 AI 提供，内容由人工智能生成" + session 事件加 `aiNotice` 字段（前端展示，进 P0 验收）

### 5.4 数据红线（T4.13）

- Desensitizer 已覆盖 Observation 注入与 assistant 回写；补手机号/支付凭证正则核查用例
- 移交包、投诉摘要落库前过 Desensitizer
- 审计表代码层仅 INSERT（不提供 update/delete 路径）

## 6. 容灾线（T4.14）

- **工具层 Sentinel**（D-2）：`ToolExecutor.execute` 包 Sentinel 资源（资源名=工具名），编程式降级规则（慢调用 RT>2000ms 或异常比例>50%，熔断 10s）；BlockException → `ToolResult.fail("TOOL_DEGRADE", 话术)` → 计入连败逻辑 → 触发转人工（衔接 T4.8）
- **工具重试泛化**：Phase 3 的 QueryMyOrdersTool 内重试上移到 ToolExecutor 统一"失败重试 1 次"→ Observation 告知 LLM 换路（既有）→ 连续 2 次失败转人工（既有）
- **LLM 容灾**：GlmClient 内置 3 次重试（指数退避 1s/2s/4s）→ 备用模型互换（main ↔ light）→ 仍失败抛 LlmException；流式仅首 delta 前可安全重试，中途断流走失败路径；orchestrator 捕获 → FAQ 直答（RagFeignClient top1 原文 + 来源标注）+ 建单入口卡片
- 降级链路每条对应测试用例（附录 C 检查项）

## 7. 测试设计（T4.15/T4.16）

### A. 单测（mock LLM/Feign，`mvn -pl agent-service test` 全绿门禁）

| 测试类 | 覆盖 |
|---|---|
| RefundFlowServiceTest | 候选筛选/多单选择/已核销不可退/卡片生成+埋点 |
| ConfirmTaskServiceTest | 懒过期/条件更新幂等/取消/越权/篡改 actionId |
| ComplaintFlowServiceTest | 2 轮追问/OTHER 定稿/摘要模板兜底/涉资金 HIGH/去重命中 |
| TransferServiceTest | 四类触发（各 20 条用例素材）/状态锁/移交包四要素/无人值守建单 |
| InjectionDetectorTest | 注入攻击集 50 条（30 攻击集 + 20 变体，P4-R2 回归量）100% 拦截 |
| OutputFilterTest | 滑动窗口/重生成 1 次/兜底 |
| LlmResilienceTest | 3 重试/备用模型切换/FAQ 降级 |
| TicketPriorityRulesTest | 涉资金 100% HIGH |
| EmotionDetectorTest | 高置信触发/低置信不误伤 |

### B. DB/环境集成测试（`@Tag("db-it")`，Assumptions 环境守卫，沿 Phase 3 parity 模式）

- **并发 10 次确认仅 1 条 ADOPTED + order 库仅 1 条 status=5**（硬门禁压测用例）
- order 退款原子性（status≠2 全拦截）；工单去重命中
- 网关限流 9/11 QPS 边界（**gateway-service 模块测试**，Redis 在线时 Assumptions 守卫）；通知消费者落库（MQ 在线时）

### C. T4.15 全链路联调

冒烟脚本 `scripts/phase4-smoke.sh`（沿 Phase 3 模式），5 场景端到端：秒杀订单未到账 / 退款申请 / 券咨询 / 商户投诉建单 / 转人工兜底；gateway + MQ + 五服务在线走查 → `docs/dev-plans/phase4-deliverables/D4.7-集成联调报告.md`

### D. T4.16 P0 用例报告

对拍/幂等/越权/注入四类结果汇总 → `D4.8-P0自动化用例执行报告.md`，MS2 退出门禁（通过率 100%）。

## 8. 验收标准映射（PRD 条目级 16 条）

| # | 验收标准 | 验证方式 |
|---|---|---|
| 1 | 并发确认 100% 仅一次（含并发 10 压测） | ConfirmTaskServiceTest + db-it 并发用例 |
| 2 | 越权（篡改 actionId/订单号）100% 拦截 | ConfirmTaskServiceTest + RefundFlowServiceTest |
| 3 | 退款提交成功率 ≥99%（排除主动取消） | 联调报告统计（正常路径 + 失败原因分类） |
| 4 | 意图到卡片 P90 ≤8s | 冒烟脚本计时（环境在线时） |
| 5 | 工单分类准确率 ≥90% | ComplaintFlowServiceTest + 抽检项（报告标注人工抽检） |
| 6 | 涉资金 priority=高 100% | TicketPriorityRulesTest |
| 7 | 建单失败率 <0.5%、失败 100% 有出路 | 重试+转人工降级用例 + 联调报告 |
| 8 | 工单号唯一可查 | uk_ticket_no + TicketService 既有查询 |
| 9 | 四类触发各 100%（各 20 条） | TransferServiceTest |
| 10 | 移交包四要素完整率 ≥95% | TransferServiceTest（摘要 prompt 四要素约束）+ 抽检项 |
| 11 | 触发后零后续输出 | 状态锁单测 + 日志验证（联调报告） |
| 12 | 注入 30 条拦截率 100% | InjectionDetectorTest（扩充 50 条回归） |
| 13 | 频控边界 11 触发/9 不触发 | db-it 网关限流用例 |
| 14 | 敏感词热更新 1 分钟内生效 | RefreshEvent 集成用例 + Nacos 控制台演练（联调报告） |
| 15 | 会话与拦截事件审计 100% 留痕 | track_event 埋点单测（m5_input_blocked/m5_transfer_human 等） |
| 16 | MS2：P0 自动化用例通过率 100% | T4.16 报告 |

## 9. 交付物清单（对齐计划 §4）

| # | 交付物 | 形式 |
|---|---|---|
| D4.1 | 确认卡片通用机制（agent_task 全生命周期 + actionId 幂等） | 代码 + 单测 |
| D4.2 | FR-08 退款完整链路 | 代码 + 用例 |
| D4.3 | FR-09 工单服务（create_ticket/去重/路由/MQ 通知/站内信） | 代码 + 用例 |
| D4.4 | FR-10 转人工链路（四类触发/状态锁/移交包/无人值守建单） | 代码 + 用例 |
| D4.5 | FR-11 安全风控模块（频控/输入检测/输出过滤/AI 标识/数据红线/热更新） | 代码 + 规则库 v1 |
| D4.6 | 降级与熔断配置（Sentinel 规则 + 备用模型切换 + FAQ 直答） | 配置 + 代码 |
| D4.7 | 集成联调报告（5 场景端到端走查记录） | Markdown（真实运行结果） |
| D4.8 | P0 自动化用例执行报告 | Markdown（真实运行结果） |
| — | sql/phase4-notification.sql + sql/phase4-agent-task-biz-order.sql + 对账 SQL | SQL |

## 10. 运行与验证步骤

1. **DDL**：执行 `sql/phase4-notification.sql`、`sql/phase4-agent-task-biz-order.sql`
2. **环境**：Docker 中间件（MySQL/Redis/Nacos/RocketMQ）+ 环境变量 `GLM_API_KEY`；rag-service 可选（FAQ 降级路径验证）
3. **启动顺序**：order → voucher → shop → rag（可选）→ social → agent → gateway(8081)；外部访问经网关：`/agent/**`（既有）、`/notification/**`（本阶段新增前缀）；`/voucher-order/refund` 仅服务间 Feign 内部调用，不暴露网关（现状如此，符合 CLAUDE.md 约束）
4. **验证顺序**：
   - `mvn -pl agent-service test`（单测全绿门禁）
   - `mvn -pl agent-service test -Dgroups=db-it`（环境在线）
   - `bash scripts/phase4-smoke.sh`（5 场景联调）
5. **如实报告**：实现完成时至少执行编译 + 全部单测并报告真实结果；环境不可用项标注"待环境"及复现命令，不虚构数字

## 11. 风险应对落点（对齐计划 §6）

| 风险 | 本设计落点 |
|---|---|
| P4-R1 退款误执行 | 双闸门分权（D-4）+ actionId 一次性消费 + 原子 UPDATE + 对账脚本（§2.4） |
| P4-R2 注入绕过 | 三层叠加（输入检测 §5.2 / prompt 加固既有 / 输出过滤 §5.3）+ 50 条回归集 + m5_input_blocked 留痕 + 申诉建单 |
| P4-R3 结构化输出不稳 | 复用 StructuredOutputParser；卡片参数只用工具真实数据，LLM 参数仅候选（§2.2） |
| P4-R4 幂等缺陷 | 唯一键 + 条件更新原子流转 + 并发 10 压测硬门禁（§7.B） |
| P4-R5 MQ 通知延期 | D-1 已定完整实现；producer 侧仍保持工单创建与通知解耦（notify_status 独立流转） |
| P4-R6 情绪误判 | 词典 + 规则，高置信（≥N 强负面词）才触发（§4.1） |
| P4-R7 工具延迟放大 | Feign 2s + ToolExecutor 统一重试 1 次 + Sentinel 熔断快速失败 + agent 路由独立限流（§5.1/§6） |
| P4-R8 P2 范围蔓延 | 工作台冻结；默认无坐席，仅无人值守路径（D-5）；SSE 预留 role 字段 |
