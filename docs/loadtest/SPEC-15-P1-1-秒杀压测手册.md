# SPEC-15 P1-1 · 秒杀接口压测手册

> 对应 SPEC-15 §2.1 / §5.2 E4 / §6 验收 1。**必须实测**，不接受仅单元测试通过。

## 0. 前置检查

```bash
# 凭据：本手册所有 redis/mysql 命令都读 $REDIS_PASSWORD / $MYSQL_PASSWORD，
# 值在仓库根目录的 .env 里。不 source 的话它们为空，redis-cli 会 NOAUTH、mysql 会语法错。
set -a; source .env; set +a

# 中间件
docker ps --format '{{.Names}}' | grep -E 'hmdp-(mysql|redis|nacos|rocketmq)'
# 6 个 Java 服务（网关 8081 / voucher 8083 / order 8084）
curl -s http://127.0.0.1:8081/actuator/health | head -c 200

# 黑名单必须为空（SPEC-15 P2-2）：网关在限流**之前**查 risk:blacklist:ip / risk:blacklist:user，
# 命中直接 403。若压测机 IP 或任一测试账号在黑名单里，整轮压测会被 403 挡掉，
# 而操作者极易把它误读成"秒杀链路故障"。
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SMEMBERS risk:blacklist:ip
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SMEMBERS risk:blacklist:user
# 两者都应返回空集合；非空则先 SREM 移除压测机 IP / 测试用户
```

**⚠️ Docker 不可用时的替代写法**：本手册的 redis/mysql 命令默认走 `docker exec hmdp-redis|hmdp-mysql`。
若 Docker Desktop 引擎未运行（`docker ps` 报 `failed to connect to the docker API ... dockerDesktopLinuxEngine`），
但只要 3306/6379 上有**本机原生** MySQL/Redis（同端口），把命令里的

```bash
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD"
```

分别换成直连（去掉 `docker exec <容器>` 前缀即可，凭据与端口不变）：

```bash
redis-cli -a "$REDIS_PASSWORD"
mysql -uroot -p"$MYSQL_PASSWORD"
```

其余参数、键名、SQL 一律不动。

**⚠️ Nacos 覆盖检查（必做）**：`order-service/src/main/resources/bootstrap.yaml` 中有
`spring.config.import: optional:nacos:order-service.yaml`。若 Nacos 上存在同名 data-id
且其内容含 `datasource` / `redis` 配置，**它会覆盖本地的池化参数**，Task 1 的改动不会生效。

```bash
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=order-service.yaml&group=DEFAULT_GROUP" | head -40
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=voucher-service.yaml&group=DEFAULT_GROUP" | head -40
```
若返回非空且含池化键，先在 Nacos 上同步成 Task 1 的值，再继续。

**生效验证**（本地改动真的到了运行时）：
```bash
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"maximum-pool-size":[0-9]*'
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"max-active":[0-9]*'
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"threads.max":[0-9]*'
```

## 1. 隔离测试券（禁止打真实券）

```sql
-- 独立券，与业务券完全隔离
INSERT INTO tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES (1, 'SPEC-15 压测专用券', '压测', '压测', 1, 10, 1, 1);
-- 记下自增 id，下称 <VID>
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (<VID>, 100000, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY);
-- 预热 Redis 库存与时间窗
```

```bash
VID=<VID>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SET "seckill:stock:$VID" 100000
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL "seckill:order:$VID" "seckill:order:detail:$VID"

# 活动时间窗（SPEC-14 P0-3）：入口在调用 Lua **之前**会读 seckill:window:{vid} 判定
# begin <= now <= end。用裸 SQL 造券绕开了 VoucherServiceImpl.writeSeckillWindow()，
# 这个 key 不会被自动写入 —— 必须手工铺，否则入口按"无窗口"放行（能跑，但没测到时间窗路径）。
NOW_MS=$(($(date +%s) * 1000))
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" HSET "seckill:window:$VID" \
  begin $((NOW_MS - 3600000)) end $((NOW_MS + 86400000))
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" EXPIRE "seckill:window:$VID" 172800
```

**令牌池** `docs/loadtest/tokens.csv`：每行一个已登录用户的 `Authorization` token，
行数 **≥ 并发数**（限流按 loginId 维度 5 QPS，同一 token 会被限流器挡住 —— 这是预期行为，
故压测必须用多用户池，否则测的是限流器而不是秒杀链路）：

