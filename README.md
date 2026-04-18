# Live-hub 仿大众点评系统技术文档

## 1. 项目概述

### 1.1 项目简介
Live-hub是一个基于微服务架构的仿大众点评系统，提供用户注册登录、商户信息展示、优惠券发放、订单管理、社交互动等核心功能。系统采用Spring Cloud Alibaba技术栈，实现了高可用、高性能、可扩展的微服务架构。

### 1.2 核心特性
- **微服务架构**：基于Spring Cloud Alibaba的完整微服务解决方案
- **高并发秒杀**：支持高并发秒杀场景，通过Redis+Lua+RocketMQ实现异步削峰
- **分布式事务**：集成Seata实现分布式事务管理
- **多级缓存**：Redis+Caffeine二级缓存体系
- **服务治理**：Nacos实现服务注册发现与配置管理
- **监控告警**：集成Micrometer、Prometheus实现应用监控

### 1.3 系统架构
```
┌─────────────────────────────────────────────────────────────────────────┐
│                              客户端（Web）                          │
└────────────────────────────────────────┬────────────────────────────────┘
                                         │
┌────────────────────────────────────────┼────────────────────────────────┐
│                         API网关（Gateway-Service）                     │
└────────────────────────────────────────┬────────────────────────────────┘
                                         │
┌────────────────┬─────────────────┬─────┴─────┬─────────────────┬────────────────┐
│ User-Service   │ Shop-Service    │ Voucher-  │ Order-Service   │ Social-Service │
│（用户服务）    │（商户服务）     │ Service   │（订单服务）     │（社交服务）    │
│                │                 │（优惠券） │                 │                │
└────────────────┼─────────────────┼───────────┼─────────────────┼────────────────┘
                 │                 │           │                 │
┌────────────────┴─┐ ┌────────────┴─┐ ┌───────┴───┐ ┌──────────┴───┐ ┌────────────┴───┐
│ User DB          │ │ Shop DB       │ │ Voucher DB│ │ Order DB     │ │ Social DB      │
└──────────────────┘ └──────────────┘ └───────────┘ └──────────────┘ └────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      服务注册与配置中心（Nacos）                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      分布式事务管理器（Seata）                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                      缓存（Redis） + 消息队列（RocketMQ）               │
└─────────────────────────────────────────────────────────────────────────┘
```

## 2. 技术栈

### 2.1 后端技术栈
| 技术栈               | 版本             | 用途                     |
|----------------------|------------------|--------------------------|
| Java                 | 21              | 主要开发语言             |
| Spring Boot          | 3.1.12          | 应用开发框架             |
| Spring Cloud         | 2022.0.4        | 微服务框架               |
| Spring Cloud Alibaba | 2022.0.0.0-RC2  | 微服务生态组件           |
| MyBatis Plus         | 3.5.6           | ORM框架                  |
| MySQL                | 8.0             | 关系型数据库             |
| Redis                | 6.x             | 缓存数据库               |
| Redisson             | 3.23.3          | Redis客户端框架          |
| Seata                | 1.7.0           | 分布式事务框架           |
| Nacos                | 3.1.0           | 服务注册与配置中心       |
| Spring Cloud Gateway | 4.0.5           | API网关                  |
| OpenFeign            | 4.0.5           | 服务间通信框架           |
| RocketMQ             | 2.2.3           | 消息队列                 |
| Docker               | 20.10+          | 容器化部署               |

### 2.2 服务划分
| 服务名称          | 服务端口 | 主要功能                     | 核心数据模型               |
|-------------------|----------|------------------------------|----------------------------|
| gateway-service   | 8081     | API网关、路由转发、权限控制 | -                          |
| user-service      | 8082     | 用户注册、登录、个人信息管理 | User、UserInfo             |
| shop-service      | 8083     | 商户信息、店铺分类、店铺详情 | Shop、ShopType             |
| voucher-service   | 8084     | 优惠券发放、抢购、使用       | Voucher、SeckillVoucher    |
| order-service     | 8085     | 订单创建、支付、查询         | VoucherOrder               |
| social-service    | 8086     | 点赞、评论、关注             | Blog、BlogComments、Follow |

## 3. 核心功能模块说明

