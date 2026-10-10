# SPEC-15 端到端验证手册

> 覆盖 SPEC-15 §5.2 中标注「必须实测」的 E4 / E6 / E7 / E8 / E9。
> 每条给出**操作 → 期望 → 反例（怎么知道它没生效）**。启动配方见
> `.e2e/` 既有脚本；网关鉴权在路由前短路，**不带 token 的请求一律假绿**，
> 所有 curl 必须带 `Authorization`。

## 执行状态（诚实口径）

**本手册全部 5 项（E4/E6/E7/E8/E9）在 2026-10-10 均标注「未执行」。** 原因统一如下：

> **环境阻塞**：执行时 Docker Desktop 引擎未运行（`docker ps` 报
> `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`），
> 容器 `hmdp-mysql` / `hmdp-redis` **不存在**。3306/6379 上虽有**本机原生** MySQL/Redis
> （同端口），但缺少 Nacos / RocketMQ 以及 6 个 Java 服务的完整运行时，
> E4 的阶梯压测与 E6/E8 的「停进程 / kill -9 / 重启」链路无法拉起。
> 因此本手册**不含任何实测数字**——凡未跑过的，一律写「未执行」，不写猜测值。

**环境拉起配方**（把「未执行」变「已执行」所需的最小条件）：

1. 启动 Docker Desktop，等 `docker ps` 能正常返回；
2. 拉起中间件：`docker compose up -d`（Nacos 8848 / MySQL 3306 / Redis 6379 / RocketMQ 9876-10911）。
   若坚持用本机原生 MySQL/Redis（同端口），则至少补起 Nacos 与 RocketMQ——
   E6/E8 依赖服务注册发现与 MQ；
3. 跑 `sql/` 下建表脚本（`tb_seckill_outbox` 已幂等建好，`CREATE TABLE IF NOT EXISTS`）；
4. 依次启动 gateway(8081) / voucher(8083) / order(8084) 及所依赖服务，确认
   `curl -s http://127.0.0.1:8081/actuator/health` 返回 `UP`；
5. 按 §2「通用准备」拿 token，再逐条执行。

> **给评阅者的换算**：本批交付的是「可复现的操作脚本 + 期望 + 反例 + 已知口径」，
> 不是「已通过的验收记录」。E4 的字节级操作见同目录 `SPEC-15-P1-1-秒杀压测手册.md`。

## 通用准备

```bash
BASE=http://127.0.0.1:8081
TOKEN=<登录后拿到的 token>
VID=<测试券 id>
```

**Docker 不可用时的替代写法**：本手册中所有 `docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD"`
可去掉前缀换成直接 `redis-cli -a "$REDIS_PASSWORD"`；
`docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD"` 换成直接
`mysql -uroot -p"$MYSQL_PASSWORD"`（本机原生实例同端口）。键名 / SQL / 参数不变。

## E4 · 阶梯压测 100/500/1000 并发

见 `SPEC-15-P1-1-秒杀压测手册.md` 全篇。
**期望**：输出 QPS/p95/p99/错误率表；超卖数 = 0；对账等式成立。

> **实测状态：未执行**。原因见上方「执行状态」。本项需要 6 个 Java 服务 + JMeter 全链路在跑，
> 依赖 Nacos/RocketMQ，`docker ps` 失败时无法拉起。手册（Task 12 交付）已把操作与判据写死，
> 环境恢复后按它跑并把结果回填到此处。

## E6 · 停 voucher-service 后发起秒杀

```bash
# 1) 记录基线
curl -s http://127.0.0.1:8084/actuator/prometheus | grep '^seckill_feign_call'
# 2) 停掉 voucher-service
kill "$(jps -l | grep voucher-service | awk '{print $1}')"
# 3) 连续发起 20 次秒杀（每次会走到消费者的 deductStock）
for i in $(seq 1 20); do
  curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" -o /dev/null
done
# 4) 观察
curl -s http://127.0.0.1:8084/actuator/prometheus | grep -E '^seckill_(feign_call|mq_consume_fail)'
tail -100 <order-service 日志> | grep -E 'Feign 失败率超阈值|库存服务调用失败'
```
**期望**：每次失败耗时 ≈ `readTimeout(2s)` 而不是 5s；`seckill.feign.call{result="fail"}` 增长；
出现「Feign 失败率超阈值」WARN（连续 ≥10 次失败且失败率 ≥50%）。
**反例**：若日志里每次耗时仍是 ~5000ms，说明 Task 3 的 Feign 超时没生效
（回去查 Nacos 是否覆盖了 `feign.client.config`）。
**恢复**：重新启动 voucher-service。

