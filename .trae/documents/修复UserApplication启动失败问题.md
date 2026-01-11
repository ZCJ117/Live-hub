## 问题分析
1. **核心问题**：UserController中使用`@Autowired`注入`RedisTemplate<String, Object>`，但Spring容器中找不到该Bean
2. **根本原因**：Nacos配置中心中`user-service.yaml`文件为空，导致Spring Boot无法读取Redis连接信息，因此没有自动创建RedisTemplate
3. **现象**：UserServiceImpl中使用的是`StringRedisTemplate`，而UserController中使用的是`RedisTemplate<String, Object>`，两者不一致

## 解决方案
结合分析，我将采取以下步骤修复问题：

### 步骤1：统一Redis操作模板
将UserController中的`RedisTemplate<String, Object>`替换为`StringRedisTemplate`，与UserServiceImpl保持一致

### 步骤2：修改UserController代码
- 将第42行的`RedisTemplate<String, Object>`替换为`StringRedisTemplate`
- 修改logout方法中的Redis操作，使用`StringRedisTemplate`的API

### 步骤3：确保配置优先级正确
确认bootstrap.yaml中的Nacos配置import为optional，确保本地application.yaml中的Redis配置能生效

## 预期效果
1. UserController不再依赖`RedisTemplate<String, Object>`，改为使用已存在的`StringRedisTemplate`
2. 代码风格保持一致，减少依赖冲突
3. 即使Nacos配置缺失，本地配置也能正常生效，确保应用可以启动

## 修复文件
- `d:\hm-dianping\user-service\src\main\java\com\hmdp\user\controller\UserController.java`