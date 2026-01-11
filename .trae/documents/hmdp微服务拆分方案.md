# hmdp微服务拆分方案

## 1. 项目结构分析

### 1.1 现有技术栈
- Spring Boot 3.3.4
- MySQL 5.6.22
- Redis (WSL Ubuntu)
- RabbitMQ 本地运行
- MyBatis-Plus
- Redisson 3.23.3

### 1.2 现有业务模块
- 用户管理
- 商户管理
- 优惠券管理
- 订单管理
- 博客社交
- 关注关系

## 2. 微服务拆分方案

### 2.1 模块划分

| 微服务名称 | 端口 | 主要职责 | 对应数据库表 |
|------------|------|----------|--------------|
| gateway-service | 8080 | API网关，请求路由 | - |
| user-service | 8081 | 用户管理、认证授权 | tb_user, tb_user_info, tb_sign |
| shop-service | 8082 | 商户信息、商户类型 | tb_shop, tb_shop_type |
| voucher-service | 8083 | 优惠券管理、秒杀活动 | tb_voucher, tb_seckill_voucher |
| order-service | 8084 | 订单管理、秒杀订单 | tb_voucher_order |
| social-service | 8085 | 博客、评论、关注 | tb_blog, tb_blog_comments, tb_follow |
| common | - | 公共依赖、工具类 | - |

### 2.2 技术栈选择

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.15 | 基础框架 |
| Spring Cloud | 2023.0.1 | 微服务框架 |
| Spring Cloud Alibaba | 2023.0.1.0 | 微服务生态 |
| Nacos | 3.1.0 | 注册中心、配置中心 |
| Spring Cloud Gateway | 4.1.5 | API网关 |
| OpenFeign | 4.1.5 | 服务间调用 |
| Seata | 2.0.0 | 分布式事务 |
| Redisson | 3.23.3 | 分布式锁 |

## 3. 实施步骤

### 3.1 第一阶段：项目结构重构

1. 创建Maven父工程，统一管理依赖版本
2. 创建common模块，提取公共代码
3. 创建各个微服务模块

### 3.2 第二阶段：数据库拆分

1. 按模块拆分数据库，创建5个数据库实例
2. 修改各服务的数据源配置
3. 实现跨服务数据访问的Feign接口

### 3.3 第三阶段：微服务组件集成

1. 集成Nacos注册中心
2. 集成Nacos配置中心
3. 配置Spring Cloud Gateway
4. 集成OpenFeign
5. 配置Redis跨WSL访问
6. 集成Seata（如需）

### 3.4 第四阶段：业务代码迁移

1. 将现有代码按模块迁移到对应微服务
2. 调整服务间调用方式
3. 配置API网关路由
4. 测试各服务功能

## 4. 关键配置示例

### 4.1 Nacos配置（bootstrap.yaml）
```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      server-addr: localhost:8848
      discovery:
        namespace: public
      config:
        namespace: public
        file-extension: yaml
```

### 4.2 Gateway路由配置
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-route
          uri: lb://user-service
          predicates:
            - Path=/user/**
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
            - Path=/blog/**,/follow/**
```

### 4.3 Redis跨WSL访问配置
```yaml
spring:
  redis:
    host: 172.25.16.1  # WSL IP地址
    port: 6379
```

### 4.4 OpenFeign接口示例
```java
@FeignClient("user-service")
public interface UserClient {
    @GetMapping("/user/{id}")
    Result<UserDTO> getUserById(@PathVariable("id") Long id);
}
```

## 5. 本地开发注意事项

1. 确保Nacos服务已启动（端口8848）
2. 配置WSL IP地址用于Redis访问
3. 按顺序启动服务：Nacos → 各微服务 → Gateway
4. 使用Postman测试API网关路由
5. 查看Nacos控制台确认服务注册状态

## 6. 数据一致性处理

1. **用户下单流程**：
   - order-service调用voucher-service扣减库存
   - 使用Seata实现分布式事务，确保订单创建和库存扣减的一致性

2. **博客发布流程**：
   - social-service直接操作自己的数据库
   - 如需通知其他服务，使用RabbitMQ实现最终一致性

3. **关注用户流程**：
   - social-service直接操作自己的数据库
   - 如需更新用户关注数，使用Redis缓存或异步更新

## 7. 实施计划

1. **第1天**：搭建父工程和common模块，配置依赖管理
2. **第2天**：创建各微服务模块，集成Nacos
3. **第3天**：配置Gateway，实现服务注册与发现
4. **第4天**：拆分数据库，配置数据源
5. **第5天**：迁移用户服务、商户服务代码
6. **第6天**：迁移优惠券服务、订单服务代码
7. **第7天**：迁移社交服务代码，实现服务间调用
8. **第8天**：测试整体功能，调试问题

## 8. 版本兼容性说明

- Spring Boot 3.2.x 兼容 Spring Cloud 2023.0.x
- Spring Cloud 2023.0.x 兼容 Spring Cloud Alibaba 2023.0.1.0
- Redisson 3.23.3 兼容 Spring Boot 3.x
- Nacos 3.1.0 兼容 Spring Cloud Alibaba 2023.0.1.0

## 9. 预期效果

1. 各微服务独立部署、独立运行
2. 通过API网关可访问所有服务
3. 服务间调用正常，数据一致性得到保证
4. 支持本地开发调试，便于后续扩展

## 10. 后续优化建议

1. 引入Sentinel进行服务熔断限流
2. 引入ELK进行日志管理
3. 引入Prometheus+Grafana进行监控
4. 实现灰度发布功能
5. 优化数据库索引，提高查询性能