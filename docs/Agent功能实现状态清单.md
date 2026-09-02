# 智能客服工单 Agent（M5）功能实现状态清单

| 项 | 内容 |
|---|---|
| 对照基准 | 《docs/PRD-智能客服工单Agent.md》v1.0（开发评审稿） |
| 检查范围 | agent-service 全量源码（8088）、gateway-service、order-service/rag-service/social-service 依赖接口、frontend-livehub 会话页、sql/ DDL 与增量脚本、docs/dev-plans 各期交付物 |
| 检查日期 | 2026-09-02 |
| 状态口径 | **已实现**：PRD 要求的行为已在代码中落地；**部分实现**：主链路已落地但存在前端断点、边界缺口或与 PRD 原文的口径偏差；**未实现**：代码中无对应实现 |

---

## 1. 总体结论

- **后端（agent-service + gateway）P0 功能基本全量落地**：会话/SSE、多轮记忆与摘要、意图路由、ReAct 循环、5 个注册工具（query_my_orders / query_voucher / query_shop / search_shop_by_name / kb_search）、退款人机协同（双闸门+幂等）、工单创建与路由（去重/工单号/SLA/MQ 通知）、转人工（四类触发/状态锁/移交包/无人值守建单）、安全风控（网关限流/注入检测/输出过滤/敏感词热更新/脱敏/AI 标识）均有可运行实现，且配套单元/集成/对拍测试（agent-service/src/test 下 parity/eval/it 等 15 个包）。
- **当前最大的端到端断点在前端会话页**：`frontend-livehub/.../js/agent-chat.js`（203 行，Phase 3 基础版）**未处理 `card` 事件**，导致退款确认卡片、转人工确认卡片、评价卡片、澄清菜单卡片到达前端后被丢弃，后端已就绪的 `/agent/chat/{sessionId}/confirm`、`/transfer/confirm`、`/{sessionId}/rating` 接口无前端调用方。
- **FR-13 前端三页未开发**（后端接口已就绪）；**FR-14 工作台（P2）未实现**（仅后端状态机 API 就绪）。
- 存在 3 处与 PRD 原文的口径偏差/阻塞项，见 §3 末尾「歧义与阻塞项」。

## 2. 逐项功能清单

### FR-01 客服入口与会话建立（P0）— **部分实现**

| 子项 | 状态 | 说明（实现位置） |
|---|---|---|
| POST /agent/chat 建连 SSE + sessionId + 欢迎语流式 | 已实现 | AgentChatController.chat（message 为空走建连）；ChatOrchestratorService.handleConnect 流式输出 WELCOME_MESSAGE（含能力提示原文） |
| 入口 context={orderId\|voucherId\|shopId} 预注入 | 已实现 | ChatRequest.context → createOrReuse 存 contextJson；orderId 写入 focus（ChatMemoryService.setFocusOrder） |
| 未登录拦截 | 已实现 | 网关 SaTokenGatewayConfig + Controller 内 UserHolder 兜底 |
| 会话复用（并发会话>1） | **部分实现** | 后端自动复用未过期 ACTIVE 会话并返回 `reused` 标记（session 事件携带）；但 PRD 边界要求**先提示"已有进行中的会话，是否继续"再由用户选择**，当前为静默复用，前端亦未消费 reused 字段 |
| 空闲 30 分钟自动 CLOSED + 推结束语 | 已实现 | AgentSessionService.closeIdleSessions（@Scheduled 60s）+ close() 推 done(SESSION_CLOSED) |
| 断线重连 5 分钟内上下文不丢 | 已实现 | 请求携带 sessionId → getOwned 校验后复用，Redis 历史/摘要保留 |
| SSE 建连失败/超时 3s → 前端降级轮询提示 + 重试 1 次 | **未实现** | 前端 fetch 失败仅提示"服务连接失败"；无轮询降级通道，后端也无同步轮询接口 |

