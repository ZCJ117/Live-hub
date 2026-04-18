-- 优惠券秒杀Lua脚本
-- 实现库存预检和一人一单校验的原子操作
-- 返回值: 0-成功, 1-库存不足, 2-重复下单
--
-- 此脚本在秒杀流程中起到核心作用，保证在高并发场景下库存扣减和一人一单校验的原子性。
-- 通过Redis单线程执行特性，避免了并发导致的超卖和重复购买问题。
-- 脚本执行成功后，会将订单信息写入Redis队列，供下游消费者异步处理，实现秒杀流量的削峰填谷。

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- Redis Key定义
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local orderDetailKey = 'seckill:order:detail:' .. voucherId

-- 1. 检查库存是否存在
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil then
    return 1
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
    orderId = orderId
})
redis.call('HSET', orderDetailKey, orderId, orderInfo)

-- 7. 将订单ID添加到待处理队列（供消费者消费）
redis.call('LPUSH', 'seckill:order:queue', orderId)

return 0
