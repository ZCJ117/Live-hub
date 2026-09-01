# MS2 演示脚本（Phase 3 退出）

前置：Docker 中间件（MySQL/Redis/Nacos）+ 环境变量 `GLM_API_KEY`；
启动顺序：order → voucher → shop → rag（可选，不启动则场景 8 验证 kb_search 降级）→ agent-service → gateway。

```bash
BASE=http://127.0.0.1:8081

# 0. 登录（user-service 验证码登录，与对拍测试同流程；若登录实现不同以其源码为准调整）
curl -s "$BASE/user/code?phone=13800000001"
CODE=<从 Redis 读 user:code:13800000001>
TOKEN=$(curl -s -X POST "$BASE/user/login" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000001\",\"code\":\"$CODE\"}" | sed -E 's/.*"data":"([^"]+)".*/\1/')
AUTH="Authorization: $TOKEN"

# 场景 1 意图分流·订单（观察 tool_call/tool_result 状态条 + ORDER_LIST 卡片）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"我上周秒杀的券订单到哪了"}'

# 场景 2 澄清（模糊表达 → 澄清话术）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"那个东西呢"}'
# 场景 2b 连续第 3 次模糊 → CLARIFY_MENU 卡片（查订单/查券/退款/投诉/转人工）

# 场景 3 复合意图（两个子任务顺序执行，共享 8 步预算）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"查下我的单子，顺便把这个退了"}'

# 场景 4 上下文冲突（退款流程中问商户 → 先见"您的退款申请尚未提交"再正常回答）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"星巴克在哪"}'

# 场景 5 券咨询两段式（query_voucher + 「原因+解决路径」）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"满100减30的券为什么用不了"}'

# 场景 6 商户候选（多结果 → SHOP_CANDIDATES 卡片，不猜）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"星巴克现在营业吗"}'

# 场景 7 超范围引导（CHAT 直答 → 礼貌拉回）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"帮我写一首诗"}'

# 场景 8 kb_search 来源标注（rag-service 在线 + KB merchantId=shopId 时返回"据商户资料"；离线验证降级话术）
curl -N -X POST "$BASE/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"这家店的招牌菜是什么"}'
```

预期记录：每场景首 token 时延、工具状态条出现顺序、卡片类型、澄清轮次。
执行结果记入 `D3.9-意图评测报告.md` 附表。
