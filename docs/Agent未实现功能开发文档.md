# 智能客服工单 Agent（M5）未实现/部分实现功能开发文档

| 项 | 内容 |
|---|---|
| 对照基准 | 《docs/PRD-智能客服工单Agent.md》v1.0（开发评审稿） |
| 前置文档 | 《docs/Agent功能实现状态清单.md》（本文档只覆盖其中"部分实现/未实现"项） |
| 范围红线 | **只实现 PRD 中明确要求的 Agent 功能，禁止扩展、新增或优化任何 PRD 之外的功能**；PRD 描述存在歧义时以原文为准，歧义项先经评审确认（见状态清单 §3）再编码 |
| 阅读对象 | 按"待实现功能点 → 依赖与顺序 → 验收标准"直接编码的开发者 |

---

## 1. 待实现功能点详述

### D1【P0】前端交互卡片层补全 —— 打通 FR-03/08/10/12 端到端闭环

**对应 PRD 条款**：FR-03（澄清菜单）、FR-08（退款确认卡片）、FR-10（转人工卡片）、FR-12（评价卡片）、5.1 IA"交互卡片层"。

**现状**：后端 4 类卡片均已通过 SSE `card` 事件推送（事件格式 `{cardType, payload}`），但 `frontend-livehub/nginx-1.18.0/html/hmdp/js/agent-chat.js` 的 `handleEvent()` 只处理 `session/delta/tool_call/tool_result/done/error`，**没有 `card` 分支，卡片全部被丢弃**；后端 3 个确认接口无前端调用方。

**改动内容**（仅前端 agent-chat.html / agent-chat.js / agent-chat.css，后端零改动）：

1. `handleEvent()` 增加 `case 'card'`，按 `d.cardType` 分发，将卡片对象 push 进 `messages`（`{ role: 'ai', card: {...} }`）。
2. **REFUND_CONFIRM**（payload：`actionId / order{orderId,voucherTitle,payValue,createTime,statusText} / reasons / expireMinutes / notice`）：
   - 渲染订单摘要 + 退款原因下拉（options=payload.reasons）+「确认提交」「取消」按钮 + 时效说明（payload.notice）；
   - 「确认提交」→ `POST /api/agent/chat/{sessionId}/confirm`，body `{actionId, decision:'CONFIRM', reason, reasonText}`（header 携带 authorization token，与现有 openStream 一致）；响应 `Result{data:{message,refundNo,ticketNo,expectedSla}}` → 将 message 追加为 AI 气泡；失败 → 追加 fail 提示并作废卡片（按钮置灰）；
   - 「取消」→ 同接口 `decision:'CANCEL'`；确认按钮点击后立即禁用（防快速双击，幂等由后端兜底）。
3. **TRANSFER_CONFIRM**（payload：`summaryPreview / elements`）：摘要预览可折叠（默认收起）+「确认转接」按钮 → `POST /api/agent/chat/{sessionId}/transfer/confirm`，响应 `data:{message,ticketNo,expectedSla}` 渲染为 AI 气泡。
4. **RATING**（payload：`sessionId / satisfactionLabels / tags`）：满意/不满意二选一；选不满意时展示多选标签（payload.tags）+ 提交按钮 → `POST /api/agent/chat/{sessionId}/rating`，body `{score, tags}`（score: 满意=5，不满意=1）。
5. **CLARIFY_MENU**（payload：`options`，取值为["查订单","查券","退款","投诉","转人工"]）：渲染按钮组，点击后将按钮文案作为用户消息走 `send(text)`（转人工选项即发送"转人工"，由既有 HUMAN_DEMAND 链路承接，无需新增接口）。
6. **ORDER_LIST 卡片点选修正**（FR-05 交互 4）：现有 `focusOrder(order)` 仅填充输入文案。改为携带 `context:{orderId}` 重新走 `openStream({message:'就是这个订单', sessionId, context:{orderId}})`，命中后端 `AgentChatController` 的 context 预注入 → `setFocusOrder`，保证聚焦不依赖 LLM 从文本解析订单号。

**验收标准（PRD 原文）**：
- FR-08 验收 4：从用户表达退款意图到卡片出现的 P90 时延 ≤ 8s；验收 1/2：确认幂等、越权拦截（后端已具备，本项补齐前端触发路径后由既有自动化用例回归）；
- FR-12 验收：评价提交成功率 100%（前端可提交即达标）；
- FR-03 验收 3：澄清菜单按钮可用、选择后走对应意图；
- FR-05 验收：带订单上下文进入后首句能正确关联该订单（context.orderId 直达焦点）。