> **实测状态：未执行**。原因见上方「执行状态」——本项要求停掉一个正在运行、且已被 Nacos
> 注册发现的 `voucher-service`，而当前没有任何 Java 服务在跑（Docker 引擎未起，
> 中间件不完整，服务无法启动）。无实测耗时数字。

### 已知口径（务必先读，否则会误判）

- **`FeignFailureRateMonitor` 统计的是「Feign 调用是否抛异常」，不是全量业务失败率。**
  下游**正常返回**一个业务失败（`Result.fail`，例如「优惠券不存在」「库存不足」）
  **不计入**失败样本——它走的是成功返回路径。
  因此 `seckill.feign.call{result="fail"}` 与那条 WARN 的语义是
  **「依赖是否不可达 / 超时」**，用来把「下游变慢或宕机」从「下游正常但业务拒绝变多」里区分出来。
  **不要**把它读成「秒杀订单失败率」；业务失败率应看
  `seckill.fail` / `seckill.stock.insufficient` / `seckill.duplicate_order` 等业务计数器。
- 告警节流：进入告警态后**不再重复告警**，直到出现一次成功才重新武装（见
  `FeignFailureRateMonitor` 类注释）。所以「同一轮劣化只看到一条 WARN」是预期，不是漏报。

## E7 · 热点券 key 过期瞬间并发查询

```bash
# 1) 灌入少量券，让 key 处于"有缓存"状态
curl -s "$BASE/voucher/$VID" -H "Authorization: $TOKEN" -o /dev/null
# 2) 删掉 L2，制造"缓存刚过期"的瞬间
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL "cache:voucher:$VID"
# 3) 100 并发同时查
seq 1 100 | xargs -P 100 -I{} curl -s "$BASE/voucher/$VID" -H "Authorization: $TOKEN" -o /dev/null
# 4) 看 DB 回源次数（MySQL 侧）
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SHOW GLOBAL STATUS LIKE 'Com_select'"
# 5) 看 Hikari 峰值
curl -s http://127.0.0.1:8083/actuator/prometheus | grep '^hikaricp_connections_(active|pending)'
```
**期望**：`Com_select` 增量 ≈ 1（而不是 100）；`hikaricp_connections_active` 无明显尖峰。
**反例**：增量接近 100 → 互斥锁没生效（查 `MultiLevelCache` 的 `rebuildLock` 是否被装配）。
**注意**：本项测的是**券元信息**（`cache:voucher:`）。`seckill:stock` **不在缓存内**，
若看到它参与命中率统计，说明有人给强一致读接了缓存 → 立即跑 Task 7 的契约测试。

> **实测状态：未执行**。原因见上方「执行状态」——需要 `shop/voucher-service` 在跑并暴露
> `hikaricp_*` 指标、同时能读 MySQL `Com_select` 全局状态；当前服务与容器均不可用。
> 无实测 `Com_select` 增量数字。

## E8 · kill 秒杀生产端后重启（P2-1 形态 D1-b）

验证"落库成功但投递未完成"的窗口由补投器兜住。

```bash
# 1) 让 MQ 暂时不可达，制造"落库成功但投递失败并回滚"以外的场景：
#    更准确的做法是直接制造"落库成功、进程立刻崩溃"——用 kill -9 打断在
#    sendSeckillOrderMessage 与 markOutboxDelivered 之间。
#    实操：在 order-service 日志里盯住 "秒杀资格校验通过"，随后立即 kill -9
OID=$(curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" | grep -o '[0-9]\+')
kill -9 "$(jps -l | grep order-service | awk '{print $1}')"

# 2) 确认事件行停在待投递
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,status,retry_count FROM hmdp.tb_seckill_outbox WHERE id=$OID"
#   期望：status=0（若为 1，说明投递已完成，换一次重试）

# 3) 重启 order-service，等 ≥ 补投间隔(3s) + 退避(30s)
# 4) 观察补投
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,status,retry_count FROM hmdp.tb_seckill_outbox WHERE id=$OID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,user_id,voucher_id,status FROM hmdp.tb_voucher_order WHERE id=$OID"
curl -s http://127.0.0.1:8084/actuator/prometheus | grep '^seckill_outbox'
```
**期望**：事件行 `status` 变为 1、`tb_voucher_order` 出现该 orderId、
`seckill.outbox.redelivered` ≥ 1、`seckill.outbox.exhausted` = 0。
**反例**：行长期停在 `status=0` 且 `retry_count` 不涨 → 补投器没被注册
（跑 `SeckillSchedulingContractTest`）。
**写入文档的诚实口径**：丢失窗口 = 0（消息不会丢），但补投**延迟** ≈
扫描间隔(3s) + 行内退避(30s)，不是"零延迟"。与 P0-2 的 T+120s 相比是收敛，不是消除等待。