### 3.1 用户服务 (user-service)
**主要功能**：
- 用户注册与登录（手机验证码+密码）
- JWT令牌认证与鉴权
- 用户个人信息管理
- 签到功能（Redis Bitmap实现）
- 用户信息查询

**关键技术**：
- Redis存储登录会话
- Hutool工具类处理验证码
- 拦截器实现Token验证

  拦截器配置说明：

  - **RefreshTokenInterceptor**：刷新Token有效期，每次请求时更新Token的过期时间
  - **LoginInterceptor**：验证登录状态，检查用户是否已登录
  - **配置类**：WebMvcConfig位于common模块config包中，负责拦截器注册和路径配置
  - **默认排除路径**：`/user/code`, `/user/login`, `/actuator/**`
  - **自定义排除路径**：支持通过`hmdp.interceptor.excludePaths`配置项添加自定义排除路径

- 分布式ID生成（雪花算法）

### 3.2 商户服务 (shop-service)
**主要功能**：
- 店铺信息CRUD操作
- 店铺分类管理
- 地理位置搜索（Redis GEO）
- 店铺详情缓存（多级缓存策略）

**关键技术**：
- Caffeine本地缓存 + Redis分布式缓存
- 缓存穿透、击穿、雪崩解决方案
- 分布式锁保证缓存重建原子性
- 地理位置搜索算法

### 3.3 优惠券服务 (voucher-service)
**主要功能**：
- 普通优惠券管理
- 秒杀券库存管理
- 优惠券发放与核销
- 库存扣减（数据库+Redis双写）

**关键技术**：
- 数据库乐观锁控制库存
- Redis预减库存提高性能
- 库存同步机制保证数据一致性

### 3.4 订单服务 (order-service)
**主要功能**：
- 秒杀订单创建
- 订单状态管理
- 支付流程处理
- 订单查询与统计

**关键技术**：
- Lua脚本实现原子性秒杀
- RocketMQ异步处理订单
- Seata分布式事务
- 分布式锁保证幂等性

### 3.5 社交服务 (social-service)
**主要功能**：
- 博客发布与展示
- 点赞与评论功能
- 用户关注与粉丝管理
- Feed流推送

**关键技术**：
- Redis Set实现点赞功能
- Sorted Set实现Feed流
- 推拉结合的消息模式
- 分页查询优化

### 3.6 API网关 (gateway-service)
**主要功能**：
- 统一API入口
- 请求路由与负载均衡
- 跨域处理
- 限流与熔断

**关键技术**：
- Spring Cloud Gateway动态路由
- 全局过滤器实现鉴权
- Nacos服务发现集成

## 4. 关键代码实现细节

### 4.1 秒杀系统核心实现

#### 4.1.1 Lua脚本原子性操作
```lua
-- seckill.lua 脚本实现库存预扣减和一人一单校验
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- Redis Key定义
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 检查库存
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 1  -- 库存不足
end

-- 2. 检查是否重复下单
if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2  -- 重复下单
end

-- 3. 扣减库存并记录订单
redis.call('DECR', stockKey)
redis.call('SADD', orderKey, userId)
redis.call('HSET', 'seckill:order:detail:' .. voucherId, orderId, 
    cjson.encode({voucherId = voucherId, userId = userId, orderId = orderId}))
redis.call('LPUSH', 'seckill:order:queue', orderId)

return 0  -- 成功
```

#### 4.1.2 异步订单处理流程
```java
// VoucherOrderServiceImpl.java - 秒杀入口
public Result seckillVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    
    // 执行Lua脚本
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString(), String.valueOf(orderId));
    
    if (result != 0) {
        return result == 1 ? Result.fail("库存不足") : Result.fail("不能重复下单");
    }
    
    // 发送MQ消息异步处理订单
    SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);
    seckillOrderProducer.sendSeckillOrderMessageAsync(message);
    
    return Result.ok(orderId);
}
```

#### 4.1.3 消息消费者实现
```java
// SeckillOrderConsumer.java - 订单消息消费者
@RocketMQMessageListener(
        topic = "seckill-order-topic",
        consumerGroup = "seckill-order-consumer-group",
        maxReconsumeTimes = 3
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {
    
    @Override
    public void onMessage(SeckillOrderMessage message) {
        // 1. 获取分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + message.getOrderId());
        
        // 2. 检查订单是否已存在（幂等性校验）
        VoucherOrder existingOrder = voucherOrderMapper.selectById(message.getOrderId());
        if (existingOrder != null) {
            return; // 订单已存在，直接返回
        }
        
        // 3. 调用优惠券服务扣减库存
        Result deductResult = voucherFeignClient.deductStock(message.getVoucherId());
        
        // 4. 创建订单记录
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        voucherOrder.setStatus(1); // 未支付
        
        voucherOrderMapper.insert(voucherOrder);
    }
}
```