---

### D2【P0】SSE 降级轮询通道 —— FR-01 边界 2 / 4.4 兼容性

**对应 PRD 条款**：FR-01 边界 2"SSE 建连失败/超时（3s）→ 前端降级为轮询模式提示'当前使用慢速通道'，并重试 1 次"；4.4"SSE 不支持时自动降级轮询（3s 间隔）"。

**现状**：前端 fetch 失败仅展示错误文案，无降级；后端无轮询接口。

**改动内容**：

1. **后端新增** `POST /agent/chat/poll`（AgentChatController）：同步 JSON 接口。请求体与 `/agent/chat` 相同（ChatRequest）。实现要点：
   - 复用 `sessionService.createOrReuse / getOwned` 与既有安全链路（登录校验、InputPreprocessor、消息频控、注入检测均在 ChatOrchestratorService 内，需将 doChat 的"单轮结果收集"抽为可同步等待的形式：本轮所有 delta 聚合 + card 事件列表 + finishReason，整体一次返回；不得在 Tomcat 工作线程上同步阻塞等待 LLM——沿用 sseExecutor 异步执行 + `CompletableFuture` 在请求线程 `get(3, SECONDS)`，超时返回 `{"partial":true}` 供前端下一轮继续轮询）；
   - 该接口仅为降级通道，不新增任何 PRD 之外能力。
2. **前端**（agent-chat.js）：
   - `openStream()` 中：建连 fetch 未在 3s 内收到 `session` 事件（用 `AbortController` + setTimeout 竞速）或 catch 网络错误 → 提示"当前使用慢速通道"，重试 SSE 1 次；
   - 重试仍失败 → 切换轮询模式：发送消息改调 `/agent/chat/poll`，3s 间隔拉取直至 `finishReason` 返回；降级提示条常驻。
3. 前端降级行为只影响传输通道，卡片/状态条渲染逻辑复用（poll 响应中的 card 列表逐个走 `handleEvent` 同一分发）。

**验收标准（PRD 原文）**：
- FR-01 验收 1 的降级路径：SSE 失败场景下用户仍能在轮询通道获得完整回答；
- 4.4：SSE 不支持时自动降级轮询（3s 间隔）生效；
- 4.1 性能约束：不在 Tomcat 工作线程同步阻塞等待 LLM。

---

### D3【P0】会话复用提示 —— FR-01 边界 3

**对应 PRD 条款**：FR-01 边界 3"同一用户并发会话数 > 1 → 新会话建立前提示'已有进行中的会话，是否继续'，选择继续则复用未过期会话"。

**现状**：后端自动复用并在 `session` 事件中返回 `reused=true`；前端未消费该字段，无提示。

**改动内容**（仅前端，后端零改动）：

1. `handleEvent('session')` 中，若 `d.reused === true` → 展示提示："已有进行中的会话，已为您继续上次的咨询" + 「重新开始」按钮；
2. 「重新开始」→ `POST /api/agent/chat/close`（body `{sessionId}`，后端已有）关闭旧会话 → 清空本地 sessionId/messages → 重新 `connect()` 建立新会话。

**验收标准（PRD 原文）**：并发会话场景用户可见提示并可选择"继续/重新开始"；选择继续则复用未过期会话（现状已满足）。

---

### D4【P0】澄清第 3 轮出路对齐 —— FR-03 边界（需评审确认 A3 后执行）

**对应 PRD 条款**：FR-03 边界"confidence < 0.6 → 输出澄清话术，最多连续澄清 2 轮，第 3 轮降级为菜单选择（按钮：查订单 / 查券 / 退款 / 投诉 / 转人工）"。

**现状**：`PlannerService.plan()` 中 `round > maxClarifyRounds` 时返回 `PlanDecision.transfer(...)`（第 3 轮直接转人工）；`FALLBACK_MENU` 菜单仅在意图 JSON 解析 3 次失败时使用。与 FR-03 原文不一致（与 FR-10 触发条件 3 又一致，两处 PRD 条款存在张力，见状态清单 A3）。

**改动内容**（按 FR-03 原文修正，单点改动）：