> **实测状态：未执行**。原因见上方「执行状态」——本项要求 `order-service` 与 RocketMQ
> 同时在跑（制造 `kill -9` 崩溃窗口、再重启验证补投），当前二者均不可用。
> **不过**，补投器本身的行为已被单测覆盖（全量回归绿）：
> `com.hmdp.order.service.impl.SeckillOutboxDelivererTest`（5 tests）、
> `com.hmdp.order.SeckillSchedulingContractTest`（3 tests）。
> 单测证明的是「逻辑正确」，**不等于** E8 的「真链路崩溃-恢复」已实测——两者不可互相替代。

### 已知口径：outbox 人工处置（务必先读）

- 补投次数耗尽的行会**永久停在 `status=0`**。原因是扫描条件里有
  `.lt(SeckillOutbox::getRetryCount, MAX_RETRY)`（`MAX_RETRY = 5`）——
  一旦 `retry_count` 达到 5，该行**不再被任何一轮自动补投扫到**。
  ERROR 日志 `[事件表补投耗尽] 该订单需人工核对` **只在跨过上限的那一次**打一条。
  所以「日志里没有新的 ERROR」**不代表**没有待处置的行。
- **人工核查命令**（发现长期 `status=0` 的秒杀单时用）：

  ```sql
  SELECT * FROM hmdp.tb_seckill_outbox WHERE status=0 AND retry_count>=5;
  ```

  非空即表示有订单投递彻底失败、需要人工判定是「补投 MQ 恢复后重发」还是
  「按业务规则作废并回补库存」。`seckill.outbox.exhausted` 计数器可作旁证。

## E9 · 黑名单用户被拦截且零误杀

> 本项**必须显式覆盖 user 维度**（不只是 IP 维度）：黑名单判定按 loginId，
> 依赖 Sa-Token 会话在 Redis 可用。`RiskBlacklistFilter` 的判定顺序是
> **先 IP、后 user**（见 `filter`：两个 `isBlacklisted` 依次执行），
> 而黑名单过滤器整体又**先于限流**（`order = -20`，早于限流的 -10）。

```bash
# 1) 基线：正常用户可下单
curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" | head -c 200

# 2) 加入黑名单（运维手工维护）——user 维度
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SADD risk:blacklist:user <loginId>

# 3) 同一用户再发起 → 期望 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN"
#   期望输出：403
curl -s http://127.0.0.1:8081/actuator/prometheus | grep 'gateway_risk_blacklist_blocked'

# 4) 误杀检查：另一个未在名单的用户必须仍能正常请求（200/业务错误码，但**不是** 403）
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $OTHER_TOKEN"

# 5) IP 维度
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SADD risk:blacklist:ip 127.0.0.1

# 6) 清理（必须）
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SREM risk:blacklist:user <loginId>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SREM risk:blacklist:ip 127.0.0.1
```
**期望**：黑名单用户 403 且指标 +1；未在黑名单的用户不受影响（零误杀）。
**反例**：请求带 token 通过网关返回 200 而 Redis 里确实 SADD 了 → 过滤器顺序或路径前缀配错
（`hmdp.risk.blacklist.path-prefixes` 必须覆盖 `/voucher-order/seckill/`）。
**注意**：黑名单判定用了 loginId，需保证 Sa-Token 会话在 Redis 可用；token 失效时
`resolveLoginId` 返回 null 会跳过 user 维度 —— 这是有意设计（未登录流量由 SaToken 拒绝）。