### FR-02 多轮对话与上下文管理（P0）— **已实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| 上下文组装：system prompt + 摘要 + 最近 10 轮 + 当前消息 | 已实现 | ChatOrchestratorService.doChat + ChatMemoryService（max-history-rounds: 10） |
| 超 10 轮 LLM 摘要压缩（512 字，存 agent_session.summary） | 已实现 | triggerSummaryIfNeeded（异步，lightModel，失败/异常降级 trimKeepLast(6)，不阻塞对话） |
| 消息 >500 字截断并提示 | 已实现 | InputPreprocessor（max-length: 500） |
| 空消息/纯表情引导，不进 LLM | 已实现 | InputPreprocessor Verdict.GUIDE_EMOJI |
| 发送频率>5条/10s 频控 | 已实现 | 网关令牌桶（10 QPS/loginId）+ 单会话 100 条（checkAndIncrMsg）；PRD 的 5条/10s 为频控示例口径，实际按 FR-11 规则实现 |
| 会话关闭 Redis key 过期清理 | 已实现 | close() → memoryService.evict；TTL 30min 滑动续期 |

### FR-03 意图识别与路由（P0）— **部分实现（1 处口径偏差）**

| 子项 | 状态 | 说明 |
|---|---|---|
| LLM 意图分类输出 JSON（7 类 intent + entities + confidence） | 已实现 | IntentClassifier + StructuredOutputParser（JSON mode + 重试） |
| 查询类 → ReAct 循环 | 已实现 | PlannerService.route → ReActEngine |
| REFUND → 退款流程；COMPLAINT → 要素收集建单；CHAT → 免工具直答（lightModel）；HUMAN_DEMAND → 直接转人工 | 已实现 | dispatch 各分支（ChatOrchestratorService.dispatch） |
| confidence<0.6 → 澄清，最多 2 轮 | 已实现 | clarify-threshold: 0.6，FlowStateService.incrClarify |
| 第 3 轮降级为菜单选择（按钮：查订单/查券/退款/投诉/转人工） | **口径偏差** | 实现为第 3 轮**直接转人工**（PlannerService：round > maxClarifyRounds → PlanDecision.transfer）；FALLBACK_MENU 菜单卡片仅在意图 JSON 解析 3 次失败时使用。PRD FR-03 边界原文为"第 3 轮降级为菜单选择"，FR-10 触发条件 3 又规定"澄清 2 轮仍无法确定意图"→ 转人工，两处存在张力，实现选择了 FR-10 口径 → 需评审确认（详见开发文档 D4） |
| 复合意图拆解为有序子任务，工具调用上限 8 步（共享预算） | 已实现 | PlanDecision.subtasks + react.max-steps: 8，预算逐子任务扣减 |
| 意图与上下文冲突 → 中断提示"您的退款申请尚未提交" | 已实现 | PlannerService REFUNDING 流程中非退款意图 → interruptNotice 先行推送 |
| 意图解析失败降级（R2） | 已实现 | 3 次失败 → FALLBACK_MENU 菜单卡片 |

### FR-04 工具调用过程可视化（P0）— **部分实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| SSE tool_call 事件（友好文案） | 已实现 | ToolExecutor.execute 推 tool_call（def.friendlyText） |
| tool_result 事件（结果摘要 + 可折叠参数/结果） | 已实现 | ToolExecutor 推 tool_result（summary/latencyMs/cardPayload）；前端渲染可折叠状态条（toolOpen） |
| 失败态文案 + 进入 FR-10 失败判断 | 已实现 | 失败摘要 + ReActEngine 连败计数 → TOOL_CONSECUTIVE_FAIL → 转人工 |
| 结果摘要敏感字段脱敏 | 已实现 | Desensitizer（手机号 138****1234/银行卡后4位）统一出口 |
| 展开态不进入历史回放 | 已实现 | 快照仅固化文本 + 卡片流水（SessionSnapshotService），工具过程不回放 |
| 连续工具调用 ≥4 次 → 状态条合并显示"已进行 4 步查询" | **未实现** | 前端每次 tool_call 新建状态条，无合并逻辑 |