- `agent-service/.../planner/PlannerService.java`：`if (isMenu)` 分支由 `return PlanDecision.transfer(...)` 改为 `return PlanDecision.menu(...)`（复用既有 FALLBACK_MENU 决策，ChatOrchestratorService.dispatch 已有 FALLBACK_MENU 推卡逻辑，`options` 已含"转人工"按钮）；
- 菜单按钮文案→消息的回传链路由 D1 第 5 点承接；用户在菜单后继续模糊表达时，由既有 HUMAN_DEMAND / 情绪检测链路兜底转人工；
- m5_intent 埋点保留 `isFallbackMenu=true` 属性（已有）。

**验收标准（PRD 原文）**：FR-03 验收：连续 2 轮澄清后第 3 轮出现菜单按钮组（查订单/查券/退款/投诉/转人工），点击后进入对应意图路径；澄清轮次均值 ≤ 1.2 轮（埋点统计，不因本项恶化）。

---

### D5【P0】工具状态条合并显示 —— FR-04 边界

**对应 PRD 条款**：FR-04 边界"同一会话连续工具调用 ≥ 4 次 → 状态条合并显示'已进行 4 步查询'"。

**现状**：agent-chat.js 每次 `tool_call` 事件都新建独立状态条。

**改动内容**（仅前端）：`messages` 内维护本轮工具状态条计数；当同一轮内第 4 个及以后的 `tool_call` 到达时，不再新增条目，改为更新首个状态条的摘要文案为"已进行 N 步查询"（N 为累计次数），展开态仍可查看最近一次调用的参数与结果 JSON。

**验收标准（PRD 原文）**：连续 ≥4 次工具调用后前端仅见合并状态条，文案计数正确；单次调用的 `tool_call → tool_result` UI 反馈间隔 ≤ 100ms（验收 1，既有同步推送已满足）。

---

### D6【P0】转人工等待期消息进入移交包 —— FR-10 边界

**对应 PRD 条款**：FR-10 边界"用户在等待人工时继续发消息 → 消息进入队列随移交包带给人工"。

**现状**：`ChatOrchestratorService.doChat` 的 TRANSFERRED 分支把用户消息 append 进 Redis 历史并 ack"将随工单一并移交"；但移交包由 `TransferService.confirmTransfer → buildHandoverPackage` 在**确认时点一次性固化**，确认之后的新消息不会进入移交包（确认前的消息会包含）。

**改动内容**（后端，2 处小改）：

1. `TransferService` 新增 `appendLateMessage(AgentSession session, String userMessage)`：读取 Redis 移交包（key `agent:transfer:{sessionId}`）→ `history` 数组追加 `{role:'user', content:msg}` 与一条 assistant ack → 原 TTL 写回；包不存在（已过期/建单前）则跳过（不重建、不报错）。
2. `ChatOrchestratorService.doChat` TRANSFERRED 分支：在 `memoryService.append(sessionId, "user", message)` 之后调用 `transferService.appendLateMessage(session, message)`（尽力而为，异常仅 warn，不阻断 ack）。

**验收标准（PRD 原文）**：触发转人工并确认后继续发消息 → 移交包（Redis `agent:transfer:{sessionId}`）内可见该消息；验收 3"触发转人工后 Agent 零后续输出"不回归（ack 为固定话术模板，不进 LLM，既有日志断言保持）。

---

### D7【P1】FR-13 前端三页 —— 会话列表 / 会话回放 / 我的工单与工单详情

**对应 PRD 条款**：FR-13 交互流程、5.2 页面清单（/agent/history、/agent/history/{sessionId}、/agent/ticket/{ticketId}）。

**现状**：后端接口全部就绪（GET `/agent/chat/history`、`/agent/chat/history/{sessionId}`、`/agent/ticket/list`、`/agent/ticket/{ticketNo}`）；前端无任何页面。

**改动内容**（新增 3 个静态页 + js，风格复用 agent-chat.html 的 Vue + axios 模式）：