```bash
# 生成 N 个测试用户并登录，导出 token。
#
# 【为什么不走 POST /user/code】sendCode 有 **IP 维度频控：24 小时 20 次**
# （UserServiceImpl 的 CODE_IP_MAX_PER_DAY=20）。本机循环 1000 次全部来自同一个 IP，
# 第 21 个号码起 sendCode 直接返回"验证码发送过于频繁"，验证码从未写入 Redis →
# 后续登录全部失败。更阴险的是失败是**静默**的：每个号码仍会往 csv 追加一个空行，
# 1000 个号码照样得到 1000 行，看行数以为成功了，实际只有 20 个有效 token，
# 而"每 loginId 5 QPS"的限流会把结果染成限流假象。
#
# 所以直接**播种测试夹具**：自己往 Redis 写验证码，跳过 sendCode。
# 这与本手册其它地方用 redis-cli 铺 seckill:stock / seckill:window 是同一手法。
# 登录接口本身不校验"是否发送过"，只比对 Redis 里的值。
FIXED_CODE=123456
rm -f docs/loadtest/tokens.csv
for i in $(seq 1 1000); do
  PHONE="13$(printf '%09d' $i)"
  docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" \
    SETEX "login:code:$PHONE" 300 "$FIXED_CODE" > /dev/null
  curl -s -X POST "http://127.0.0.1:8081/user/login" \
       -H 'Content-Type: application/json' \
       -d "{\"phone\":\"$PHONE\",\"code\":\"$FIXED_CODE\"}" \
    | grep -o '"data":"[^"]*"' | cut -d'"' -f4 >> docs/loadtest/tokens.csv
done

# 校验：必须数**非空行**。数 `wc -l` 是错的 —— 失败时每行都是空行，行数照样是 1000，
# 校验形同虚设（这正是上一版脚本的缺陷）。
TOTAL=$(grep -c '[^[:space:]]' docs/loadtest/tokens.csv)
echo "有效 token 数 = $TOTAL（必须 >= 并发数 1000/500/100）"
# 非零退出：token 池不足时**必须中止**。若只 echo 告警，操作者极易忽略后直接进入 §2，
# 而"每 loginId 5 QPS"的限流会把结果染成"QPS 上不去"的假象，得出错误结论。
[ "$TOTAL" -ge 1000 ] || { echo "!! token 池不足（$TOTAL < 1000），已中止；先排查登录失败原因再压测"; exit 1; }
```

> `login:code:` 的键前缀见 `RedisConstants.LOGIN_CODE_KEY`；TTL 给 300 秒足够覆盖整段建池时间。
> 这也意味着**不要**用 `/user/code` 接口来生成令牌池 —— 它是产品功能，带频控；
> 夹具铺设走 redis-cli 才是确定性的。

## 2. 阶梯压测

```bash
# VID 与 tokens.csv 必须在**同一个 shell** 里有效：下面 `cd docs/loadtest` 之后
# `-JvoucherId=$VID` 依赖它仍在环境中。若分块执行，请先 `export VID=<VID>`。
cd docs/loadtest
for T in 100 500 1000; do
  echo "=== 并发 $T ==="
  "$JMETER_HOME/bin/jmeter" -n -t seckill-baseline.jmx \
    -Jhost=127.0.0.1 -Jport=8081 -JvoucherId=$VID -Jthreads=$T \
    -JtokenPool=tokens.csv -JresultFile=result-$T.jtl \
    -l jmeter-$T.log
  # 汇总：QPS / p95 / p99 / 错误率。
  # 刻意**不用** gawk 的 asort()（非 POSIX，mawk/busybox awk 会报 function not defined），
  # 改成管道 + sort -n，任何 awk 都能跑。
  awk -F, 'NR>1{t++; if($8!="true")e++; s+=$2; print $2}' result-$T.jtl \
    | sort -n > /tmp/spec15-elapsed-$T.txt
  N=$(wc -l < /tmp/spec15-elapsed-$T.txt)
  P95=$(sed -n "$((N*95/100))p" /tmp/spec15-elapsed-$T.txt)
  P99=$(sed -n "$((N*99/100))p" /tmp/spec15-elapsed-$T.txt)
  awk -F, -v n="$N" -v p95="$P95" -v p99="$P99" \
    'NR>1{t++; if($8!="true")e++; s+=$2}
     END{printf "样本=%d 错误=%d 错误率=%.3f%% avg=%.1fms p95=%.1fms p99=%.1fms\n",
     t,e,e*100/t,s/t,p95,p99}' result-$T.jtl
done
```

**采集服务端指标（每档压测前后各一次）**：
```bash
# 只有 order-service(8084) 暴露 prometheus —— 网关(8081) 暴露 health,info,gateway，
# voucher-service(8083) 暴露 seata,health,info，两者都没有 prometheus 端点。
# 秒杀的全部计数都在 order-service 侧，采它一个就够。
curl -s http://127.0.0.1:8084/actuator/prometheus \
  | grep -E '^seckill_(request|success|fail|stock_insufficient|duplicate_order|mq_send_fail|mq_consume_success|dlq_consumed|feign_call|outbox)|^hikaricp_connections_' \
  | head -40

# 网关的限流/风控拦截数在网关侧，但它没有 prometheus 端点，只能看日志：
#   grep -E '网关限流触发|风控黑名单拦截' <gateway-service 日志>
```