### FR-05 订单查询（P0）— **已实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| query_my_orders（orderId?/status?/timeRange=days?）Feign → order-service | 已实现 | QueryMyOrdersTool + OrderFeignClient.queryMyOrders（order-service VoucherOrderController 已提供，PRD 附录 B 唯一改造点已落地） |
| 多条结果结构化卡片列表（券名/金额/状态/时间，倒序，每页 5 条 + 查看更多） | 已实现 | OrderCardDTO + cardPayload.ORDER_LIST；size 强制 ≤5，分页指引"下一页" |
| 结果为空 → 引导扩大时间范围 | 已实现 | 工具返回空记录摘要，由 LLM 依据 days 参数引导扩大范围 |
| 用户点选订单 → 后续聚焦 | 已实现 | ToolContext.focusOrderId（后端）；前端点选填充输入文案（未携带 context.orderId，依赖 LLM 从文本解析，弱化但不缺链路） |
| 越权：订单号不属于本人 → 拒绝且不泄露存在性 | 已实现 | userId 来自登录态 ToolContext 强制过滤，orderId 仅作本人上下文内聚焦 |
| >50 条强制分页 | 已实现 | 每页固定 5 条 |
| 超时/熔断 → 友好反馈 + 重试 1 次 + 建议建单/转人工 | 已实现 | ToolExecutor 瞬态白名单重试 1 次 + Sentinel 熔断（SentinelRuleConfig）+ Feign 2s 超时 |
| 已取消订单置灰 | 已实现 | OrderCardDTO.cancelled 标记（status=4）；取消原因数据源不存在，PRD"注明取消原因（若有）"的"若有"条件不成立（见阻塞项 A2） |

### FR-06 优惠券咨询（P0）— **部分实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| query_voucher(voucherId?)；未提供券 ID 先查订单圈定 | 已实现 | QueryVoucherTool（缺失 voucherId 时返回引导先调 query_my_orders，与工具 description 一致） |
| 「原因 + 解决路径」两段式回答，结合订单上下文 | 已实现 | ReActEngine Observation 回填 + system prompt 约束 |
| 券适用于其他店铺 → 附带推荐"该券还适用于 XX 店（距您 1.2km）" | **部分实现** | 适用店铺名推荐已实现（applicableShops 附查店名，≤5 个）；**距离信息无数据源**（平台无用户定位能力）→ 距离部分阻塞（见阻塞项 A1） |
| 券过期/已使用明确说明状态 | 已实现 | 工具返回完整券字段 + rulesComplete 标记 |
| 规则字段缺失 → 诚实回答"规则暂未录入"，禁止编造 | 已实现 | rulesComplete=false 注入数据 + system prompt 硬约束；有幻觉嫌疑埋点（m5_hallucination_suspect） |

### FR-07 商户信息咨询（P0）— **已实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| query_shop(shopId?) / search_shop_by_name | 已实现 | QueryShopTool / SearchShopByNameTool |
| 菜品/招牌等扩展信息 → kb_search（rag-service） | 已实现 | KbSearchTool（RagFeignClient 内部检索 API） |
| 店名模糊多结果 → 列候选不猜 | 已实现 | SearchShopByNameTool 返回候选列表 |
| 知识库无资料 → 仅结构化字段不补充 | 已实现 | system prompt 硬约束 + 工具数据 [DATA] 格式回填 |
| kb_search 检索信息带"据商户资料"来源标注 | 已实现 | FAQ 直答与 ReAct 答复均带标注（ChatOrchestratorService.faqDirectAnswer） |

### FR-08 退款申请（人机协同核心，P0）— **部分实现（后端全量，前端卡片缺失）**