1. **会话列表页** `agent-history.html`：调 `/api/agent/chat/history` 渲染近 30 天列表（状态徽标 ACTIVE/CLOSED/TRANSFERRED/ARCHIVED、评价标记 rating、摘要摘要行、消息数、时间）；行点击进回放页；页面含"我的工单"入口 tab（调 `/api/agent/ticket/list` 渲染工单列表：工单号/类别/优先级/状态/预计时效）。
2. **会话回放页** `agent-history-detail.html`（路由 `?sessionId=`）：调 `/api/agent/chat/history/{sessionId}`，渲染 `snapshot`（文本气泡 + 卡片快照，工具过程不回放）；只读，无输入框；底部「基于此会话继续咨询」按钮 → 跳转 `agent-chat.html?resumeSessionId={sessionId}`（agent-chat.js mounted 读取该参数并在建连请求中携带 `resumeSessionId`，后端已支持摘要继承）。
3. **工单详情页** `agent-ticket-detail.html`（路由 `?ticketNo=`）：调 `/api/agent/ticket/{ticketNo}` 渲染状态时间线（OPEN→ROUTED→RESOLVED）+ 处理备注（handleResult）+ 摘要 + 关联会话入口（sessionId → 回放页）。
4. 入口：index.html 个人中心（"我的"）增加"客服记录"与"联系客服"链接（对应 5.1 IA）。

**验收标准（PRD 原文）**：
- 列表加载 P90 ≤ 1s（后端已建立 createTime 索引口径，前端分页一次性渲染 30 天数据）；
- 回放内容与实时会话一致率 100%（快照存储验证——后端 close/transfer 时点已固化，前端只读渲染）；
- 回放为静态快照不可继续对话，仅提供「基于此会话继续咨询」入口且自动携带摘要。

---

### D8【P1】前端埋点统一上报接口 —— 6.2 埋点方案

**对应 PRD 条款**：6.2"前端埋点走统一上报接口，服务端关键事件由 agent-service 直接写入（不依赖前端）"。

**现状**：服务端关键事件已全部直写 `track_event`；无前端上报端点，前端无上报逻辑（m5_first_token 当前为服务端口径，PRD 4.1 测量口径为前端"连接发起 → 首个 delta"）。

**改动内容**：

1. **后端新增** `POST /agent/track`（body：`{events:[{eventName, sessionId, props}]}`，批量 ≤20 条/次）：字段校验（eventName 白名单 = 6.2 事件表中的前端侧事件：m5_session_start/m5_msg_send/m5_first_token）→ 复用 `TrackEventService.track` 落库，`userId` 取登录态（不信任前端传参）；失败返回 success 不阻断业务。
2. **前端**：agent-chat.js 在建连成功（m5_session_start，含 entry）、发送消息（m5_msg_send，msgLen/roundNo）、首个 delta 渲染（m5_first_token，前端测量延迟 ms）三处调用上报（`navigator.sendBeacon` 或 fetch keepalive，失败静默）。

**验收标准（PRD 原文）**：埋点上报率 ≥ 99%；事件属性符合 6.2 字段字典（sessionId/userId/timestamp 服务端）。

---

### D9【P2】FR-14 客服工单工作台 —— MS4 发布后启动（PRD R9 范围冻结）

**对应 PRD 条款**：FR-14 全部、5.2 /agent-console、5.3 流程 D"有坐席 → 人工接管"。

**现状**：工单状态机 API 已就绪（PUT `/agent/ticket/{ticketNo}/status`，非法跳转 100% 拦截）；移交包已按约定存 Redis（TTL 7 天，`agent:transfer:{sessionId}`，含 history/summary/toolSnapshots/transferReason）；`agent.transfer.seat-online=false` 固定无人值守。

**改动内容**：

1. **后端**（最小集，均属 PRD 明确范围）：
   - 坐席侧工单查询接口：`GET /agent/console/tickets?group=&priority=&status=`（按 assignee_group 过滤、priority 排序；坐席身份鉴权沿用 Sa-Token 登录态，PRD 未定义坐席角色体系，本阶段演示级实现）；
   - 转人工会话队列接口：`GET /agent/console/transfers`（扫 `snapshot_uri` 前缀 `redis://agent:transfer:` 的 TRANSFERRED 会话列表）+ `GET /agent/console/transfers/{sessionId}`（读移交包）；
   - 人工接管消息：配置 `seat-online=true` 后，`confirmTransfer` 走既有占位分支改为"进入接管模式"；人工回复走同一 SSE 通道（`SseSessionManager.send(sessionId, "delta", {..., "role":"human"})`），坐席发消息接口 `POST /agent/console/transfers/{sessionId}/reply`；用户侧消息已实时进入 Redis 历史（D6），坐席轮询/订阅该历史展示。
2. **前端** `agent-console.html`（单页）：工单表格（组过滤 + 优先级排序）→ 工单详情（用户摘要/关联会话回放链接/处理表单：处理结果 + 备注）→ 关闭工单（调既有 transition 接口，targetStatus=RESOLVED）；转人工会话接管面板（历史展示 + 文本回复框）。

