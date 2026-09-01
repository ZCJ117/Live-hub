# MS1 退出验证 — curl SSE 演示脚本（T2.16 / D2.7 配套）

> 全程走网关 8081 统一入口（项目关键约束：禁止绕过网关访问业务服务）。
> bash / Git Bash 环境执行；`curl -N` 关闭缓冲以观察 SSE 逐事件输出。

## 0. 前置条件

```bash
# 1. 启动中间件（Docker Desktop）：MySQL:3306 / Redis:6379 / Nacos:8848
# 2. 依序启动服务：user-service → order-service → agent-service（网关可选，脚本走 8081）
# 3. LLM 密钥：agent-service 启动前 export GLM_API_KEY=<你的智谱密钥>
# 4. 已执行 sql/agent_service-ddl.sql 建库建表（agent_service 库 5 张表）

GW=http://127.0.0.1:8081
```

## 1. 登录取 token

```bash
# 验证码登录（先 POST /user/code 获取）或密码登录（phone+password）
curl -s -X POST "$GW/user/login" -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"123456"}'
# 响应 data 中取 token，设置：
TOKEN=<登录返回的token>
```

## 2. 建连 + 欢迎语（FR-01：2s 内出现欢迎语首字）

```bash
curl -N -s -X POST "$GW/agent/chat" \
  -H "Authorization: $TOKEN" -H "Content-Type: application/json" \
  -d '{}'
```

**预期事件序列：**

```
event:session  data:{"sessionId":"<雪花ID>","status":"ACTIVE","reused":false}
event:delta    data:{"text":"您好，我是 LiveHub 智能客服～"}   （欢迎语流式）
event:done     data:{"roundNo":0}
```

## 3. 带工具调用的问答（MS1 退出标准 1：SSE 全链路）

```bash
SID=<步骤2返回的sessionId>

curl -N -s -X POST "$GW/agent/chat" \
  -H "Authorization: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"sessionId\":$SID,\"message\":\"查询我最近7天的订单\"}"
```

**预期事件序列（FR-04 状态条）：**

```
event:delta        （若 LLM 先答复）
event:tool_call    data:{"toolName":"query_my_orders","friendlyText":"正在为您查询订单…",...}
event:tool_result  data:{"success":true,"summary":"已找到 N 条订单记录：…（已支付）等","cardPayload":{"ORDER_LIST":{...}}}
event:delta        （基于 [DATA] 的订单总结，含券名/状态/时间）
event:done         data:{"finishReason":null 或 "OK"}
```

## 4. 断线重连上下文不丢（FR-01 验收 3）

```bash
# 重新执行步骤 3 的 curl（携同一 sessionId）——服务端复用会话、历史从 Redis 恢复
# 预期 session 事件 reused=true，"刚才查到哪一单" 能承接上文
```

## 5. 输入边界（FR-02 基础验收）

```bash
# 纯表情 → 引导话术，不进 LLM（无 tool_call 事件）
curl -N -s -X POST "$GW/agent/chat" -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" -d "{\"sessionId\":$SID,\"message\":\"😀😀\"}"

# 超长消息（>500 字）→ 截断提示
python -c "print('{\"sessionId\":$SID,\"message\":\"'+('字'*600)+'\"}')" > /tmp/long.json
curl -N -s -X POST "$GW/agent/chat" -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" -d @/tmp/long.json
```

## 6. 结束会话 + 审计留痕核查（MS1 退出标准 2）

```bash
curl -s -X POST "$GW/agent/chat/close" -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" -d "{\"sessionId\":$SID}"
```

```sql
-- agent_tool_call 完整留痕：参数/结果/耗时/trace_id 均可查（验收：非空且 trace_id 贯通）
SELECT tool_name, args_json, result_summary, success, latency_ms, trace_id, error_code, create_time
FROM agent_service.agent_tool_call ORDER BY create_time DESC LIMIT 5;

-- Redis key 已随会话关闭清理（FR-02 验收 3）
-- redis-cli -a 520117 keys 'agent:session:<SID>*'   → 预期 (empty)

-- 基础埋点 4 事件（T2.15）
SELECT event_name, COUNT(*) FROM agent_service.track_event
WHERE create_time > CURDATE() GROUP BY event_name;
-- 预期：m5_session_start / m5_msg_send / m5_first_token / m5_tool_call 均有记录
```

## 7. 对拍冒烟（FR-05 验收 12，20 条）

对同一 userId、同一过滤条件，比对步骤 3 的 `cardPayload.orders` 与下方 SQL 直查结果：

```sql
SELECT vo.id, v.title, vo.status, vo.create_time
FROM hmdp.voucher_order vo LEFT JOIN hmdp.tb_voucher v ON vo.voucher_id = v.id
WHERE vo.user_id = <登录用户id> ORDER BY vo.create_time DESC LIMIT 20;
```