| 子项 | 状态 | 说明 |
|---|---|---|
| REFUND → 定位候选订单（多单让用户选择） | 已实现 | RefundFlowService.handle（多单推 ORDER_LIST 卡片点选） |
| 退款确认卡片（订单摘要 + 原因下拉 + 确认/取消 + 时效说明） | **后端已实现，前端未渲染** | RefundFlowService.pushConfirmCard 推 REFUND_CONFIRM 卡片事件；agent-chat.js 无 card 事件处理 → 卡片被丢弃 |
| POST /agent/chat/{sessionId}/confirm 二次校验 | 已实现 | ConfirmService：归属（getOwned）→ actionId+userId 查任务 → 跨会话拦截 → 懒过期（10min）→ 幂等（tryAdopt 条件更新抢确认）→ order-service 原子退款最终裁决 |
| 校验通过 → 落库 REFUND_PENDING + 受理编号 + 联动复核工单 | 已实现 | 受理编号 RF{id}；createTicketWithRetry（重试 1 次，失败不回滚退款事实源，对账脚本补偿 sql/phase4-reconciliation.sql） |
| 校验失败 → 具体原因 + 卡片作废 | 已实现 | rejectAfterAdopt / reject |
| 同订单重复申请拦截 | 已实现 | findActiveByOrder 提示已有受理编号 |
| 已核销/已完成不可退说明 | 已实现 | 仅 statusCode=2（已支付）可生成卡片，其余话术说明不可退原因 |
| 取消 → 卡片收起、不建单不留待办；已受理不可取消 | 已实现 | decision=CANCEL 分支 |
| 卡片过期后点击 → 提示重新发起 | 已实现 | expireIfOverdue 懒过期 |
| 快速双击/并发确认幂等（仅一次） | 已实现 | tryAdopt 条件更新仅 1 胜出（ConfirmTaskServiceTest 覆盖） |
| 越权（篡改 actionId/订单号）拦截 | 已实现 | findByActionId(userId, actionId) + sessionId 匹配 |

### FR-09 工单创建与自动路由（P0）— **已实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| 要素结构化收集（类别/涉及对象/时间/诉求），最多追问 2 轮，仍不全按"其他" | 已实现 | ComplaintFlowService 状态机（Redis 草稿 TTL 30min，max-collect-rounds: 2，LLM JSON 抽取重试 2 次） |
| create_ticket 落库 agent_ticket，summary ≤200 字客观摘要（失败模板兜底） | 已实现 | ComplaintFlowService.buildSummary（lightModel，失败走规则模板） |
| category → assignee_group 路由（订单组/营销组/商户运营组/安全组） | 已实现 | TicketService.CATEGORY_GROUP |
| priority 规则：涉资金=高、普通=中、建议=低 | 已实现 | TicketPriorityRules（fund-keywords 词表）+ TicketService.defaultPriority |
| 工单号 TK+yyyyMMdd+6位日序号 + 预计时效（高4h/中24h/低72h） | 已实现 | TicketService.nextTicketNo（Redis INCR）+ expectedSla |
| 发送 agent-m5-ticket-route MQ 消息，消费侧通知对应组 | 已实现 | TicketNotifyProducer（MQ 离线降级 notify_status=PENDING 不影响建单）+ social-service TicketNotifyConsumer 写 tb_notification 站内信 |
| 同会话同问题重复建单去重（返回已有工单号） | 已实现 | dedup_key = MD5(category+sorted refs+sessionId) |
| 建单失败 → 重试 1 次降级转人工，不静默丢失 | 已实现 | ComplaintFlowService.createWithRetry + 失败 markTransferred(TICKET_FAIL) |

### FR-10 转人工与上下文移交（P0）— **部分实现（后端全量，前端确认卡片缺失）**