**验收标准（PRD 原文）**：
- 工单状态机流转正确（非法状态跳转 100% 拦截——后端已具备，回归验证）；
- 人工接管消息实时到达 ≤ 2s；
- 本阶段为演示级实现（无排班/质检），不得新增排班、质检等 PRD 之外能力。

---

## 2. 依赖关系与建议实现顺序

### 2.1 依赖关系

```
D1 前端卡片层 ────────────┐（独立，最大端到端断点，最先做）
D4 澄清菜单对齐 ──────────┤ 依赖 D1 第 5 点（菜单按钮回传链路）才可端到端验收
D6 移交包增量消息 ─────────┘（独立后端单点，先于 D9）
D2 SSE 降级轮询 ──┐（前端依赖后端 poll 接口；独立于 D1）
D3 复用提示 ──────┤（纯前端，独立）
D5 状态条合并 ────┘（纯前端，独立）
D7 FR-13 三页 ──── 后端就绪，无硬依赖；建议在 D1 稳定后做（共用会话页入口与鉴权模式）
D8 埋点上报 ────── 独立；agent-chat.js 改动建议与 D1/D2/D3/D5 合并一次提交
D9 工作台(P2) ──── 依赖 D6（等待期消息进包）与 D7（回放页链接）；PRD R9：MS4 发布后才允许启动
```

### 2.2 建议实现顺序

| 顺序 | 项 | 优先级 | 类型 | 工作量级 |
|---|---|---|---|---|
| 1 | D1 前端卡片层补全 | P0 | 前端 | 中 |
| 2 | D4 澄清第 3 轮菜单对齐（先过评审 A3） | P0 | 后端单点 | 小 |
| 3 | D6 转人工等待期消息进移交包 | P0 | 后端单点 | 小 |
| 4 | D3 会话复用提示 | P0 | 前端 | 小 |
| 5 | D5 工具状态条合并 | P0 | 前端 | 小 |
| 6 | D2 SSE 降级轮询（后端 poll 接口 + 前端降级） | P0 | 前后端 | 中 |
| 7 | D7 FR-13 前端三页 | P1 | 前端 | 中 |
| 8 | D8 前端埋点统一上报接口 | P1 | 前后端 | 小 |
| 9 | D9 客服工单工作台（MS4 后启动） | P2 | 前后端 | 大 |

> 完成顺序 1-6 后，全部 P0 功能达到 PRD 定义的端到端可验收状态（对应里程碑 MS2 退出标准）；完成 7-8 后达到 MS3 的 P1 范围；D9 按 PRD R9 冻结至 MS4 后。

---

## 3. 阻塞项（编码前须评审确认，见状态清单 §3）

| # | 阻塞点 | 影响范围 | 处理建议 |
|---|---|---|---|
| A1 | 券推荐距离无用户定位数据源（FR-06 原文含"距您 1.2km"） | FR-06 距离展示 | 需产品确认数据源；确认前维持"仅推荐店名"现状，禁止编造距离（FR-06 验收 2 的禁编造红线优先） |
| A2 | 取消原因无数据字段（FR-05"若有"） | FR-05 已取消单展示 | "若有"条件不成立即无需实现；如需展示须 order-service 增字段，超出 PRD 附录 B 改造点，需评审 |
| A3 | 澄清第 3 轮：FR-03"降级菜单" vs FR-10"转人工" | D4 是否执行 | 建议按 FR-03 功能定义处执行 D4；评审确认前不动 PlannerService |

## 4. 回归与验证约定

- 每项完成后运行 agent-service 既有测试（`mvn -pl agent-service test`），重点回归：幂等（ConfirmServiceTest）、越权、注入拦截、对拍（parity）、意图评测（eval）用例，保证改动不破坏 MS2 已达成的"对拍/幂等/越权/注入 100% 通过"退出标准；
- D1/D2/D3/D5/D7 的前端改动用 `MS2-curl演示脚本.md`（docs/dev-plans/phase3、phase4-deliverables）中的 SSE 事件序列作为联调契约，前端事件命名与后端 `SseEvent` 协议保持一致（session/delta/card/tool_call/tool_result/done/error）；
- 全部实现严格限于本文档所列 PRD 功能，任何过程中的"顺手优化"均不得合入。