### 4.2 分布式锁实现

#### 4.2.1 Redisson分布式锁配置
```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        return Redisson.create(config);
    }
}
```

#### 4.2.2 自定义Redis锁实现
```java
public class SimpleRedisLock implements ILock {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS));
    }
    
    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
```

### 4.3 缓存策略实现

#### 4.3.1 多级缓存架构
```java
// ShopServiceImpl.java - 店铺信息查询
public Result queryById(Long id) {
    // 1. 查询本地缓存（Caffeine）
    Shop shop = cacheManager.getLocalCache(id);
    if (shop != null) {
        return Result.ok(shop);
    }
    
    // 2. 查询Redis缓存
    String key = CACHE_SHOP_KEY + id;
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(shopJson)) {
        shop = JSONUtil.toBean(shopJson, Shop.class);
        // 回填本地缓存
        cacheManager.setLocalCache(id, shop);
        return Result.ok(shop);
    }
    
    // 3. 查询数据库（加分布式锁防止缓存击穿）
    RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
    try {
        lock.lock();
        // 双重检查
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }
        
        // 查询数据库
        shop = getById(id);
        if (shop == null) {
            // 缓存空值解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        
        // 写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        cacheManager.setLocalCache(id, shop);
        
        return Result.ok(shop);
    } finally {
        lock.unlock();
    }
}
```

## 5. API接口说明

