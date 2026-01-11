# hm-dianping 微服务项目

## 项目介绍
本项目基于Spring Cloud Alibaba微服务架构，对原“黑马点评”单体系统进行了架构升级与深度性能优化。核心成果为：通过设计并集成Caffeine本地缓存与Redis分布式缓存，
构建了高性能二级缓存体系，使核心服务缓存层QPS提升至21,027，有效降低数据库负载70%以上。
同时，引入RabbitMQ消息队列实现订单异步化与流量削峰，结合Redisson分布式锁保障高并发场景下数据强一致性。系统具备高可用、弹性可扩展特性，成功支撑了类大众点评业务下的高并发访问。

### 核心功能

- **用户管理**：用户注册、登录、信息管理
- **店铺管理**：店铺分类、店铺查询、店铺详情
- **优惠券管理**：优惠券发布、领取、使用
- **订单管理**：订单创建、支付、查询
- **社交功能**：博客发布、评论、关注

## 技术栈

| 技术 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.1.12 | 应用框架 |
| Spring Cloud | 2022.0.4 | 微服务框架 |
| Spring Cloud Alibaba | 2022.0.0.0-RC2 | 微服务生态组件 |
| MyBatis Plus | 3.5.6 | ORM框架 |
| Hutool | 5.8.22 | Java工具包 |
| Redisson | 3.23.3 | Redis客户端 |
| Seata | 1.7.0 | 分布式事务框架 |
| Nacos | - | 服务注册与配置中心 |
| Java | 21 | 开发语言 |

## 架构设计

### 微服务模块划分

```
hm-dianping
├── common/                  # 公共模块
├── gateway-service/         # 网关服务
├── user-service/            # 用户服务
├── shop-service/            # 店铺服务
├── voucher-service/         # 优惠券服务
├── order-service/           # 订单服务
└── social-service/          # 社交服务
```

### 服务调用关系

- **gateway-service**：作为所有请求的入口，负责路由转发、负载均衡、认证授权
- **user-service**：提供用户相关的业务功能
- **shop-service**：提供店铺相关的业务功能
- **voucher-service**：提供优惠券相关的业务功能
- **order-service**：提供订单相关的业务功能，依赖voucher-service
- **social-service**：提供社交相关的业务功能，依赖user-service

### 核心中间件

- **Nacos**：服务注册与发现、配置中心
- **Redis**：缓存、分布式锁、限流
- **Seata**：分布式事务管理
- **MySQL**：关系型数据库
- **Spring Cloud Gateway**：API网关
- **MyBatis Plus**：数据持久化框架
- **Redisson**：Redis客户端，提供高级功能

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- Nacos 2.x
- Redis 6.x+
- MySQL 8.x+

### 依赖安装

```bash
# 安装项目依赖
mvn clean install -DskipTests
```

### 启动顺序

1. 启动Nacos服务
2. 启动Redis服务
3. 启动MySQL服务
4. 启动各个微服务（顺序不限）

### 配置说明

每个服务的配置文件位于`src/main/resources`目录下：

- `bootstrap.yaml`：Nacos配置中心配置
- `application.yaml`：本地应用配置

主要配置项：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| spring.cloud.nacos.server-addr | Nacos服务地址 | localhost:8848 |
| spring.cloud.nacos.discovery.namespace | 服务发现命名空间 | public |
| spring.cloud.nacos.config.namespace | 配置中心命名空间 | public |
| spring.datasource.url | 数据库连接地址 | - |
| spring.datasource.username | 数据库用户名 | - |
| spring.datasource.password | 数据库密码 | - |
| spring.redis.host | Redis地址 | localhost |
| spring.redis.port | Redis端口 | 6379 |

## 模块说明

### common 公共模块

公共模块包含了各个微服务共享的代码，包括：

- 实体类（Entity）
- DTO对象
- 工具类
- 公共配置
- 拦截器

### gateway-service 网关服务

- 基于Spring Cloud Gateway实现
- 负责请求路由转发
- 实现服务认证授权
- 提供负载均衡功能

### user-service 用户服务

- 用户注册、登录
- 用户信息管理
- 用户认证授权

### shop-service 店铺服务

- 店铺分类管理
- 店铺信息管理
- 店铺查询

### voucher-service 优惠券服务

- 优惠券发布
- 优惠券领取
- 优惠券使用
- 秒杀优惠券

### order-service 订单服务

- 订单创建
- 订单支付
- 订单查询
- 分布式事务管理

### social-service 社交服务

- 博客发布与查询
- 博客评论
- 用户关注功能

## 核心业务流程

### 用户注册登录流程

1. 用户通过手机号注册
2. 验证码发送与验证
3. 密码加密存储
4. JWT令牌生成与返回
5. 令牌验证与刷新

### 优惠券领取流程

1. 检查优惠券是否存在
2. 检查用户是否已领取
3. 检查优惠券库存
4. 扣减库存
5. 记录领取记录

### 订单创建流程

1. 验证用户身份
2. 验证优惠券有效性
3. 扣减优惠券库存
4. 创建订单
5. 扣减账户余额
6. 分布式事务提交

### 社交功能流程

1. 发布博客：验证用户身份 → 保存博客 → 推送通知
2. 评论博客：验证用户身份 → 保存评论 → 推送通知
3. 关注用户：验证用户身份 → 保存关注关系 → 更新关注数

## 开发指南

### 代码规范

- 遵循阿里巴巴Java开发规范
- 使用Lombok简化代码
- 统一异常处理
- 日志规范

### 提交规范

提交信息格式：

```
[模块名] 功能描述

例如：
[user-service] 修复用户登录失败问题
```

### 测试流程

- 单元测试：使用JUnit 5
- 集成测试：使用Testcontainers
- 接口测试：使用Postman或Swagger

## 部署说明

### 本地开发环境

1. 克隆代码仓库
2. 安装依赖
3. 启动Nacos、Redis、MySQL
4. 启动各微服务
5. 访问Swagger文档：http://localhost:8080/swagger-ui.html

### 测试环境

- 使用Docker Compose部署
- 配置CI/CD流水线
- 自动化测试

### 生产环境

- 使用Kubernetes部署
- 配置HPA自动扩缩容
- 启用服务网格
- 配置监控告警

## 监控与运维

### 服务监控

- 使用Spring Boot Actuator暴露监控端点
- 集成Prometheus和Grafana
- 配置服务健康检查

### 日志管理

- 使用SLF4J+Logback
- 日志分级：DEBUG、INFO、WARN、ERROR
- 日志归档：按天归档
- 日志收集：使用ELK Stack

### 异常处理

- 统一异常封装
- 异常分类：业务异常、系统异常
- 异常日志记录
- 异常告警通知

## 贡献指南

1. Fork代码仓库
2. 创建特性分支：`git checkout -b feature/xxx`
3. 提交代码：`git commit -m "[模块名] 功能描述"`
4. 推送分支：`git push origin feature/xxx`
5. 创建Pull Request
