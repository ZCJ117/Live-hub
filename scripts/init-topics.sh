#!/bin/sh
# SPEC-08 §5.7 / G6：显式创建业务 topic，不依赖 broker 的 autoCreateTopicEnable。
# 用法（在 broker 容器内执行，mqadmin 随镜像自带）：
#   docker exec hmdp-rocketmq-broker sh /scripts/init-topics.sh
# 也可通过环境变量覆盖 NameServer 地址（默认 localhost:9876，容器内即本机 broker）：
#   docker exec -e NAMESRV_ADDR=rocketmq-namesrv:9876 hmdp-rocketmq-broker sh /scripts/init-topics.sh
# 脚本可重复执行：topic 已存在时 updateTopic 幂等（失败用 || true 兜住，不中断后续创建）。
set -e

NAMESRV=${NAMESRV_ADDR:-localhost:9876}

for t in seckill-order-topic rag-document-process agent-m5-ticket-route; do
  sh mqadmin updateTopic -n "$NAMESRV" -c DefaultCluster -t "$t" || true
done
