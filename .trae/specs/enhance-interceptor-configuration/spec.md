# 完善拦截器配置规范

## Why
当前项目中存在两个拦截器（LoginInterceptor和RefreshTokenInterceptor），但缺乏明确的配置类来定义哪些路径需要Token验证、哪些不需要。这可能导致：
1. 公开接口（如登录、发送验证码）被错误拦截，用户无法正常访问
2. 受保护接口缺乏适当的身份验证机制
3. 拦截器执行顺序和路径规则不明确，维护困难

## What Changes
- **ADDED**: 在common模块中添加WebMvcConfigurer配置类，明确注册拦截器并定义路径排除规则
- **ADDED**: 配置类支持可配置的公开路径列表，便于不同微服务定制
- **MODIFIED**: 优化拦截器执行顺序，确保RefreshTokenInterceptor先执行，LoginInterceptor后执行
- **ADDED**: 添加测试验证公开接口可访问且受保护接口有身份验证

## Impact
- **受影响模块**: common模块（所有微服务共享）
- **受影响服务**: user-service、shop-service、voucher-service、order-service、social-service
- **关键文件**:
  - `common/src/main/java/com/hmdp/config/WebMvcConfig.java` (新增)
  - `common/src/main/java/com/hmdp/utils/LoginInterceptor.java` (可能微调)
  - `common/src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java` (可能微调)
  - 各微服务的application.yaml配置文件

## ADDED Requirements
### Requirement: 拦截器配置管理
系统SHALL提供集中式的拦截器配置管理，明确指定哪些路径需要Token验证、哪些不需要。

#### Scenario: 公开接口可访问
- **WHEN** 用户访问公开接口（如`POST /user/code`、`POST /user/login`）
- **THEN** 请求应被放行，不需要Token验证

#### Scenario: 受保护接口需要身份验证
- **WHEN** 用户访问受保护接口（如`GET /user/me`、`POST /user/logout`）
- **THEN** 请求必须携带有效Token，否则返回401状态码

#### Scenario: 拦截器正确执行顺序
- **WHEN** 用户发送请求
- **THEN** RefreshTokenInterceptor先执行（刷新Token有效期），LoginInterceptor后执行（验证登录状态）

### Requirement: 可配置的公开路径
系统SHALL支持通过配置文件自定义公开路径列表，便于不同微服务根据业务需求调整。

## MODIFIED Requirements
### Requirement: 现有拦截器集成
现有拦截器（LoginInterceptor和RefreshTokenInterceptor）SHALL通过配置类正式注册到Spring MVC框架中，确保其正确生效。

## REMOVED Requirements
无