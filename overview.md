# Phase 1（需求分析与架构设计）完成总览

## 完成内容

依据《Phase1-需求分析与架构设计-开发计划.md》，完成全部 9 项交付物（D1.1~D1.9）+ 1 份 DDL 脚本，输出于 `docs/dev-plans/phase1-deliverables/`：

| 交付物 | 文件 | 要点 |
|---|---|---|
| D1.1 | PRD评审纪要与范围冻结清单 | P0 范围冻结（FR-01~11）、10 条技术决策（KD-1~10）、5 项遗留决议（L-1~L-5）、附录 C 检查清单 7 项签核状态 |
| D1.2 | agent-service技术方案设计文档 | 架构分层（6 层）、SSE 异步线程模型与事件协议、LLM 接入抽象（双模型）、Planner/ReAct（≤8 步、DATA 隔离、防幻觉校验）、ToolRegistry、记忆机制、4 个技术 Spike（S1~S4） |
| D1.3 | 数据模型设计文档 + agent_service_ddl.sql | 四张表字段级设计，PRD 三个补充字段（rating/transfer_reason/dedup_key）全部落地；幂等用 action_id 唯一约束 + 原子 UPDATE；审计表仅追加；90 天归档策略 |
| D1.4 | 外部依赖接口变更确认单 | 6 项依赖确认：order-service 新查询接口契约（已核实现有代码确无该接口）、voucher 补字段、rag 内部检索 API、social 站内信 + 各自降级预案 |
| D1.5 | 安全与合规方案 | 三层纵深（网关频控 → 输入检测/prompt 加固 → 输出过滤）、注入正则规则库 v1 五类模式、规则热更新（1 分钟）、数据红线、合规一票否决项 |
| D1.6 | 降级与容灾设计 | PRD 4.3 四场景逐条落地（LLM 三重降级/工具熔断换路/rag 旁路/业务服务故障）、退款最终一致 + 每日对账（不用 Seata）、TC-DEG-01~09 用例映射、告警阈值表 |
| D1.7 | 测试策略与用例规划 | 6 个测试集建设计划、四类硬门禁用例（对拍/幂等/越权/注入）编号到阶段、按 FR 边界条件的功能用例、7 项压测计划 |
| D1.8 | 埋点字段字典 | PRD 6.2 全部 11 事件属性级定义、双通道上报（9 个服务端直写）、6.3 指标 SQL 口径、前端配合事项 F-1~F-4 |
| D1.9 | 灰度发布与回滚预案 | Nacos 开关 + userId 哈希分桶、10%→50%→100% 节奏与通过标准、5 分钟回滚操作、12 项发布 checklist、发布日运行时刻表 |

## 关键决策

- agent-service 端口 8088，独立 schema `agent_service`，网关新增 agent-route；不改既有秒杀/事务链路（手术式新增）。
- 退款一致性不用 Seata：task 原子状态机 + actionId 幂等 + 每日对账。
- rag-service 通过新增内部检索 API 暴露能力，客服主链路不依赖 RAG。
- 4 个 Phase 1 内 Spike（SSE 链路 / JSON mode 稳定性 / Feign 超时 / Redis 重连）先行验证最高技术风险。

## 遗留 / 后续注意

1. D1.4 需 order/voucher/rag/social 四个服务后端签核（L-1~L-4），签核前 Phase 2 按 Mock 开发。
2. D1.8 需前端双签（L-5）。
3. 快照存储介质、工单号段持久化两个开放问题分别在 Phase 5 T5.3 / Phase 4 T4.7 细化。
4. 本阶段未写业务代码（符合计划约束）；DDL 仅设计稿，Phase 2 T2.1 执行落库。