### 5.1 网关路由配置
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-route
          uri: lb://user-service
          predicates:
            - Path=/user/**,/auth/**
        - id: shop-route
          uri: lb://shop-service
          predicates:
            - Path=/shop/**,/shop-type/**
        - id: voucher-route
          uri: lb://voucher-service
          predicates:
            - Path=/voucher/**
        - id: order-route
          uri: lb://order-service
          predicates:
            - Path=/order/**
        - id: social-route
          uri: lb://social-service
          predicates:
            - Path=/blog/**,/comment/**,/follow/**
```

### 5.2 用户服务API
| 方法 | 路径 | 描述 | 参数 |
|------|------|------|------|
| POST | /user/code | 发送手机验证码 | phone |
| POST | /user/login | 用户登录 | LoginFormDTO |
| POST | /user/logout | 用户登出 | Authorization头 |
| GET  | /user/me | 获取当前用户信息 | - |
| GET  | /user/info/{id} | 获取用户详细信息 | id |
| POST | /user/sign | 用户签到 | - |
| GET  | /user/sign/count | 获取签到次数 | - |

### 5.3 商户服务API
| 方法 | 路径 | 描述 | 参数 |
|------|------|------|------|
| GET  | /shop/{id} | 查询店铺详情 | id |
| POST | /shop | 新增店铺 | Shop对象 |
| PUT  | /shop | 更新店铺 | Shop对象 |
| GET  | /shop/of/type | 按类型分页查询 | typeId, current, x, y |
| GET  | /shop/of/name | 按名称分页查询 | name, current |

### 5.4 优惠券服务API
| 方法 | 路径 | 描述 | 参数 |
|------|------|------|------|
| POST | /voucher | 新增普通券 | Voucher对象 |
| POST | /voucher/seckill | 新增秒杀券 | Voucher对象 |
| GET  | /voucher/list/{shopId} | 查询店铺优惠券 | shopId |
| PUT  | /voucher/seckill/{id}/stock | 扣减库存 | id |

### 5.5 订单服务API
| 方法 | 路径 | 描述 | 参数 |
|------|------|------|------|
| POST | /voucher-order/seckill/{id} | 秒杀下单 | id |

### 5.6 社交服务API
| 方法 | 路径 | 描述 | 参数 |
|------|------|------|------|
| POST | /blog | 发布博客 | Blog对象 |
| GET  | /blog/{id} | 查询博客详情 | id |
| PUT  | /blog/like/{id} | 点赞博客 | id |
| GET  | /blog/of/follow | 关注用户博客 | lastId, offset |
| POST | /follow/{id} | 关注用户 | id |
| GET  | /follow/or/not/{id} | 是否关注 | id |

## 6. 使用方法

### 6.1 环境要求
- JDK 21+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+
- Nacos 3.1.0+
- Seata 1.7.0+
- RocketMQ 2.2.3+

### 6.2 快速启动

#### 6.2.1 数据库初始化
1. 创建数据库 `hm_dianping`
2. 执行 `docs/SQL/start.sql` 初始化表结构
3. 执行 `docs/SQL/undo_log.sql` 创建Seata日志表

#### 6.2.2 服务启动顺序
```bash
# 1. 启动基础设施
nacos-server start  # 默认端口8848
seata-server start  # 默认端口7091
redis-server start  # 默认端口6379
rocketmq-start      # 默认端口9876

# 2. 启动微服务（按依赖顺序）
cd user-service && mvn spring-boot:run
cd shop-service && mvn spring-boot:run
cd voucher-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run
cd social-service && mvn spring-boot:run
cd gateway-service && mvn spring-boot:run
```

#### 6.2.3 Docker部署
```bash
# 构建镜像
docker build -t hm-dianping/user-service:latest ./user-service

# 运行容器
docker run -d --name user-service -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=prod \
  hm-dianping/user-service:latest
```

### 6.3 项目构建
```bash
# 克隆项目
git clone <repository-url>
cd hm-dianping

# 编译项目
mvn clean compile

# 打包项目
mvn clean package -DskipTests

# 运行所有测试
mvn test
```

## 7. 配置指南

### 7.1 Nacos配置中心
```yaml
# bootstrap.yaml 配置示例
spring:
  application:
    name: user-service
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yaml
        namespace: dev
        group: DEFAULT_GROUP
      discovery:
        server-addr: 127.0.0.1:8848
```

### 7.2 Redis配置
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: 520117
      lettuce:
        pool:
          max-active: 10
          max-idle: 10
          min-idle: 1
          time-between-eviction-runs: 10s
```

### 7.3 Seata分布式事务配置
```yaml
seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: my_test_tx_group
  registry:
    type: nacos
    nacos:
      server-addr: 127.0.0.1:8848
      namespace: dev
      group: SEATA_GROUP
  config:
    type: nacos
    nacos:
      server-addr: 127.0.0.1:8848
      namespace: dev
      group: SEATA_GROUP
```

### 7.4 RocketMQ配置
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: seckill-producer-group
    send-message-timeout: 3000
    retry-times-when-send-failed: 2
```

### 7.5 监控配置
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

## 8. 常见问题解决

### 8.1 秒杀相关问题

#### 问题1：库存超卖
**现象**：秒杀商品库存出现负数
**原因**：高并发下数据库更新竞争
**解决方案**：
1. Redis Lua脚本原子性预扣减
2. 数据库乐观锁控制最终库存
3. 消息队列异步处理订单

#### 问题2：重复下单
**现象**：同一用户多次购买同一商品
**原因**：并发请求通过了一人一单校验
**解决方案**：
1. Redis Set记录已购买用户
2. 数据库唯一索引约束
3. 消息消费幂等性校验

#### 问题3：系统性能瓶颈
**现象**：秒杀期间系统响应缓慢
**原因**：数据库压力过大
**解决方案**：
1. 流量削峰：消息队列异步处理
2. 读写分离：Redis缓存热点数据
3. 限流降级：网关层限制并发请求

### 8.2 缓存相关问题

#### 问题1：缓存穿透
**现象**：大量请求查询不存在的数据
**解决方案**：
1. 缓存空值（设置较短TTL）
2. 布隆过滤器过滤非法请求
3. 参数校验拦截恶意请求

#### 问题2：缓存击穿
**现象**：热点key过期瞬间大量请求直达数据库
**解决方案**：
1. 永不过期key + 后台异步更新
2. 分布式锁控制缓存重建
3. 缓存预热提前加载热点数据

#### 问题3：缓存雪崩
**现象**：大量key同时过期导致数据库压力骤增
**解决方案**：
1. 随机过期时间分散过期点
2. 多级缓存架构（本地+分布式）
3. 服务降级和熔断保护

### 8.3 分布式事务问题

#### 问题1：事务不一致
**现象**：跨服务操作部分成功部分失败
**解决方案**：
1. Seata AT模式自动补偿
2. 消息队列+本地事务表
3. 业务状态机+对账补偿

#### 问题2：网络超时
**现象**：分布式事务调用超时
**解决方案**：
1. 合理设置超时时间
2. 异步补偿机制
3. 事务状态查询接口

### 8.4 性能优化建议

#### 数据库优化
1. 合理设计索引，避免全表扫描
2. 分库分表处理大数据量
3. 读写分离减轻主库压力
4. SQL语句优化，避免复杂查询

#### 缓存优化
1. 热点数据预加载
2. 缓存数据结构优化
3. 缓存淘汰策略选择
4. 本地缓存减少网络IO

#### JVM优化
1. 合理设置堆内存大小
2. GC算法选择（G1）
3. 线程池参数调优
4. 连接池配置优化

## 9. 开发注意事项

### 9.1 代码规范
1. **命名规范**：遵循Java命名规范，包名小写，类名大驼峰
2. **注释要求**：公共方法必须添加Javadoc注释，复杂逻辑添加行内注释
3. **异常处理**：使用自定义异常，避免直接抛出RuntimeException
4. **日志规范**：合理使用日志级别，敏感信息脱敏

### 9.2 安全规范
1. **输入验证**：所有外部输入必须验证和过滤
2. **SQL注入**：使用MyBatis Plus参数化查询
3. **XSS防护**：输出内容进行HTML转义
4. **CSRF防护**：重要操作使用Token验证
5. **敏感信息**：密码加密存储，配置文件不提交敏感信息

### 9.3 微服务开发规范
1. **服务边界**：明确服务职责，避免功能耦合
2. **API设计**：RESTful风格，版本控制
3. **异常处理**：统一异常响应格式
4. **超时配置**：合理设置Feign调用超时时间
5. **熔断降级**：关键服务必须配置熔断策略

### 9.4 数据库开发规范
1. **索引设计**：为查询条件创建合适索引
2. **事务使用**：避免长事务，合理设置隔离级别
3. **分页查询**：大数据量使用游标分页
4. **字段设计**：使用合适的数据类型，避免过度设计

### 9.5 缓存使用规范
1. **缓存key**：统一前缀，避免冲突
2. **过期时间**：根据业务特点设置合理TTL
3. **缓存更新**：先更新数据库，再删除缓存
4. **缓存监控**：监控缓存命中率，及时调整策略

### 9.6 消息队列使用规范
1. **消息设计**：消息体尽量精简，包含必要信息
2. **幂等性**：消费者必须实现幂等性处理
3. **死信队列**：配置死信队列处理失败消息
4. **消息监控**：监控消息积压情况，及时告警

### 9.7 监控与告警
1. **应用监控**：集成Micrometer暴露指标
2. **业务监控**：关键业务流程添加埋点
3. **日志收集**：统一日志格式，便于ELK分析
4. **告警策略**：设置合理的告警阈值和通知机制

## 10. 扩展与定制

### 10.1 功能扩展建议
1. **搜索优化**：引入Elasticsearch全文搜索
2. **推荐系统**：基于用户行为实现个性化推荐
3. **实时通信**：集成WebSocket实现实时通知
4. **数据分析**：集成大数据平台进行用户行为分析

### 10.2 性能扩展方案
1. **水平扩展**：无状态服务可水平扩展
2. **缓存集群**：Redis集群提高缓存容量和性能
3. **数据库分片**：数据量过大时考虑分库分表
4. **CDN加速**：静态资源使用CDN加速
5. **服务网格**：引入Istio进行服务治理

### 10.3 部署方案优化
1. **K8s编排**：使用Kubernetes进行容器编排 
2. **CI/CD**：建立自动化部署流水线
3. **多环境**：开发、测试、预发、生产环境分离
4. **灰度发布**：支持按比例或按用户特征的灰度发布

### 10.4 监控体系完善
1. **全链路追踪**：集成SkyWalking或Zipkin
2. **日志分析**：建立ELK日志分析平台
3. **指标监控**：Prometheus + Grafana监控体系
4. **告警自动化**：基于规则的自动化告警系统
5. **容量规划**：基于历史数据的容量预测和规划

---