## 3. 压测后必做：对账等式断言（超卖数必须为 0）

```bash
# 触发一次对账。**必须带 admin 身份**：SeckillConsistencyController 类上是
# @SaCheckRole("admin")，不带 Authorization 只会拿到 401/403，判定段无法执行。
#
# admin 是谁由 hmdp.admin-user-ids 决定（本仓库 .env 里是 1 = loginId 1）。
# tb_user 没有 seed 行、登录时按手机号自动建号，所以要**反查**这个 id 对应的手机号再登录：
ADMIN_PHONE=$(docker exec hmdp-mysql mysql -N -B -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT phone FROM hmdp.tb_user WHERE id IN (${ADMIN_USER_IDS:-1}) LIMIT 1")
if [ -z "$ADMIN_PHONE" ]; then
  echo "!! ids=${ADMIN_USER_IDS:-1} 在 tb_user 中不存在；先手动指定一个已存在的用户并把其 id 加入 ADMIN_USER_IDS"
fi
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" \
  SETEX "login:code:$ADMIN_PHONE" 300 123456 > /dev/null
ADMIN_TOKEN=$(curl -s -X POST "http://127.0.0.1:8081/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$ADMIN_PHONE\",\"code\":\"123456\"}" \
  | grep -o '"data":"[^"]*"' | cut -d'"' -f4)
echo "ADMIN_TOKEN 长度 = ${#ADMIN_TOKEN}（为 0 说明没拿到 token）"

curl -s -X POST "http://127.0.0.1:8081/seckill/consistency/stock/sync/$VID" \
     -H "Authorization: $ADMIN_TOKEN" | head -c 500
```
期望：响应 `success=true`，日志出现「库存同步成功」且**分歧为 0**；Redis 库存 = DB 库存 − 在途订单数。

```bash
# 三方读数
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" GET "seckill:stock:$VID"
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" HLEN "seckill:order:detail:$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT stock FROM hmdp.tb_seckill_voucher WHERE voucher_id=$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE voucher_id=$VID"
```
**判定（两条同时成立才算通过）**：
1. `Redis库存 == DB库存 − 明细Hash条数`（对账等式，这是主判据）；
2. `tb_voucher_order` 行数 ≤ 100000 且**等于** `100000 − Redis库存`（真正判超卖要看"卖出的份数
   与库存减少量一致"，而不是只比一个上界 —— 只卖出 8 万份时 `行数 <= 100000` 也成立，
   那个写法查不出超卖也查不出少卖）。

> 注意 `明细Hash条数 == 0` 只在**没有死信**时成立：正常消费会 HDEL 掉明细，
> 但"重投耗尽 → 安全释放"与 DLQ 回写会留下条目。若对账后 `明细Hash条数 > 0`，
> 先去 `seckill:order:pending` 与 `tb_seckill_outbox` 查有没有需人工处置的单，
> **不要**直接判定为链路故障。

## 4. 清理（必须执行，避免污染真实库存）

```bash
VID=<VID>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL \
  "seckill:stock:$VID" "seckill:order:$VID" "seckill:order:detail:$VID" \
  "seckill:window:$VID" "seckill:deduct:$VID" "seckill:order:pending"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e "
  DELETE FROM hmdp.tb_voucher_order WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_seckill_outbox WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_seckill_voucher WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_voucher WHERE id=$VID;"

# 风控黑名单：若你在第 0 节的检查里 SREM 过、或验证 P2-2 时 SADD 过，这里必须还原，
# 否则残留条目会持续 403 污染后续任何走 /voucher-order/seckill/ 的操作。
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL risk:blacklist:user risk:blacklist:ip
# 释放墓碑（键是 orderId 不是 voucherId，无法按 VID 批量删）：24h TTL 会自动收敛，
# 不做处理；此处仅说明为何清理后仍能看到 seckill:released:* 的残留键。
```

## 5. 归因方法（拐点在哪）

| 现象 | 首要嫌疑 | 看哪里 |
|---|---|---|
| QPS 到某点后不涨、p99 陡增 | Hikari 池耗尽 | `hikaricp_connections_pending > 0` |
| connectTimeout 类错误 | Lettuce 池耗尽 | `lettuce` 连接数指标 / Tomcat 忙线程数 |
| 429 大幅上升 | 网关限流（**不是**秒杀瓶颈） | 网关日志 `网关限流触发`（网关无 prometheus 端点） |
| **403 大幅上升** | **风控黑名单命中**（SPEC-15 P2-2，**不是**秒杀故障） | 网关日志 `风控黑名单拦截`；核对第 0 节的两个 `SMEMBERS` |
| 库存不足占比高 | 券卖完了 | `seckill.stock.insufficient` |
| 成功数 > 库存 | 超卖 | 立即停止压测并跑第 3 节对账 |
