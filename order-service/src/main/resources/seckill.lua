-- 优惠券秒杀Lua脚本
-- 实现库存预检和一人一单校验的原子操作
-- 返回值: 0-成功, 1-库存不足, 2-重复下单, 3-库存key缺失(需预热)
--
-- 【明细 Hash 值契约】seckill:order:detail:{voucherId} 的 field 值为 JSON：
--   {"voucherId":"..","userId":"..","orderId":"..","ts":"<epoch millis>","retryCount":<n>}
--   ts         —— 写入时刻，由 Java 侧以 ARGV[4] 传入（Lua 沙箱无可靠 os.time）；在途补偿器据此判龄
--   retryCount —— 已重投次数，本脚本恒写 0；由在途补偿器与 DLQ 消费者递增
--   消费端**不解析**该 JSON（从 MQ 消息体取字段），故新增字段为纯增量、无解析风险（SPEC-14 §7 M6）
--
-- 【库存 owner 约定】seckill:stock:{voucherId} 的扣减权只属于本脚本（order-service 是秒杀流量入口）。
-- voucher-service 只扣 DB 库存，不得再操作该 key——两侧同时扣减会造成 2 倍速消耗（SPEC-03 §1.2）。
--
-- 此脚本在秒杀流程中起到核心作用，保证在高并发场景下库存扣减和一人一单校验的原子性。
-- 通过Redis单线程执行特性，避免了并发导致的超卖和重复购买问题。
-- 脚本执行成功后，订单消息由 Java 端同步发送到 MQ，供下游消费者异步处理，实现秒杀流量的削峰填谷。

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local ts = ARGV[4]

-- Redis Key定义
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local orderDetailKey = 'seckill:order:detail:' .. voucherId

-- 1. 检查库存是否存在
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil then
    -- 分开返回：key 缺失是运维态（Redis 重启/flush/过期），与真实"库存不足"必须可区分
    return 3
end

-- 2. 检查库存是否充足
if stock <= 0 then
    return 1
end

-- 3. 检查是否重复下单（一人一单）
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2
end

-- 4. 扣减Redis库存
redis.call('DECR', stockKey)

-- 5. 记录用户已购买（Set集合存储已购买用户）
redis.call('SADD', orderKey, userId)

-- 6. 存储订单详情到Redis（用于后续异步处理和最终一致性校验）
local orderInfo = cjson.encode({
    voucherId = voucherId,
    userId = userId,
    orderId = orderId,
    ts = ts,
    retryCount = 0
})
redis.call('HSET', orderDetailKey, orderId, orderInfo)
-- 补 TTL：消费端清理失败（进程崩溃等）时明细 hash 也不会无界驻留（SPEC-04 §5.2 G7）
-- 每次秒杀都会刷新，活跃券的 TTL 始终往后顺延
redis.call('EXPIRE', orderDetailKey, 3600)

-- 7. 将订单ID追加到观测队列
-- 该队列是纯观测/排障用途（真正的投递由 Java 侧的 RocketMQ 负责），因此必须**有界**：
-- 原实现无 LTRIM 无 TTL，每次秒杀都永久堆积一条（SPEC-04 §1.6 G7）
redis.call('LPUSH', 'seckill:order:queue', orderId)
redis.call('LTRIM', 'seckill:order:queue', 0, 999)
redis.call('EXPIRE', 'seckill:order:queue', 3600)

return 0