> **实测状态：未执行**。原因见上方「执行状态」——需要网关(8081)在跑、Sa-Token 会话可解析 loginId，
> 而当前无 Java 服务运行。无实测状态码记录。
> 过滤器逻辑已被单测覆盖：`com.hmdp.gateway.filter.RiskBlacklistFilterTest`（5 tests，全量回归绿）。

### user 维度完整操作步骤与期望（本项的主要判据）

1. **拿到 loginId**：用真 token 调一次需要登录的接口（如 `GET /user/me`），
   或从 `tb_user` 反查。loginId 就是 `tb_user.id`（Sa-Token 登录时以用户 id 为 loginId）。
   ```bash
   LOGIN_ID=<该 TOKEN 对应的 tb_user.id>
   ```
2. **加入黑名单**（仅 user 维度，**先不动 IP**，以证明拦截确由 user 维度触发）：
   ```bash
   docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SADD risk:blacklist:user "$LOGIN_ID"
   docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SISMEMBER risk:blacklist:user "$LOGIN_ID"
   #   期望：返回 1
   ```
3. **同 token 再发起秒杀 → 期望 403**：
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN"
   #   期望：403（不是 401、不是 429、不是 200）
   ```
   **日志期望**（网关侧）：
   ```
   WARN ... 风控黑名单拦截: path=/voucher-order/seckill/<VID>, dimension=user, identity=<LOGIN_ID>
   ```
   注意日志里的 `dimension=user`——这正是「user 维度生效」的直接证据；
   若打成 `dimension=ip`，说明是 IP 命中而非本步的 user 命中。
4. **指标期望**：`gateway.risk.blacklist.blocked{path="/voucher-order/seckill/",dimension="user"}`
   计数 +1。网关**无 prometheus 端点**，只能从日志/`/actuator/metrics/gateway.risk.blacklist.blocked` 看。
   响应体应为 `{"success":false,"errorMsg":"当前账号或网络环境存在风险，已被限制访问","code":403}`。
5. **零误杀**：用**另一个**登录用户（其 loginId **不在**名单）发起同一请求，
   期望**不是 403**（可能是 200 或业务错误码，如库存不足/重复下单）：
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $OTHER_TOKEN"
   ```
6. **还原（必须）**：
   ```bash
   docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SREM risk:blacklist:user "$LOGIN_ID"
   docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SISMEMBER risk:blacklist:user "$LOGIN_ID"
   #   期望：返回 0，确认已还原
   ```
   **不还原的后果**：残留条目会持续 403 污染后续任何走 `/voucher-order/seckill/` 的操作，
   且极易被误读成「秒杀链路故障」（见压测手册 §5 归因表 403 行）。

## 验收对照

| SPEC-15 §6 验收 | 类型 | 本手册对应 |
|---|---|---|
| 1 QPS/p95/p99/错误率基线，超卖数 = 0 | 必须实测 | E4 |
| 2 N 并发同 key → loader 1 次 | 单测 | `MultiLevelCacheTest#并发同key回源时loader只调用一次` |
| 3 券元信息命中率可观测，回源 DB QPS 下降 | 必须实测 | E7 |
| 4 getSeckillStock 无缓存注解 | 契约测试 | `SeckillStockNoCacheContractTest` |
| 5 voucher 下线时快速失败，无线性堆积 | 必须实测 | E6 |
| 6（P1-3）Cluster 无 CROSSSLOT | 不适用 | **本批跳过 P1-3**（SPEC-15 §3 D4 选 B） |
| 7 kill 消费端后重启，消息不丢 | 必须实测 | E8 |
| 8 黑名单拦截 + 零误杀 | 必须实测 | E9 |

## 附：本批交付时点的可信度分层

| 层级 | 内容 | 证据 |
|---|---|---|
| **已实测（真跑）** | 全量单元/契约回归（5 模块 221 tests 全绿） | `mvn -pl common,order-service,voucher-service,gateway-service,shop-service -am test`，2026-10-10 |
| **未实测（环境阻塞）** | E4 / E6 / E7 / E8 / E9 五条端到端链路 | 见上「执行状态」；Docker 引擎未运行 |
| **已写死的操作脚本与判据** | 本手册 + `SPEC-15-P1-1-秒杀压测手册.md` | 环境恢复后可直接照跑 |
