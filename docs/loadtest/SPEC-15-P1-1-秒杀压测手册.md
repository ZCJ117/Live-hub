# SPEC-15 P1-1 · 秒杀接口压测手册

> 对应 SPEC-15 §2.1 / §5.2 E4 / §6 验收 1。**必须实测**，不接受仅单元测试通过。

## 0. 前置检查

```bash
# 中间件
docker ps --format '{{.Names}}' | grep -E 'hmdp-(mysql|redis|nacos|rocketmq)'
# 6 个 Java 服务（网关 8081 / voucher 8083 / order 8084）
curl -s http://127.0.0.1:8081/actuator/health | head -c 200
```

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
```

**令牌池** `docs/loadtest/tokens.csv`：每行一个已登录用户的 `Authorization` token，
行数 **≥ 并发数**（限流按 loginId 维度 5 QPS，同一 token 会被限流器挡住 —— 这是预期行为，
故压测必须用多用户池，否则测的是限流器而不是秒杀链路）：

```bash
# 生成 N 个测试用户并登录，导出 token（示例：用 user-service 登录接口）
for i in $(seq 1 1000); do
  curl -s -X POST "http://127.0.0.1:8081/user/login" \
       -H 'Content-Type: application/json' \
       -d "{\"phone\":\"13$(printf '%09d' $i)\",\"code\":\"123456\"}" \
    | grep -o '"data":"[^"]*"' | cut -d'"' -f4 >> docs/loadtest/tokens.csv
done
```

## 2. 阶梯压测

```bash
cd docs/loadtest
for T in 100 500 1000; do
  echo "=== 并发 $T ==="
  "$JMETER_HOME/bin/jmeter" -n -t seckill-baseline.jmx \
    -Jhost=127.0.0.1 -Jport=8081 -JvoucherId=$VID -Jthreads=$T \
    -JtokenPool=tokens.csv -JresultFile=result-$T.jtl \
    -l jmeter-$T.log
  # 汇总：QPS / p95 / p99 / 错误率
  awk -F, 'NR>1{t++; if($8!="true")e++; s+=$2; a[NR]=$2}
           END{n=asort(a); q=0; printf "样本=%d 错误=%d 错误率=%.3f%% avg=%.1fms p95=%.1fms p99=%.1fms\n",
           t,e,e*100/t,s/t,a[int(n*0.95)],a[int(n*0.99)]}' result-$T.jtl
done
```

**采集服务端指标（每档压测前后各一次）**：
```bash
for s in 8081 8083 8084; do
  echo "--- port $s ---"
  curl -s http://127.0.0.1:$s/actuator/prometheus \
    | grep -E '^seckill_(request|success|fail|stock_insufficient|duplicate_order|mq_send_fail|mq_consume_success|dlq_consumed)|^hikaricp_connections_' \
    | head -30
done
```

## 3. 压测后必做：对账等式断言（超卖数必须为 0）

```bash
# 触发一次对账（order-service 一致性接口）
curl -s -X POST "http://127.0.0.1:8081/seckill/consistency/stock/sync/$VID" | head -c 500
```
期望：日志出现「库存同步成功」且**分歧为 0**；Redis 库存 = DB 库存 − 在途订单数。

```bash
# 三方读数
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" GET "seckill:stock:$VID"
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" HLEN "seckill:order:detail:$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT stock FROM hmdp.tb_seckill_voucher WHERE voucher_id=$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE voucher_id=$VID"
```
**判定**：`Redis库存 == DB库存 − 明细Hash条数` 且 `明细Hash条数 == 0`（消费追平后）；
`tb_voucher_order` 行数 ≤ 100000（**超卖数 = 0**）。

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
```

## 5. 归因方法（拐点在哪）

| 现象 | 首要嫌疑 | 看哪里 |
|---|---|---|
| QPS 到某点后不涨、p99 陡增 | Hikari 池耗尽 | `hikaricp_connections_pending > 0` |
| connectTimeout 类错误 | Lettuce 池耗尽 | `lettuce` 连接数指标 / Tomcat 忙线程数 |
| 429 大幅上升 | 网关限流（**不是**秒杀瓶颈） | `gateway.ratelimit.blocked` |
| 库存不足占比高 | 券卖完了 | `seckill.stock.insufficient` |
| 成功数 > 库存 | 超卖 | 立即停止压测并跑第 3 节对账 |