| 子项 | 状态 | 说明 |
|---|---|---|
| 触发条件 1：用户显式要求（HUMAN_DEMAND） | 已实现 | PlannerService → TransferService.trigger("HUMAN_DEMAND") |
| 触发条件 2：连续 2 次工具调用失败 | 已实现 | ReActEngine TOOL_CONSECUTIVE_FAIL → trigger("TOOL_FAIL") |
| 触发条件 3：澄清 2 轮仍无法确定意图 | 已实现 | PlannerService 澄清超限 → trigger("CLARIFY_EXCEED")（与 FR-03 口径偏差联动，见 D4） |
| 触发条件 4：强烈负面情绪（词典+规则，高置信） | 已实现 | EmotionDetector（≥3 个强负面词命中）→ trigger("NEGATIVE_EMOTION") |
| 触发后 Agent 停止生成（状态锁，零 LLM 输出） | 已实现 | doChat 入口 TRANSFERRED 分支 + DB CAS（markTransferred）防并发双触发 |
| 转人工卡片（摘要预览四要素，可折叠） | **后端已实现，前端未渲染** | TransferService.pushTransferCard 推 TRANSFER_CONFIRM 卡片；前端无处理 |
| 用户确认 → 状态 TRANSFERRED；无坐席 → 自动建单 + 告知时效 | 已实现 | TransferController /transfer/confirm → confirmTransfer（seatOnline=false 无人值守路径；Redis 一次性标记防双击重复建单） |
| 移交包：完整历史 + 摘要 + 工具结果快照 | 已实现 | buildHandoverPackage → Redis（TTL 7 天）+ snapshot_uri 回写 |
| 等待人工期间继续发消息 → 消息进队列随移交包带给人工 | **部分实现** | TRANSFERRED 分支把消息 append 进 Redis 历史并 ack；但移交包在确认时一次性固化，**确认之后的新消息不会进入移交包**（确认前消息会包含）→ 开发文档 D6 |
| 无人值守时段直接建单 | 已实现 | 同 confirmTransfer 建单路径（D-5 决策） |

### FR-11 安全风控（P0）— **已实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| 网关令牌桶：单用户 10 QPS（/agent/** 独立限流） | 已实现 | AgentRateLimitFilter + AgentTokenBucketLimiter（Redis+Lua，429 友好 JSON，不封禁） |
| 单用户日会话数上限 20 次 | 已实现 | AgentSessionService（Redis 计数，TTL 25h，次日自动恢复） |
| 单会话消息数上限 100 条 | 已实现 | checkAndIncrMsg |
| 输入安全：注入特征正则库（忽略指令/角色劫持/泄露/中英文变体 ≥30 条）命中不进 LLM | 已实现 | InjectionDetector（32 条规则）+ 固定话术 + 审计留痕（recordSecurityBlock + m5_input_blocked） |
| 输入敏感词检测 | 已实现 | SensitiveWordService（Nacos 托管热更新，EnvironmentChangeEvent 重建词表） |
| 输出安全：LLM 输出敏感词二次过滤，命中重生成 1 次 → 兜底话术 + 建议转人工 | 已实现 | OutputFilter 流式滑动窗口 + ReActEngine OUTPUT_BLOCKED_FALLBACK + 重生成逻辑 |
| AI 生成内容标识 | 已实现 | 前端顶部固定标识（agent-chat.html）+ session 事件 aiNotice + 欢迎语首行 |
| 数据红线：手机号/支付凭证脱敏进上下文 | 已实现 | Desensitizer 统一出口（记忆回写/审计/SSE 摘要） |
| 注入误伤申诉通道 | 已实现 | 拦截话术引导回复"投诉"建单人工核实 |
| 审计 100% 留痕 | 已实现 | agent_tool_call 表（参数/结果/耗时/trace_id/error_code）+ track_event 表 |

### FR-12 会话评价（P1）— **部分实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| 会话结束/转人工后推送评价卡片（满意/不满意 + 不满意多选标签） | **后端已实现，前端未渲染** | close() 与 confirmTransfer() 推 RATING 卡片（RatingService.ratingCard）；前端无处理 |
| POST /agent/chat/{sessionId}/rating 落库 agent_session.rating/rating_tags | 已实现 | RatingController + RatingService（仅 CLOSED/TRANSFERRED 可评、score 5/1、标签白名单过滤） |
| 每会话仅一次评价 | 已实现 | rating IS NULL 条件更新 |
| 忽略则 24h 后不再提醒 | 已实现 | 无提醒调度（一次性推送），由构造保证 |
| m5_rating_submit 埋点 | 已实现 | TrackEventService 服务端直写 |

### FR-13 会话历史与工单进度查询（P1）— **部分实现（后端全量，前端页面未开发）**

