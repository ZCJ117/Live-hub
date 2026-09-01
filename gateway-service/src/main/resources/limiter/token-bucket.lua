-- 令牌桶（FR-11 T4.10：单用户 10 QPS；原子执行）
-- KEYS[1]=桶key  ARGV: capacity, refillPerSec, nowSec
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil then
    tokens = capacity
    ts = now
end
tokens = math.min(capacity, tokens + (now - ts) * refill)
if tokens < 1 then
    redis.call('HSET', key, 'tokens', tokens, 'ts', now)
    redis.call('EXPIRE', key, 60)
    return 0
end
tokens = tokens - 1
redis.call('HSET', key, 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', key, 60)
return 1