| 子项 | 状态 | 说明 |
|---|---|---|
| 客服记录列表（最近 30 天，含状态与评价标记） | **后端已实现，前端页面未开发** | GET /agent/chat/history（AgentSessionService.listRecent） |
| 会话详情回放（文本 + 卡片快照，工具过程不回放，静态不可续聊） | **后端已实现，前端页面未开发** | GET /agent/chat/history/{sessionId} + SessionSnapshotService（关闭/转人工时固化；Redis 卡片流水） |
| 「基于此会话继续咨询」携带摘要新建会话 | 已实现 | ChatRequest.resumeSessionId → createOrReuse 复制旧摘要 |
| 我的工单列表 + 工单进度（OPEN→ROUTED→RESOLVED 流转与处理备注） | **后端已实现，前端页面未开发** | GET /agent/ticket/list、GET /agent/ticket/{ticketNo}（归属校验） |
| 会话数据保留 90 天后归档 | 已实现 | archiveDaily 调度（CLOSED/TRANSFERRED 且 90 天未更新 → ARCHIVED） |
| 工单号唯一可查 | 已实现 | getByTicketNo |

### FR-14 客服工单工作台（P2）— **未实现（P2 允许整体砍掉，不阻塞发布）**

| 子项 | 状态 | 说明 |
|---|---|---|
| 工单列表（按组过滤、优先级排序）/ 详情 / 关闭工单（处理结果+备注） | **部分基础就绪** | 后端 PUT /agent/ticket/{ticketNo}/status 状态机（OPEN→ROUTED→RESOLVED，非法跳转 100% 拦截）已实现；无按组过滤/排序的坐席侧查询接口，无工作台前端 |
| 接收 TRANSFERRED 会话人工接管（role=human，同一 SSE 通道，实时 ≤2s） | 未实现 | seatOnline=false 固定走无人值守；接管路径仅占位返回（TransferService）；移交包已按 P2 消费约定存 Redis（TTL 7 天） |

### 埋点与指标（6.2，服务端部分）— **部分实现**

| 子项 | 状态 | 说明 |
|---|---|---|
| 服务端关键事件直写（m5_session_start/msg_send/first_token/tool_call/intent/refund_card_show/refund_confirm/ticket_create/transfer_human/session_end/rating_submit/llm_degrade/input_blocked/hallucination_suspect） | 已实现 | TrackEventService → track_event 表 |
| 前端埋点统一上报接口 | **未实现** | 无 POST 上报端点，前端亦无上报逻辑（PRD 6.2：前端埋点走统一上报接口）→ 开发文档 D8 |

---

## 3. 歧义与阻塞项（需评审确认，编码前必读）

| # | 事项 | PRD 原文依据 | 现状 | 说明 |
|---|---|---|---|---|
| A1 | 券推荐距离 | FR-06 交互 3："该券还适用于 XX 店（距您 1.2km）" | 推荐店名已实现，距离无数据源 | 平台无用户定位服务，PRD 未定义用户位置获取方式，距离无法计算。**阻塞**：需产品确认数据源；确认前不编造距离 |
| A2 | 已取消订单的取消原因 | FR-05 边界："卡片置灰并注明取消原因（若有）" | cancelled 置灰标记已实现，无取消原因字段 | order 侧订单模型无 cancel_reason 字段，PRD 附录 B 未将其列入 order-service 改造点 → "若有"条件不成立，维持现状即可；如需展示须新增 order-service 字段（超出 PRD 附录 B 范围，需评审） |
| A3 | 澄清第 3 轮的出路 | FR-03 边界："第 3 轮降级为菜单选择" vs FR-10 触发条件 3："澄清 2 轮仍无法确定意图 → 转人工" | 实现为第 3 轮转人工 | 两处条款并存存在口径差异。建议按 FR-03（功能定义处）：第 3 轮推菜单卡片，菜单内含"转人工"按钮——用户不选菜单时自然承接 FR-10。**需评审拍板后按开发文档 D4 修正** |
| A4 | 有坐席的人工接管 | FR-10 交互 3 / 5.3 流程 D | seatOnline=false 固定无人值守 | PRD 将人工接管归入 P2 工作台（5.3 流程 D 注明"有坐席（P2）"），与 FR-14 同批实现即可 |
