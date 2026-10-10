# Review package: SPEC-03/06/07 implementation

Range: d4c5950...HEAD (three-dot)

## Commits

```
b393d1e docs(specs): SPEC-03/06/07 交付实测记录——自动化全绿，全栈项标待环境
ed20527 fix(review): 修复 StpInterface 重复注册(Critical) 并清理 SPEC-06/07 三处残留
00bf0d9 test(gateway): 内部端点白名单补 /internal/voucher
ebd81f7 fix(gateway): SPEC-06 §5.6 CORS 改显式白名单，内部端点无路由加回归断言
1d17657 fix(security): SPEC-06 §5.5 验证码接口加频控并移除验证码明文日志
5f109cd fix(security): SPEC-06 §5.4 /user/info/{id} 脱敏——不再回传积分/生日/性别
e9677a7 feat(security): SPEC-06 §5.3 最小角色模型——管理接口加 @SaCheckRole(admin)
fc710a3 fix(security): SPEC-06 §5.1 业务服务补登录拦截器，鉴权失败返回 401/403
39694ce perf(order): SPEC-07 G6 消除 queryMyOrders 的 N+1 远程调用
e07e3b2 fix(feign): SPEC-07 G3 用户态 token 透传下沉 common 并挂到 order/social
d93e936 fix(seckill): SPEC-03 G2/G4 消费者错误语义修正 + 明细 key 契约冻结
b2ca842 fix(seckill): SPEC-03 G2/G3/G5 秒杀入口改同步发送并补齐回滚与判空
c818a5c fix(seckill): 扣减幂等集合补 24h TTL，Redis 故障分支不再静默
fd16fd7 fix(seckill): SPEC-03 §5.1/§5.4 消除 Redis 库存双扣并加 (voucherId,orderId) 服务端幂等
66fd690 test(contract): 契约护栏补上缺失分号时的边界，并收窄断言文档口径
1dbdcd3 fix(feign): SPEC-07 G1/G5 补齐 POST /user/list 并新增 Feign 契约测试
d817fcf docs(readme): 补 §6.2 环境变量表与 .env 机制说明
84ab532 fix(security): SPEC-06 §5.2 内部端点共享密钥通道——/internal/** 校验 X-Internal-Token
f8abf90 fix(docs): README §7.2 Redis 段误用 MYSQL_PASSWORD 变量名
18de3de docs(specs): 脱敏——设计书与计划书不再内嵌明文口令字面量
afb51af fix(config): SPEC-06 G8 凭据外置——8 份配置走环境变量，仓库零明文口令
```

## Diffstat

```
 .env.example                                       |   8 +
 .gitignore                                         |   3 +
 README.md                                          |   8 +-
 .../java/com/hmdp/agent/feign/RagFeignClient.java  |   4 +-
 agent-service/src/main/resources/application.yaml  |  13 +-
 .../com/hmdp/agent/it/OrderRefundAtomicDbIT.java   |   4 +-
 .../com/hmdp/agent/it/RefundIdempotencyDbIT.java   |   4 +-
 .../com/hmdp/agent/it/SessionP1FeaturesDbIT.java   |   4 +-
 .../hmdp/agent/memory/ChatMemoryServiceTest.java   |   4 +-
 .../java/com/hmdp/agent/parity/ParityTestBase.java |   4 +-
 .../hmdp/agent/planner/FlowStateServiceTest.java   |   4 +-
 common/pom.xml                                     |  10 ++
 .../java/com/hmdp/config/AdminRoleProvider.java    |  50 ++++++
 .../com/hmdp/config/FeignTokenRelayConfig.java     |  35 ++++
 .../com/hmdp/config/InternalTokenFeignConfig.java  |  26 +++
 .../com/hmdp/config/InternalTokenInterceptor.java  |  40 +++++
 .../main/java/com/hmdp/config/SaTokenConfig.java   |  53 +++++-
 .../com/hmdp/config/SaTokenExceptionHandler.java   |  15 +-
 common/src/main/java/com/hmdp/dto/UserInfoVO.java  |  20 +++
 .../main/java/com/hmdp/utils/RedisConstants.java   |  10 ++
 .../com/hmdp/config/AdminRoleProviderTest.java     |  28 ++++
 .../hmdp/config/InternalTokenInterceptorTest.java  |  55 +++++++
 .../com/hmdp/config/SaTokenConfigPathsTest.java    |  33 ++++
 .../java/com/hmdp/contract/FeignContractTest.java  | 178 +++++++++++++++++++++
 .../src/test/java/com/hmdp/dto/UserInfoVOTest.java |  41 +++++
 ...0-07-spec-03-06-07-\344\277\256\345\244\215.md" |  26 +--
 ...ec-03-06-07-\344\277\256\345\244\215-design.md" |  73 ++++++++-
 .../hmdp/gateway/filter/AgentRateLimitFilter.java  |   5 +-
 .../src/main/resources/application.yaml            |  11 +-
 .../gateway/limit/AgentTokenBucketLimiterIT.java   |   5 +-
 .../gateway/route/GatewayRouteContractTest.java    |  24 ++-
 order-service/pom.xml                              |   5 +
 .../controller/SeckillConsistencyController.java   |   2 +
 .../order/fallback/VoucherFeignClientFallback.java |  23 ---
 .../com/hmdp/order/feign/VoucherFeignClient.java   |  26 ++-
 .../com/hmdp/order/metrics/SeckillMetrics.java     |  10 ++
 .../com/hmdp/order/mq/SeckillOrderConsumer.java    |  76 +++++----
 .../com/hmdp/order/mq/SeckillOrderProducer.java    |  19 ++-
 .../impl/SeckillConsistencyServiceImpl.java        |   2 +-
 .../service/impl/VoucherOrderServiceImpl.java      | 107 +++++++++----
 order-service/src/main/resources/application.yaml  |  16 +-
 order-service/src/main/resources/seckill.lua       |  10 +-
 .../hmdp/order/mq/SeckillOrderConsumerTest.java    | 147 +++++++++++++++++
 .../hmdp/order/service/QueryMyOrdersBatchTest.java |  84 ++++++++++
 .../order/service/SeckillVoucherServiceTest.java   | 134 ++++++++++++++++
 rag-service/src/main/resources/application.yaml    |  11 +-
 .../com/hmdp/shop/controller/ShopController.java   |   3 +
 shop-service/src/main/resources/application.yaml   |  13 +-
 .../com/hmdp/social/feign/UserFeignClient.java     |   7 +-
 social-service/src/main/resources/application.yaml |   9 +-
 user-service/pom.xml                               |   6 +
 .../com/hmdp/user/controller/UserController.java   |  43 ++++-
 .../java/com/hmdp/user/service/IUserService.java   |   7 +-
 .../hmdp/user/service/impl/UserServiceImpl.java    |  59 ++++++-
 user-service/src/main/resources/application.yaml   |   9 +-
 .../user/service/UserServiceImplSendCodeTest.java  |  87 ++++++++++
 voucher-service/pom.xml                            |   6 +
 .../controller/InternalVoucherController.java      |  35 ++++
 .../hmdp/voucher/controller/VoucherController.java |  22 ++-
 .../com/hmdp/voucher/service/IVoucherService.java  |   9 +-
 .../voucher/service/impl/VoucherServiceImpl.java   |  42 ++++-
 .../src/main/resources/application.yaml            |  14 +-
 .../service/VoucherServiceImplDeductStockTest.java |  95 +++++++++++
 63 files changed, 1753 insertions(+), 183 deletions(-)
```

## Full diff (U10)

```diff
diff --git a/.env.example b/.env.example
new file mode 100644
index 0000000..9bbfbc8
--- /dev/null
+++ b/.env.example
@@ -0,0 +1,8 @@
+# 本地开发凭据模板。复制为 .env 并填值；.env 已被 .gitignore 忽略，不会入库。
+# 加载方式：set -a; source .env; set +a
+MYSQL_PASSWORD=
+REDIS_PASSWORD=
+# 内部端点共享密钥（/internal/** 校验，见 SPEC-06 §5.2）
+INTERNAL_TOKEN=
+# 管理员用户 id，逗号分隔（决定 @SaCheckRole("admin") 的判定，见 SPEC-06 §5.3）
+ADMIN_USER_IDS=
diff --git a/.gitignore b/.gitignore
index 8d8bb04..33584a4 100644
--- a/.gitignore
+++ b/.gitignore
@@ -31,10 +31,13 @@ build/
 
 ### VS Code ###
 .vscode/
 
 ### E2E取证（含登录token，禁止入库）
 .e2e/
 
 ### JVM崩溃日志
 hs_err_pid*.log
 replay_pid*.log
+
+# 本地凭据（SPEC-06 G8）：模板见 .env.example
+.env
diff --git a/README.md b/README.md
index 6b98734..f90fab2 100644
--- a/README.md
+++ b/README.md
@@ -487,20 +487,26 @@ spring:
 
 > 本项目开发环境通过 Docker Desktop 管理中间件（Nacos:8848、MySQL:3306、Redis:6379、Seata:8091、RocketMQ容器 `hmdp-rocketmq-namesrv`/`hmdp-rocketmq-broker` 4.9.4）。
 
 ### 6.2 环境变量
 | 变量名 | 使用服务 | 必填 | 说明 |
 |--------|----------|------|------|
 | GLM_API_KEY | agent-service, rag-service | 是 | 智谱开放平台API密钥；agent-service未设置时LLM功能降级，rag-service必须设置 |
 | RAG_DB_PASSWORD | rag-service | 是 | PostgreSQL密码 |
 | RAG_DB_USER | rag-service | 否 | PostgreSQL用户（默认 rag_user） |
 | RAG_UPLOAD_DIR | rag-service | 否 | 文档上传目录（默认 ./data/rag-uploads） |
+| MYSQL_PASSWORD | 全部服务 | 是 | MySQL 密码；各服务 `application.yaml` 中为 `${MYSQL_PASSWORD:}` |
+| REDIS_PASSWORD | 全部服务 | 是 | Redis 密码；各服务 `application.yaml` 中为 `${REDIS_PASSWORD:}` |
+| INTERNAL_TOKEN | voucher-service, rag-service, order-service, agent-service | 是 | `/internal/**` 内部端点的共享密钥（SPEC-06 §5.2）。**未设置时内部调用一律 401**，秒杀扣减、RAG 检索会失败 |
+| ADMIN_USER_IDS | shop-service, voucher-service, order-service | 否 | 管理员用户 id，逗号分隔；决定 `@SaCheckRole("admin")` 的判定（SPEC-06 §5.3）。留空则无人拥有 admin 角色，管理接口对所有登录用户返回 403 |
+
+本地开发把上述变量写进仓库根目录的 `.env`（模板见 `.env.example`），`.env` 已被 `.gitignore` 忽略、不会入库。各服务通过 `application.yaml` 的 `spring.config.import: optional:file:.env[.properties],optional:file:../.env[.properties]` 载入它，两个相对路径分别覆盖"从仓库根 `java -jar`"与"从模块目录 `mvn -pl x test`"两种工作目录。环境变量优先级高于 `.env`：`export MYSQL_PASSWORD=...` 会覆盖 `.env` 中的同名值（Spring Boot 的属性源顺序中 Config data 位于 OS environment variables 之前，后者胜出），因此也可以在启动前用 `set -a; source .env; set +a` 导出。仓库内不含任何明文口令。
 
 ### 6.3 快速启动
 
 #### 6.3.1 数据库初始化
 1. 创建MySQL数据库 `hmdp`，执行 `docs/SQL/start.sql` 初始化表结构
 2. 执行 `docs/SQL/undo_log.sql` 创建Seata回滚日志表
 3. 执行 `sql/phase3-voucher-fields.sql`、`sql/phase4-notification.sql`（hmdp库增量字段/表）
 4. 执行 `sql/agent_service-ddl.sql`（自动建 `agent_service` 库），再执行 `sql/phase4-agent-task-biz-order.sql`、`sql/phase4-reconciliation.sql`、`sql/phase5-session-snapshot.sql`
 5. RAG服务（可选）：`docker compose -f docker-compose.yml up -d postgres-rag` 启动PostgreSQL+pgvector（自动执行 `sql/init-rag.sql`，端口5433）
 
@@ -570,21 +576,21 @@ spring:
     import: optional:nacos:${spring.application.name}.yaml
 ```
 
 ### 7.2 Redis配置
 ```yaml
 spring:
   data:
     redis:
       host: localhost
       port: 6379
-      password: 520117
+      password: ${REDIS_PASSWORD:}   # 见 .env.example
       lettuce:
         pool:
           max-active: 10
           max-idle: 5
           min-idle: 0
 ```
 
 ### 7.3 Seata分布式事务配置
 ```yaml
 # bootstrap.yaml（各服务一致）
diff --git a/agent-service/src/main/java/com/hmdp/agent/feign/RagFeignClient.java b/agent-service/src/main/java/com/hmdp/agent/feign/RagFeignClient.java
index 06e6714..b515601 100644
--- a/agent-service/src/main/java/com/hmdp/agent/feign/RagFeignClient.java
+++ b/agent-service/src/main/java/com/hmdp/agent/feign/RagFeignClient.java
@@ -1,19 +1,19 @@
 package com.hmdp.agent.feign;
 
-import com.hmdp.agent.config.FeignAuthConfig;
+import com.hmdp.config.InternalTokenFeignConfig;
 import com.hmdp.dto.Result;
 import org.springframework.cloud.openfeign.FeignClient;
 import org.springframework.web.bind.annotation.PostMapping;
 import org.springframework.web.bind.annotation.RequestBody;
 
 import java.util.Map;
 
 /**
  * rag-service 内部检索客户端（FR-07 扩展，D1.4 C4；Nacos 服务发现直连，不经网关）
  */
-@FeignClient(name = "rag-service", configuration = FeignAuthConfig.class, contextId = "ragInternal")
+@FeignClient(name = "rag-service", configuration = InternalTokenFeignConfig.class, contextId = "ragInternal")
 public interface RagFeignClient {
 
     @PostMapping("/internal/rag/retrieval/search")
     Result search(@RequestBody Map<String, Object> body);
 }
diff --git a/agent-service/src/main/resources/application.yaml b/agent-service/src/main/resources/application.yaml
index fae907d..cca53fe 100644
--- a/agent-service/src/main/resources/application.yaml
+++ b/agent-service/src/main/resources/application.yaml
@@ -1,25 +1,30 @@
 server:
   port: 8088 # agent-service 端口（8081~8087 已占用）
 
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver
     # 独立 schema agent_service，复用既有 MySQL 实例（PRD 4.4）
     url: jdbc:mysql://127.0.0.1:3306/agent_service?useSSL=false&serverTimezone=Asia/Shanghai
     username: root
-    password: 520117
+    password: ${MYSQL_PASSWORD:}
   data:
     redis:
       host: localhost
       port: 6379
-      password: 520117
+      password: ${REDIS_PASSWORD:}
       lettuce:
         pool:
           max-active: 10
           max-idle: 5
           min-idle: 1
   jackson:
     default-property-inclusion: non_null
 
 mybatis-plus:
   type-aliases-package: com.hmdp.agent.entity
@@ -73,20 +78,24 @@ logging:
 
 management:
   endpoints:
     web:
       exposure:
         include: health,info,metrics,prometheus
   metrics:
     tags:
       application: agent-service
 
+# 内部端点共享密钥（SPEC-06 §5.2）
+hmdp:
+  internal-token: ${INTERNAL_TOKEN:}
+
 sa-token:
   token-name: Authorization
   timeout: 2592000
   active-timeout: -1
   is-concurrent: true
   is-share: true
   token-style: uuid
   is-log: false
 
 # RocketMQ（T4.7 工单路由通知，与 order/rag 同一实例）
diff --git a/agent-service/src/test/java/com/hmdp/agent/it/OrderRefundAtomicDbIT.java b/agent-service/src/test/java/com/hmdp/agent/it/OrderRefundAtomicDbIT.java
index 29b547f..2346041 100644
--- a/agent-service/src/test/java/com/hmdp/agent/it/OrderRefundAtomicDbIT.java
+++ b/agent-service/src/test/java/com/hmdp/agent/it/OrderRefundAtomicDbIT.java
@@ -22,27 +22,29 @@ import static org.junit.jupiter.api.Assertions.assertEquals;
 
 /**
  * order 库退款原子性（T4.4）：并发 UPDATE ... WHERE status=2 仅 1 次成功，status 终态=5
  * 直连 hmdp 库复现 VoucherOrderServiceImpl.refund 的原子 UPDATE（第二道闸门裁决）
  */
 @Tag("db-it")
 class OrderRefundAtomicDbIT {
 
     private static final String URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai";
     private static final String USER = "root";
-    private static final String PASS = "520117";
+    private static final String PASS = System.getenv("MYSQL_PASSWORD");
 
     private static Connection conn;
     private static long orderId;
 
     @BeforeAll
     static void setup() throws Exception {
+        org.junit.jupiter.api.Assumptions.assumeTrue(PASS != null && !PASS.isBlank(),
+                "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
         try {
             conn = DriverManager.getConnection(URL, USER, PASS);
         } catch (Exception e) {
             conn = null;
         }
         Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过退款原子性 db-it");
         orderId = System.currentTimeMillis();
         Statement st = conn.createStatement();
         st.execute("INSERT IGNORE INTO tb_voucher_order(id, user_id, voucher_id, status, create_time) "
                 + "VALUES (" + orderId + ", 999999, 1, 2, NOW())");
diff --git a/agent-service/src/test/java/com/hmdp/agent/it/RefundIdempotencyDbIT.java b/agent-service/src/test/java/com/hmdp/agent/it/RefundIdempotencyDbIT.java
index 2b52787..0059c9c 100644
--- a/agent-service/src/test/java/com/hmdp/agent/it/RefundIdempotencyDbIT.java
+++ b/agent-service/src/test/java/com/hmdp/agent/it/RefundIdempotencyDbIT.java
@@ -22,26 +22,28 @@ import static org.junit.jupiter.api.Assertions.assertEquals;
 
 /**
  * 并发确认幂等压测（FR-08 验收 1：并发 10 次确认仅 1 条生效；P4-R4 硬门禁）
  * 直连 agent_service 库复现 ConfirmTaskService.tryAdopt 的条件更新 SQL
  */
 @Tag("db-it")
 class RefundIdempotencyDbIT {
 
     private static final String URL = "jdbc:mysql://127.0.0.1:3306/agent_service?useSSL=false&serverTimezone=Asia/Shanghai";
     private static final String USER = "root";
-    private static final String PASS = "520117";
+    private static final String PASS = System.getenv("MYSQL_PASSWORD");
 
     private static Connection conn;
 
     @BeforeAll
     static void setup() {
+        org.junit.jupiter.api.Assumptions.assumeTrue(PASS != null && !PASS.isBlank(),
+                "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
         try {
             conn = DriverManager.getConnection(URL, USER, PASS);
         } catch (Exception e) {
             conn = null;
         }
         Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过幂等 db-it");
     }
 
     @Test
     void 并发10次条件更新仅1次胜出() throws Exception {
diff --git a/agent-service/src/test/java/com/hmdp/agent/it/SessionP1FeaturesDbIT.java b/agent-service/src/test/java/com/hmdp/agent/it/SessionP1FeaturesDbIT.java
index 34997a8..d7db3fd 100644
--- a/agent-service/src/test/java/com/hmdp/agent/it/SessionP1FeaturesDbIT.java
+++ b/agent-service/src/test/java/com/hmdp/agent/it/SessionP1FeaturesDbIT.java
@@ -21,27 +21,29 @@ import static org.junit.jupiter.api.Assertions.assertTrue;
  * 直连 agent_service 库验证服务层依赖的 SQL 语义：
  * 1. 评价每会话仅一次 = rating IS NULL 条件更新（重复仅 1 胜出）
  * 2. 90 天归档条件更新 = status IN + update_time 边界
  * 3. 快照 uk_session 唯一键 = 固化幂等
  */
 @Tag("db-it")
 class SessionP1FeaturesDbIT {
 
     private static final String URL = "jdbc:mysql://127.0.0.1:3306/agent_service?useSSL=false&serverTimezone=Asia/Shanghai";
     private static final String USER = "root";
-    private static final String PASS = "520117";
+    private static final String PASS = System.getenv("MYSQL_PASSWORD");
 
     private static Connection conn;
     private static long sessionId;
 
     @BeforeAll
     static void setup() throws Exception {
+        org.junit.jupiter.api.Assumptions.assumeTrue(PASS != null && !PASS.isBlank(),
+                "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
         try {
             conn = DriverManager.getConnection(URL, USER, PASS);
         } catch (Exception e) {
             conn = null;
         }
         Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过会话 P1 数据层契约 db-it");
         sessionId = System.currentTimeMillis();
         Statement st = conn.createStatement();
         st.execute("INSERT INTO agent_session(id, user_id, module, status, entry, flow_state, msg_count, "
                 + "create_time, update_time) VALUES (" + sessionId + ", 999999, 'M5', 'CLOSED', 'my', 'IDLE', 3, NOW(), NOW())");
diff --git a/agent-service/src/test/java/com/hmdp/agent/memory/ChatMemoryServiceTest.java b/agent-service/src/test/java/com/hmdp/agent/memory/ChatMemoryServiceTest.java
index 7405c43..d6c899f 100644
--- a/agent-service/src/test/java/com/hmdp/agent/memory/ChatMemoryServiceTest.java
+++ b/agent-service/src/test/java/com/hmdp/agent/memory/ChatMemoryServiceTest.java
@@ -30,21 +30,23 @@ class ChatMemoryServiceTest {
 
     @BeforeAll
     static void setUp() {
         try {
             Config config = new Config();
             var server = config.useSingleServer()
                     .setAddress("redis://127.0.0.1:6379")
                     .setConnectTimeout(500)
                     .setTimeout(1000)
                     .setRetryAttempts(1);
-            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "520117");
+            String pwd = System.getenv("REDIS_PASSWORD");
+            org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
+                    "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
             if (!pwd.isBlank()) {
                 server.setPassword(pwd);
             }
             redisson = Redisson.create(config);
             redisson.getBucket("agent:test:ping").set("1"); // 探活：失败则跳过全部用例
             memoryService = new ChatMemoryService(redisson, PROPS);
         } catch (Exception e) {
             redisson = null;
         }
         assumeTrue(redisson != null, "Redis 不可达，跳过记忆集成测试");
diff --git a/agent-service/src/test/java/com/hmdp/agent/parity/ParityTestBase.java b/agent-service/src/test/java/com/hmdp/agent/parity/ParityTestBase.java
index 543e181..92a4ea1 100644
--- a/agent-service/src/test/java/com/hmdp/agent/parity/ParityTestBase.java
+++ b/agent-service/src/test/java/com/hmdp/agent/parity/ParityTestBase.java
@@ -24,21 +24,21 @@ import static org.junit.jupiter.api.Assumptions.assumeTrue;
 
 /**
  * 对拍基座（T3.14）：业务库探活 + JDBC 直查 + 登录辅助
  * 环境不可达 → 跳过（如实记录在报告，不虚构一致率）
  */
 public abstract class ParityTestBase {
 
     protected static final String BIZ_URL =
             "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai";
     protected static final String DB_USER = "root";
-    protected static final String DB_PWD = "520117";
+    protected static final String DB_PWD = System.getenv("MYSQL_PASSWORD");
     protected static final String GATEWAY = "http://127.0.0.1:8081";
 
     protected static Connection biz;
 
     /** 中间件探活（供子类 @EnabledIf 守卫：Nacos 未启动时在 Spring 上下文启动前跳过） */
     public static boolean middlewareReachable() {
         try (java.net.Socket s = new java.net.Socket("127.0.0.1", 8848)) {
             return true;
         } catch (Exception e) {
             return false;
@@ -63,20 +63,22 @@ public abstract class ParityTestBase {
         return serviceUp("shop-service");
     }
 
     /** 供子类 @EnabledIf：voucher-service 是否已注册到 Nacos */
     public static boolean voucherServiceUp() {
         return serviceUp("voucher-service");
     }
 
     @BeforeAll
     static void initBizDb() {
+        assumeTrue(DB_PWD != null && !DB_PWD.isBlank(),
+                "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
         try {
             biz = DriverManager.getConnection(BIZ_URL, DB_USER, DB_PWD);
             biz.createStatement().executeQuery("SELECT 1");
         } catch (Exception e) {
             biz = null;
         }
         assumeTrue(biz != null, "业务库 hmdp 不可达，跳过对拍测试（待环境）");
     }
 
     @AfterAll
diff --git a/agent-service/src/test/java/com/hmdp/agent/planner/FlowStateServiceTest.java b/agent-service/src/test/java/com/hmdp/agent/planner/FlowStateServiceTest.java
index 2d46efe..5039645 100644
--- a/agent-service/src/test/java/com/hmdp/agent/planner/FlowStateServiceTest.java
+++ b/agent-service/src/test/java/com/hmdp/agent/planner/FlowStateServiceTest.java
@@ -24,21 +24,23 @@ class FlowStateServiceTest {
 
     @BeforeAll
     static void setUp() {
         try {
             Config config = new Config();
             var server = config.useSingleServer()
                     .setAddress("redis://127.0.0.1:6379")
                     .setConnectTimeout(500)
                     .setTimeout(1000)
                     .setRetryAttempts(1);
-            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "520117");
+            String pwd = System.getenv("REDIS_PASSWORD");
+            org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
+                    "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
             if (!pwd.isBlank()) {
                 server.setPassword(pwd);
             }
             redisson = Redisson.create(config);
             redisson.getBucket("agent:test:ping").set("1");
             service = new FlowStateService(redisson, mock(AgentSessionService.class));
         } catch (Exception e) {
             redisson = null;
         }
         assumeTrue(redisson != null, "Redis 不可达，跳过流程状态测试");
diff --git a/common/pom.xml b/common/pom.xml
index f88d084..c2810ec 100644
--- a/common/pom.xml
+++ b/common/pom.xml
@@ -78,20 +78,30 @@
             <artifactId>spring-cloud-starter-openfeign</artifactId>
         </dependency>
 
         <!-- Sa-Token Core（仅核心 API，无 Web 框架依赖） -->
         <dependency>
             <groupId>cn.dev33</groupId>
             <artifactId>sa-token-core</artifactId>
             <version>${sa-token.version}</version>
         </dependency>
 
+        <!-- Sa-Token Servlet 支持（SaInterceptor / WebMvcConfigurer 注册用）
+             必须 provided：网关是 WebFlux，不能把 servlet starter 传递过去；
+             各业务服务本就各自声明了 sa-token-spring-boot3-starter，运行期不缺 -->
+        <dependency>
+            <groupId>cn.dev33</groupId>
+            <artifactId>sa-token-spring-boot3-starter</artifactId>
+            <version>${sa-token.version}</version>
+            <scope>provided</scope>
+        </dependency>
+
         <!-- 测试（SPEC-02 §8.1 SchemaConsistencyTest 的前置：common 原无 test 依赖） -->
         <dependency>
             <groupId>org.springframework.boot</groupId>
             <artifactId>spring-boot-starter-test</artifactId>
             <scope>test</scope>
         </dependency>
     </dependencies>
 
     <build>
         <plugins>
diff --git a/common/src/main/java/com/hmdp/config/AdminRoleProvider.java b/common/src/main/java/com/hmdp/config/AdminRoleProvider.java
new file mode 100644
index 0000000..320cd9f
--- /dev/null
+++ b/common/src/main/java/com/hmdp/config/AdminRoleProvider.java
@@ -0,0 +1,50 @@
+package com.hmdp.config;
+
+import cn.dev33.satoken.stp.StpInterface;
+import org.springframework.beans.factory.annotation.Value;
+
+import java.util.Arrays;
+import java.util.Collections;
+import java.util.List;
+import java.util.Set;
+import java.util.stream.Collectors;
+
+/**
+ * 最小角色模型（SPEC-06 §5.3）
+ *
+ * <p>全项目原先只有"登录/未登录"二元状态，故管理接口无法做授权。这里用配置来源
+ * 提供 {@code admin} 角色，避免给 {@code tb_user} 加列引入 DDL 变更——若走持久化，
+ * 还需额外 SQL 才能产生第一个管理员，否则所有管理接口对所有人 403。
+ *
+ * <p>注意：本类由 {@link SaTokenConfig} 以 {@code @Bean} 注册，是 {@code StpInterface}
+ * 的唯一注册路径。**不得再加类级注解使其成为 Bean**：order-service 与 rag-service 都
+ * 扫描 {@code com.hmdp} 全包，一旦被扫到就会与 {@code @Bean} 形成两个同类型 Bean，
+ * 任何按类型注入 {@code StpInterface} 的地方都会抛
+ * {@code NoUniqueBeanDefinitionException}（Sa-Token 内部按参数名回退才侥幸不炸）。
+ */
+public class AdminRoleProvider implements StpInterface {
+
+    private static final String ADMIN = "admin";
+
+    private final Set<String> adminUserIds;
+
+    public AdminRoleProvider(@Value("${hmdp.admin-user-ids:}") String adminUserIds) {
+        this.adminUserIds = Arrays.stream(adminUserIds.split(","))
+                .map(String::trim)
+                .filter(s -> !s.isEmpty())
+                .collect(Collectors.toUnmodifiableSet());
+    }
+
+    @Override
+    public List<String> getRoleList(Object loginId, String loginType) {
+        if (loginId != null && adminUserIds.contains(loginId.toString())) {
+            return List.of(ADMIN);
+        }
+        return Collections.emptyList();
+    }
+
+    @Override
+    public List<String> getPermissionList(Object loginId, String loginType) {
+        return Collections.emptyList();
+    }
+}
diff --git a/common/src/main/java/com/hmdp/config/FeignTokenRelayConfig.java b/common/src/main/java/com/hmdp/config/FeignTokenRelayConfig.java
new file mode 100644
index 0000000..b2a88fd
--- /dev/null
+++ b/common/src/main/java/com/hmdp/config/FeignTokenRelayConfig.java
@@ -0,0 +1,35 @@
+package com.hmdp.config;
+
+import cn.dev33.satoken.stp.StpUtil;
+import feign.RequestInterceptor;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+/**
+ * Feign 用户态身份透传（SPEC-07 §5.2）
+ *
+ * <p>把当前请求的 Sa-Token token 透传给下游业务服务，使其能识别登录态与做归属校验。
+ * 参照 agent-service 既有的 FeignAuthConfig 实践下沉到 common，供所有服务复用。
+ *
+ * <p>与 {@link InternalTokenFeignConfig} 的区分：用户态调用（点赞榜、共同关注、
+ * 订单查询）走本配置透传 Authorization；内部后台调用（扣库存、检索）走共享密钥。
+ */
+@Configuration
+public class FeignTokenRelayConfig {
+
+    @Bean
+    public RequestInterceptor tokenRelayInterceptor() {
+        return template -> {
+            try {
+                String token = StpUtil.getTokenValue();
+                if (token != null && !token.isEmpty()) {
+                    template.header("Authorization", token);
+                }
+            } catch (Exception ignored) {
+                // MQ 消费线程等无 HTTP 请求上下文的场景：Sa-Token 会抛 SaTokenContextException。
+                // 此时无登录态可透传，交由内部密钥路径（InternalTokenFeignConfig）处理。
+                // 不兜住会污染秒杀链路。
+            }
+        };
+    }
+}
diff --git a/common/src/main/java/com/hmdp/config/InternalTokenFeignConfig.java b/common/src/main/java/com/hmdp/config/InternalTokenFeignConfig.java
new file mode 100644
index 0000000..01fe472
--- /dev/null
+++ b/common/src/main/java/com/hmdp/config/InternalTokenFeignConfig.java
@@ -0,0 +1,26 @@
+package com.hmdp.config;
+
+import feign.RequestInterceptor;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+/**
+ * Feign 内部调用密钥注入（SPEC-06 §5.2 / SPEC-07 §5.2）
+ *
+ * <p>用户态 Feign 调用走 {@link FeignTokenRelayConfig} 透传 Authorization；
+ * 内部后台调用（扣库存、检索）走本配置的共享密钥——两者显式区分。
+ */
+@Configuration
+public class InternalTokenFeignConfig {
+
+    @Bean
+    public RequestInterceptor internalTokenRelayInterceptor(
+            @Value("${hmdp.internal-token:}") String internalToken) {
+        return template -> {
+            if (internalToken != null && !internalToken.isBlank()) {
+                template.header("X-Internal-Token", internalToken);
+            }
+        };
+    }
+}
diff --git a/common/src/main/java/com/hmdp/config/InternalTokenInterceptor.java b/common/src/main/java/com/hmdp/config/InternalTokenInterceptor.java
new file mode 100644
index 0000000..200681a
--- /dev/null
+++ b/common/src/main/java/com/hmdp/config/InternalTokenInterceptor.java
@@ -0,0 +1,40 @@
+package com.hmdp.config;
+
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.servlet.http.HttpServletResponse;
+import org.springframework.web.servlet.HandlerInterceptor;
+
+import java.io.IOException;
+
+/**
+ * 内部端点鉴权（SPEC-06 §5.2 方案 A）
+ *
+ * <p>{@code /internal/**} 不依赖登录态——它服务于 MQ 消费线程等无请求上下文的调用方。
+ * 改由共享密钥 {@code X-Internal-Token} 校验，挡住跨网段随手调用。
+ *
+ * <p>安全默认：密钥未配置时不放行（宁可内部调用失败，也不静默裸露）。
+ */
+public class InternalTokenInterceptor implements HandlerInterceptor {
+
+    private static final String HEADER = "X-Internal-Token";
+    private static final String BODY = "{\"success\":false,\"errorMsg\":\"内部接口拒绝访问\"}";
+
+    private final String expected;
+
+    public InternalTokenInterceptor(String expected) {
+        this.expected = expected;
+    }
+
+    @Override
+    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
+            throws IOException {
+        String provided = request.getHeader(HEADER);
+        if (expected != null && !expected.isBlank() && expected.equals(provided)) {
+            return true;
+        }
+        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
+        response.setContentType("application/json;charset=UTF-8");
+        response.getWriter().write(BODY);
+        return false;
+    }
+}
diff --git a/common/src/main/java/com/hmdp/config/SaTokenConfig.java b/common/src/main/java/com/hmdp/config/SaTokenConfig.java
index 22261ef..ae925a0 100644
--- a/common/src/main/java/com/hmdp/config/SaTokenConfig.java
+++ b/common/src/main/java/com/hmdp/config/SaTokenConfig.java
@@ -1,15 +1,64 @@
 package com.hmdp.config;
 
+import cn.dev33.satoken.interceptor.SaInterceptor;
+import cn.dev33.satoken.stp.StpInterface;
+import cn.dev33.satoken.stp.StpUtil;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
+import org.springframework.context.annotation.Bean;
 import org.springframework.context.annotation.Configuration;
+import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
+import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
+
+import java.util.Set;
 
 /**
- * Sa-Token 自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）
+ * Sa-Token 与内部端点自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）
  * 通过 AutoConfiguration.imports 注册，确保引用 common 的所有业务服务自动加载
+ *
+ * <p>补上服务侧鉴权拦截（SPEC-06 §5.1）：此前鉴权只在网关一层，业务服务端口
+ * 直接绑定 0.0.0.0，任何能访问该端口的人无需 token 即可调用全部接口。
  */
 @Configuration
 @ConditionalOnWebApplication(type = Type.SERVLET)
-public class SaTokenConfig {
+@ConditionalOnClass(SaInterceptor.class)
+public class SaTokenConfig implements WebMvcConfigurer {
+
+    /**
+     * 免登录路径。其中 {@code /user/login}、{@code /user/code}、{@code /actuator/**}
+     * 三项与网关 SaTokenGatewayConfig 的既有白名单一致；{@code /internal/**} 与
+     * {@code /error} 是服务侧额外放开的——前者由 InternalTokenInterceptor 用共享密钥保护，
+     * 不走登录态，后者承接容器转发的错误页。
+     */
+    static final Set<String> LOGIN_EXCLUDE_PATHS = Set.of(
+            "/user/login", "/user/code", "/internal/**", "/actuator/**", "/error");
+
+    private final String internalToken;
+
+    public SaTokenConfig(@Value("${hmdp.internal-token:}") String internalToken) {
+        this.internalToken = internalToken;
+    }
+
+    /**
+     * 注册角色来源。Sa-Token 通过 StpInterface 解析 {@code @SaCheckRole}。
+     * 此处显式注册而非依赖扫描——common 的 com.hmdp.config 不在各服务的扫描范围内。
+     */
+    @Bean
+    public StpInterface stpInterface(@Value("${hmdp.admin-user-ids:}") String adminUserIds) {
+        return new AdminRoleProvider(adminUserIds);
+    }
+
+    @Override
+    public void addInterceptors(InterceptorRegistry registry) {
+        // 内部端点：不走登录态，走共享密钥
+        registry.addInterceptor(new InternalTokenInterceptor(internalToken))
+                .addPathPatterns("/internal/**");
 
+        // 业务接口：校验登录态
+        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
+                .addPathPatterns("/**")
+                .excludePathPatterns(LOGIN_EXCLUDE_PATHS.toArray(String[]::new));
+    }
 }
diff --git a/common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java b/common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java
index 76f8f0c..66862c5 100644
--- a/common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java
+++ b/common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java
@@ -1,42 +1,49 @@
 package com.hmdp.config;
 
 import cn.dev33.satoken.exception.NotLoginException;
 import cn.dev33.satoken.exception.NotPermissionException;
 import cn.dev33.satoken.exception.NotRoleException;
 import cn.dev33.satoken.exception.SaTokenException;
 import com.hmdp.dto.Result;
+import jakarta.servlet.http.HttpServletResponse;
 import lombok.extern.slf4j.Slf4j;
 import org.springframework.web.bind.annotation.ExceptionHandler;
 import org.springframework.web.bind.annotation.RestControllerAdvice;
 
 /**
  * Sa-Token 异常处理器，将 Sa-Token 异常适配为项目统一的 Result 格式
+ * 同时设置真实 HTTP 状态码（SPEC-06 §1.7）：未登录 401、无权限 403，
+ * 否则前端与监控无法按状态码识别鉴权失败。
  */
 @RestControllerAdvice
 @Slf4j
 public class SaTokenExceptionHandler {
 
     @ExceptionHandler(NotLoginException.class)
-    public Result handleNotLoginException(NotLoginException e) {
+    public Result handleNotLoginException(NotLoginException e, HttpServletResponse response) {
         log.warn("Sa-Token 未登录异常: type={}, message={}", e.getType(), e.getMessage());
+        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
         return Result.fail("未登录，请先登录");
     }
 
     @ExceptionHandler(NotRoleException.class)
-    public Result handleNotRoleException(NotRoleException e) {
+    public Result handleNotRoleException(NotRoleException e, HttpServletResponse response) {
         log.warn("Sa-Token 无角色权限: role={}, message={}", e.getRole(), e.getMessage());
+        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
         return Result.fail("无角色权限");
     }
 
     @ExceptionHandler(NotPermissionException.class)
-    public Result handleNotPermissionException(NotPermissionException e) {
+    public Result handleNotPermissionException(NotPermissionException e, HttpServletResponse response) {
         log.warn("Sa-Token 无此权限: permission={}, message={}", e.getPermission(), e.getMessage());
+        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
         return Result.fail("无此权限");
     }
 
     @ExceptionHandler(SaTokenException.class)
-    public Result handleSaTokenException(SaTokenException e) {
+    public Result handleSaTokenException(SaTokenException e, HttpServletResponse response) {
         log.error("Sa-Token 异常: code={}, message={}", e.getCode(), e.getMessage());
+        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
         return Result.fail("认证服务异常");
     }
 }
diff --git a/common/src/main/java/com/hmdp/dto/UserInfoVO.java b/common/src/main/java/com/hmdp/dto/UserInfoVO.java
new file mode 100644
index 0000000..a4cf7db
--- /dev/null
+++ b/common/src/main/java/com/hmdp/dto/UserInfoVO.java
@@ -0,0 +1,20 @@
+package com.hmdp.dto;
+
+import lombok.Data;
+
+/**
+ * 用户详情脱敏投影（SPEC-06 §5.4）
+ *
+ * <p>刻意**不含** {@code credits}（积分）、{@code birthday}（生日）、{@code gender}（性别）——
+ * 原实现直接返回 UserInfo 实体，任意登录用户可枚举他人这些隐私字段。
+ * {@code UserInfo} 本身不含手机号/邮箱，故无需额外处理。
+ */
+@Data
+public class UserInfoVO {
+    private Long userId;
+    private String city;
+    private String introduce;
+    private Integer fans;
+    private Integer followee;
+    private Boolean level;
+}
diff --git a/common/src/main/java/com/hmdp/utils/RedisConstants.java b/common/src/main/java/com/hmdp/utils/RedisConstants.java
index da81a58..356e81a 100644
--- a/common/src/main/java/com/hmdp/utils/RedisConstants.java
+++ b/common/src/main/java/com/hmdp/utils/RedisConstants.java
@@ -1,27 +1,37 @@
 package com.hmdp.utils;
 
 public class RedisConstants {
     public static final String LOGIN_CODE_KEY = "login:code:";
     public static final Long LOGIN_CODE_TTL = 2L;
+    /** 验证码频控（SPEC-06 §5.5）：手机号 60 秒 1 次 / 24 小时 10 次，IP 24 小时 20 次 */
+    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:phone:";
+    public static final String LOGIN_CODE_COUNT_KEY = "login:code:count:phone:";
+    public static final String LOGIN_CODE_IP_COUNT_KEY = "login:code:count:ip:";
     public static final String LOGIN_USER_KEY = "login:token:";
     public static final Long LOGIN_USER_TTL = 36000L;
 
     public static final Long CACHE_NULL_TTL = 2L;
 
     public static final Long CACHE_SHOP_TTL = 30L;
     public static final String CACHE_SHOP_KEY = "cache:shop:";
 
     public static final String LOCK_SHOP_KEY = "lock:shop:";
     public static final Long LOCK_SHOP_TTL = 10L;
 
     public static final String SECKILL_STOCK_KEY = "seckill:stock:";
+    /** 已购用户 Set —— 与 seckill.lua 的 orderKey 同拼法 */
+    public static final String SECKILL_ORDER_SET_KEY = "seckill:order:";
+    /** 订单明细 Hash —— 与 seckill.lua 的 orderDetailKey 同拼法（本批冻结的契约） */
+    public static final String SECKILL_ORDER_DETAIL_KEY = "seckill:order:detail:";
+    /** 扣减幂等 Set（SPEC-03 §5.4，键为 orderId） */
+    public static final String SECKILL_DEDUCT_KEY = "seckill:deduct:";
     public static final String BLOG_LIKED_KEY = "blog:liked:";
     public static final String FEED_KEY = "feed:";
     public static final String SHOP_GEO_KEY = "shop:geo:";
     public static final String USER_SIGN_KEY = "sign:";
 
     public static final String SHOP_LIST_KEY = "shop:list:";
 
     // JetCache 配置（可根据需要调整）
     public static final Integer JETCACHE_LOCAL_LIMIT = 100;
     public static final Long JETCACHE_EXPIRE = 7200L; // 2小时
diff --git a/common/src/test/java/com/hmdp/config/AdminRoleProviderTest.java b/common/src/test/java/com/hmdp/config/AdminRoleProviderTest.java
new file mode 100644
index 0000000..0683e15
--- /dev/null
+++ b/common/src/test/java/com/hmdp/config/AdminRoleProviderTest.java
@@ -0,0 +1,28 @@
+package com.hmdp.config;
+
+import org.junit.jupiter.api.Test;
+
+import static org.junit.jupiter.api.Assertions.*;
+
+class AdminRoleProviderTest {
+
+    @Test
+    void 配置内的用户id获得admin角色() {
+        AdminRoleProvider provider = new AdminRoleProvider("1, 42");
+        assertEquals(java.util.List.of("admin"), provider.getRoleList(1L, "login"));
+        assertEquals(java.util.List.of("admin"), provider.getRoleList("42", "login"));
+    }
+
+    @Test
+    void 配置外的用户无角色() {
+        AdminRoleProvider provider = new AdminRoleProvider("1,42");
+        assertTrue(provider.getRoleList(99L, "login").isEmpty());
+    }
+
+    @Test
+    void 未配置时不授予任何角色_安全默认() {
+        AdminRoleProvider provider = new AdminRoleProvider("");
+        assertTrue(provider.getRoleList(1L, "login").isEmpty());
+        assertTrue(provider.getRoleList(null, "login").isEmpty());
+    }
+}
diff --git a/common/src/test/java/com/hmdp/config/InternalTokenInterceptorTest.java b/common/src/test/java/com/hmdp/config/InternalTokenInterceptorTest.java
new file mode 100644
index 0000000..f3ba385
--- /dev/null
+++ b/common/src/test/java/com/hmdp/config/InternalTokenInterceptorTest.java
@@ -0,0 +1,55 @@
+package com.hmdp.config;
+
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.servlet.http.HttpServletResponse;
+import org.junit.jupiter.api.Test;
+import org.springframework.mock.web.MockHttpServletRequest;
+import org.springframework.mock.web.MockHttpServletResponse;
+
+import static org.junit.jupiter.api.Assertions.*;
+
+class InternalTokenInterceptorTest {
+
+    @Test
+    void 密钥正确时放行() throws Exception {
+        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
+        MockHttpServletRequest req = new MockHttpServletRequest();
+        req.addHeader("X-Internal-Token", "secret");
+        MockHttpServletResponse resp = new MockHttpServletResponse();
+
+        assertTrue(interceptor.preHandle(req, resp, new Object()));
+        assertEquals(200, resp.getStatus());
+    }
+
+    @Test
+    void 密钥缺失时返回401且不放行() throws Exception {
+        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
+        MockHttpServletResponse resp = new MockHttpServletResponse();
+
+        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), resp, new Object()));
+        assertEquals(401, resp.getStatus());
+        assertTrue(resp.getContentAsString().contains("内部接口拒绝访问"));
+    }
+
+    @Test
+    void 密钥错误时返回401() throws Exception {
+        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
+        MockHttpServletRequest req = new MockHttpServletRequest();
+        req.addHeader("X-Internal-Token", "wrong");
+        MockHttpServletResponse resp = new MockHttpServletResponse();
+
+        assertFalse(interceptor.preHandle(req, resp, new Object()));
+        assertEquals(401, resp.getStatus());
+    }
+
+    @Test
+    void 服务端未配置密钥时不放行_安全默认() throws Exception {
+        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("");
+        MockHttpServletRequest req = new MockHttpServletRequest();
+        req.addHeader("X-Internal-Token", "");
+        MockHttpServletResponse resp = new MockHttpServletResponse();
+
+        assertFalse(interceptor.preHandle(req, resp, new Object()));
+        assertEquals(401, resp.getStatus());
+    }
+}
diff --git a/common/src/test/java/com/hmdp/config/SaTokenConfigPathsTest.java b/common/src/test/java/com/hmdp/config/SaTokenConfigPathsTest.java
new file mode 100644
index 0000000..a20e26a
--- /dev/null
+++ b/common/src/test/java/com/hmdp/config/SaTokenConfigPathsTest.java
@@ -0,0 +1,33 @@
+package com.hmdp.config;
+
+import org.junit.jupiter.api.Test;
+
+import java.lang.reflect.Field;
+import java.util.Arrays;
+import java.util.Set;
+
+import static org.junit.jupiter.api.Assertions.*;
+
+/**
+ * 登录白名单口径锁定（SPEC-06 §5.1 / 验收 A10）
+ *
+ * <p>白名单必须与网关 SaTokenGatewayConfig 的既有白名单严格一致——多一项会放宽公开面，
+ * 少一项会把网关已放行的接口在服务侧拒掉。
+ */
+class SaTokenConfigPathsTest {
+
+    @Test
+    void 白名单包含公开接口与内部端点与运维端点() throws Exception {
+        Field f = SaTokenConfig.class.getDeclaredField("LOGIN_EXCLUDE_PATHS");
+        f.setAccessible(true);
+        @SuppressWarnings("unchecked")
+        Set<String> excludes = (Set<String>) f.get(null);
+
+        assertTrue(excludes.containsAll(Arrays.asList(
+                "/user/login", "/user/code", "/actuator/**", "/error", "/internal/**")),
+                "白名单缺失：" + excludes);
+        // /user/list 需要登录态，绝不能被放行（SPEC-07 §5.1）
+        assertFalse(excludes.contains("/user/list"), "批量查用户不得免登录");
+        assertFalse(excludes.contains("/**"), "不得整体放行");
+    }
+}
diff --git a/common/src/test/java/com/hmdp/contract/FeignContractTest.java b/common/src/test/java/com/hmdp/contract/FeignContractTest.java
new file mode 100644
index 0000000..132aafd
--- /dev/null
+++ b/common/src/test/java/com/hmdp/contract/FeignContractTest.java
@@ -0,0 +1,178 @@
+package com.hmdp.contract;
+
+import org.junit.jupiter.api.Test;
+
+import java.io.IOException;
+import java.nio.charset.StandardCharsets;
+import java.nio.file.Files;
+import java.nio.file.Path;
+import java.nio.file.Paths;
+import java.util.ArrayList;
+import java.util.HashMap;
+import java.util.LinkedHashSet;
+import java.util.List;
+import java.util.Map;
+import java.util.Set;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
+import java.util.stream.Stream;
+
+import static org.junit.jupiter.api.Assertions.assertTrue;
+
+/**
+ * Feign ⇄ Controller 契约测试（SPEC-07 G5 / 验收 A4、A5）
+ *
+ * <p>断言一：每个 {@code @FeignClient} 方法都能在目标服务的 {@code @RestController}
+ * 里匹配到「HTTP 方法 + 路径」的唯一映射——杜绝 {@code /user/list} 这类
+ * "调用方按设想编写、提供方从未实现"的死契约。
+ *
+ * <p>断言二：{@code @FeignClient} 接口中不存在 {@code @GetMapping} + {@code @RequestBody}
+ * 的组合——违反 HTTP 语义，多数客户端与代理会丢弃 GET 的 body。本测试是 Feign 契约测试，
+ * 只扫 {@code @FeignClient} 接口，Controller 侧的同类写法不在其范围内。
+ *
+ * <p>纯单元测试，不依赖 Spring 上下文，可入 CI。
+ */
+class FeignContractTest {
+
+    /** @FeignClient(name=...) → 模块目录名 */
+    private static final Map<String, String> SERVICE_MODULE = Map.of(
+            "user-service", "user-service",
+            "voucher-service", "voucher-service",
+            "order-service", "order-service",
+            "shop-service", "shop-service",
+            "social-service", "social-service",
+            "rag-service", "rag-service",
+            "agent-service", "agent-service");
+
+    private static final Pattern FEIGN_CLIENT =
+            Pattern.compile("@FeignClient\\(([^)]*)\\)");
+    private static final Pattern CLASS_MAPPING =
+            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)[^;{]*\\bclass\\b");
+    private static final Pattern METHOD_MAPPING = Pattern.compile(
+            "@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)");
+    private static final Pattern REQUEST_BODY = Pattern.compile("@RequestBody");
+
+    @Test
+    void everyFeignMethodHasAControllerMapping() throws IOException {
+        Path root = repoRoot();
+        List<String> missing = new ArrayList<>();
+
+        for (Path feignFile : feignInterfaces(root)) {
+            String src = Files.readString(feignFile, StandardCharsets.UTF_8);
+            Matcher clientMatcher = FEIGN_CLIENT.matcher(src);
+            if (!clientMatcher.find()) {
+                continue;
+            }
+            String name = attr(clientMatcher.group(1), "name");
+            String module = SERVICE_MODULE.get(name);
+            if (module == null) {
+                missing.add(feignFile + " → 未知服务名 " + name);
+                continue;
+            }
+            Set<String> routes = controllerRoutes(root.resolve(module));
+            for (String[] call : feignCalls(src)) {
+                String key = call[0] + " " + normalize(call[1]);
+                if (!routes.contains(key)) {
+                    missing.add(feignFile.getFileName() + " 的 " + key
+                            + " 在 " + module + " 中无对应 Controller 映射。该服务已有：" + routes);
+                }
+            }
+        }
+
+        assertTrue(missing.isEmpty(), "Feign 契约失配（调用方声明的端点，提供方不存在）：\n" + String.join("\n", missing));
+    }
+
+    @Test
+    void noGetMappingWithRequestBody() throws IOException {
+        List<String> violations = new ArrayList<>();
+        try (Stream<Path> files = Files.walk(repoRoot())) {
+            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
+                    .filter(p -> !p.toString().contains("/target/")).toList()) {
+                String src = Files.readString(f, StandardCharsets.UTF_8);
+                if (!src.contains("@FeignClient")) {
+                    continue;
+                }
+                Matcher m = METHOD_MAPPING.matcher(src);
+                while (m.find()) {
+                    if ("Get".equals(m.group(1))) {
+                        // 只看到本方法声明语句的 ';' 为止：固定 400 字窗口会跨过下一个方法，
+                        // 把 @PostMapping 方法上的 @RequestBody 误判为本 GET 的 body。
+                        // 找不到 ';' 时收敛到文件末尾，绝不放宽窗口——护栏不该 fail-open
+                        int end = src.indexOf(';', m.end());
+                        int bound = end < 0 ? src.length() : end + 1;
+                        String tail = src.substring(m.end(), bound);
+                        if (REQUEST_BODY.matcher(tail).find()) {
+                            violations.add(f.getFileName() + " → " + m.group(0));
+                        }
+                    }
+                }
+            }
+        }
+        assertTrue(violations.isEmpty(), "Feign 存在 @GetMapping + @RequestBody 组合（GET 带 body 会被代理丢弃）：" + violations);
+    }
+
+    private static String attr(String annotationBody, String name) {
+        Matcher m = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"").matcher(annotationBody);
+        return m.find() ? m.group(1) : null;
+    }
+
+    /** 返回该 Feign 接口内每个方法映射的 [HTTP方法, 路径] */
+    private static List<String[]> feignCalls(String src) {
+        List<String[]> calls = new ArrayList<>();
+        Matcher m = METHOD_MAPPING.matcher(src);
+        while (m.find()) {
+            calls.add(new String[]{m.group(1).toUpperCase(), m.group(2)});
+        }
+        return calls;
+    }
+
+    /** 目标模块所有 @RestController 的「HTTP方法 + 全路径」集合 */
+    private static Set<String> controllerRoutes(Path moduleDir) throws IOException {
+        Set<String> routes = new LinkedHashSet<>();
+        Path srcRoot = moduleDir.resolve("src/main/java");
+        if (!Files.exists(srcRoot)) {
+            return routes;
+        }
+        try (Stream<Path> files = Files.walk(srcRoot)) {
+            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
+                String src = Files.readString(f, StandardCharsets.UTF_8);
+                if (!src.contains("@RestController")) {
+                    continue;
+                }
+                Matcher classMapping = CLASS_MAPPING.matcher(src);
+                if (!classMapping.find()) {
+                    continue;
+                }
+                String prefix = classMapping.group(1);
+                Matcher m = METHOD_MAPPING.matcher(src);
+                while (m.find()) {
+                    routes.add(m.group(1).toUpperCase() + " " + normalize(prefix + "/" + m.group(2)));
+                }
+            }
+        }
+        return routes;
+    }
+
+    private static List<Path> feignInterfaces(Path root) throws IOException {
+        List<Path> result = new ArrayList<>();
+        try (Stream<Path> files = Files.walk(root)) {
+            result.addAll(files.filter(p -> p.toString().endsWith(".java"))
+                    .filter(p -> !p.toString().contains("/target/"))
+                    .filter(p -> p.toString().contains("/feign/"))
+                    .toList());
+        }
+        return result;
+    }
+
+    /** 去除重复斜杠、去尾斜杠；路径变量 {id} 原样保留 */
+    private static String normalize(String path) {
+        String p = ("/" + path).replaceAll("/{2,}", "/");
+        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
+    }
+
+    /** common 模块的 user.dir 是 common/，仓库根是上一级 */
+    private static Path repoRoot() {
+        Path dir = Paths.get("").toAbsolutePath();
+        return Files.exists(dir.resolve("common")) ? dir : dir.getParent();
+    }
+}
diff --git a/common/src/test/java/com/hmdp/dto/UserInfoVOTest.java b/common/src/test/java/com/hmdp/dto/UserInfoVOTest.java
new file mode 100644
index 0000000..68eb7a9
--- /dev/null
+++ b/common/src/test/java/com/hmdp/dto/UserInfoVOTest.java
@@ -0,0 +1,41 @@
+package com.hmdp.dto;
+
+import com.hmdp.entity.UserInfo;
+import cn.hutool.core.bean.BeanUtil;
+import org.junit.jupiter.api.Test;
+
+import java.lang.reflect.Field;
+import java.time.LocalDate;
+import java.util.Arrays;
+import java.util.List;
+import java.util.Set;
+import java.util.stream.Collectors;
+
+import static org.junit.jupiter.api.Assertions.*;
+
+class UserInfoVOTest {
+
+    @Test
+    void 不含隐私字段() {
+        Set<String> names = Arrays.stream(UserInfoVO.class.getDeclaredFields())
+                .map(Field::getName).collect(Collectors.toSet());
+        assertEquals(Set.of("userId", "city", "introduce", "fans", "followee", "level"), names);
+    }
+
+    @Test
+    void 投影时隐私字段不会被带出() {
+        UserInfo info = new UserInfo()
+                .setUserId(7L).setCity("上海").setIntroduce("hi")
+                .setFans(3).setFollowee(4).setLevel(true)
+                .setCredits(999).setBirthday(LocalDate.of(1990, 1, 1)).setGender(Boolean.TRUE);
+
+        UserInfoVO vo = BeanUtil.copyProperties(info, UserInfoVO.class);
+
+        assertEquals(7L, vo.getUserId());
+        assertEquals("上海", vo.getCity());
+        assertEquals(3, vo.getFans());
+        assertFalse(Arrays.stream(UserInfoVO.class.getDeclaredFields())
+                .map(Field::getName).toList()
+                .containsAll(List.of("credits", "birthday", "gender")));
+    }
+}
diff --git "a/docs/superpowers/plans/2026-10-07-spec-03-06-07-\344\277\256\345\244\215.md" "b/docs/superpowers/plans/2026-10-07-spec-03-06-07-\344\277\256\345\244\215.md"
index 9996e5f..e67de27 100644
--- "a/docs/superpowers/plans/2026-10-07-spec-03-06-07-\344\277\256\345\244\215.md"
+++ "b/docs/superpowers/plans/2026-10-07-spec-03-06-07-\344\277\256\345\244\215.md"
@@ -55,21 +55,21 @@
 `.env.example` · `docs/superpowers/specs/` 无需改动
 
 **修改（按 Task 顺序）**
 
 `.gitignore` · 8 份 `*/src/main/resources/application.yaml` · `README.md` · 7 个测试文件的凭据常量 · `rag-service/.../InternalRetrievalController.java`（仅挂载点说明，路径不变）· `agent-service/.../feign/RagFeignClient.java` · `agent-service/.../feign/*` 配置 · `user-service/.../controller/UserController.java` · `user-service/.../service/impl/UserServiceImpl.java` · `user-service/.../service/IUserService.java` · `social-service/.../feign/UserFeignClient.java` · `order-service/.../feign/VoucherFeignClient.java` · `order-service/.../fallback/VoucherFeignClientFallback.java`（删除）· `order-service/.../mq/SeckillOrderConsumer.java` · `order-service/.../mq/SeckillOrderProducer.java` · `order-service/.../service/impl/VoucherOrderServiceImpl.java` · `order-service/.../metrics/SeckillMetrics.java` · `order-service/src/main/resources/seckill.lua` · `voucher-service/.../controller/VoucherController.java` · `voucher-service/.../service/IVoucherService.java` · `voucher-service/.../service/impl/VoucherServiceImpl.java` · `shop-service/.../controller/ShopController.java` · `order-service/.../controller/SeckillConsistencyController.java` · `gateway-service/src/main/resources/application.yaml` · `gateway-service/.../filter/AgentRateLimitFilter.java` · `gateway-service/src/test/java/com/hmdp/gateway/route/GatewayRouteContractTest.java` · `common/src/main/java/com/hmdp/config/SaTokenConfig.java` · `common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java` · `common/src/main/java/com/hmdp/utils/RedisConstants.java` · `common/pom.xml` · `order-service/pom.xml`
 
 ---
 
 ### Task 1: 凭据外置与 `.env` 机制
 
-**SPEC-06 G8 / 验收 A9。** 把 8 份配置里的明文口令外置到环境变量，仓库内零命中 `520117`；本地开发经被 gitignore 的 `.env` 提供值，未跟踪文件不被 `git grep` 检索。
+**SPEC-06 G8 / 验收 A9。** 把 8 份配置里的明文口令外置到环境变量，仓库内零命中 `<本地口令>`；本地开发经被 gitignore 的 `.env` 提供值，未跟踪文件不被 `git grep` 检索。
 
 **Files:**
 - Create: `.env.example`（入库，仅键名）
 - Create: `.env`（**不入库**）
 - Modify: `.gitignore`
 - Modify: `README.md:580`
 - Modify: 8 份 `{gateway,shop,voucher,order,social,user,rag,agent}-service/src/main/resources/application.yaml`
 - Test: 7 个既有测试文件的凭据常量
 
 **Interfaces:**
@@ -85,22 +85,22 @@ MYSQL_PASSWORD=
 REDIS_PASSWORD=
 # 内部端点共享密钥（/internal/** 校验，见 SPEC-06 §5.2）
 INTERNAL_TOKEN=
 # 管理员用户 id，逗号分隔（决定 @SaCheckRole("admin") 的判定，见 SPEC-06 §5.3）
 ADMIN_USER_IDS=
 ```
 
 - [ ] **Step 2: 建 `.env`（不入库，填本地真实值）**
 
 ```
-MYSQL_PASSWORD=520117
-REDIS_PASSWORD=520117
+MYSQL_PASSWORD=<本地口令>
+REDIS_PASSWORD=<本地口令>
 INTERNAL_TOKEN=hmdp-internal-dev-token
 ADMIN_USER_IDS=1
 ```
 
 - [ ] **Step 3: `.gitignore` 增加 `.env`**
 
 在文件末尾追加：
 
 ```
 # 本地凭据（SPEC-06 G8）：模板见 .env.example
@@ -110,21 +110,21 @@ ADMIN_USER_IDS=1
 - [ ] **Step 4: 验证 `.env` 确实被忽略**
 
 Run: `git check-ignore -v .env`
 Expected: 输出 `.gitignore:<行号>:.env`
 
 Run: `git status --short | grep "\.env" || echo "OK: .env 未出现在 git status"`
 Expected: `OK: .env 未出现在 git status`
 
 - [ ] **Step 5: 8 份 `application.yaml` 的口令改为环境变量**
 
-对下列每一份文件，把 datasource 的 `password: 520117` 改成 `password: ${MYSQL_PASSWORD:}`，把 redis 的 `password: 520117` 改成 `password: ${REDIS_PASSWORD:}`。**注意保持各自的既有缩进**（例如 user-service 的 redis 块用 5 空格缩进，不要"顺手对齐"）。
+对下列每一份文件，把 datasource 的 `password: <本地口令>` 改成 `password: ${MYSQL_PASSWORD:}`，把 redis 的 `password: <本地口令>` 改成 `password: ${REDIS_PASSWORD:}`。**注意保持各自的既有缩进**（例如 user-service 的 redis 块用 5 空格缩进，不要"顺手对齐"）。
 
 ```
 gateway-service/src/main/resources/application.yaml:68
 shop-service/src/main/resources/application.yaml:12,18
 voucher-service/src/main/resources/application.yaml:12,18
 order-service/src/main/resources/application.yaml:12,18
 social-service/src/main/resources/application.yaml:12,18
 user-service/src/main/resources/application.yaml:12,18
 rag-service/src/main/resources/application.yaml:17
 agent-service/src/main/resources/application.yaml:10,15
@@ -149,100 +149,100 @@ spring:
   config:
     import:
       - optional:file:.env[.properties]
       - optional:file:../.env[.properties]
 ```
 
 > `bootstrap.yaml` 里已有的 `spring.config.import: optional:nacos:...` 是另一个配置文件，两者互不影响。
 
 - [ ] **Step 7: `README.md:580` 移除明文口令**
 
-把 `      password: 520117` 一行改为：
+把 `      password: <本地口令>` 一行改为：
 
 ```yaml
-      password: ${MYSQL_PASSWORD:}   # 见 .env.example
+      password: ${REDIS_PASSWORD:}   # 见 .env.example
 ```
 
 - [ ] **Step 8: 7 个测试文件的凭据常量改读环境变量并在缺失时跳过**
 
 对下列 6 个 agent-service 测试 + 1 个 gateway-service 测试：
 
 **(a) `agent-service/src/test/java/com/hmdp/agent/it/OrderRefundAtomicDbIT.java:32`、`it/RefundIdempotencyDbIT.java:32`、`it/SessionP1FeaturesDbIT.java:31`** —— 把
 
 ```java
-    private static final String PASS = "520117";
+    private static final String PASS = "<本地口令>";
 ```
 
 改为
 
 ```java
     private static final String PASS = System.getenv("MYSQL_PASSWORD");
 ```
 
 并在各自 `@BeforeAll static void setup()` 的**第一行**加：
 
 ```java
         org.junit.jupiter.api.Assumptions.assumeTrue(PASS != null && !PASS.isBlank(),
                 "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
 ```
 
 **(b) `agent-service/src/test/java/com/hmdp/agent/parity/ParityTestBase.java:34`** —— 把
 
 ```java
-    protected static final String DB_PWD = "520117";
+    protected static final String DB_PWD = "<本地口令>";
 ```
 
 改为
 
 ```java
     protected static final String DB_PWD = System.getenv("MYSQL_PASSWORD");
 ```
 
 并在 `@BeforeAll static void initBizDb()` 的**第一行**加：
 
 ```java
         org.junit.jupiter.api.Assumptions.assumeTrue(DB_PWD != null && !DB_PWD.isBlank(),
                 "未设置 MYSQL_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
 ```
 
 **(c) `agent-service/src/test/java/com/hmdp/agent/memory/ChatMemoryServiceTest.java:40` 与 `planner/FlowStateServiceTest.java:34`** —— 把
 
 ```java
-            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "520117");
+            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "<本地口令>");
 ```
 
 改为
 
 ```java
             String pwd = System.getenv("REDIS_PASSWORD");
             org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
                     "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
 ```
 
 **(d) `gateway-service/src/test/java/com/hmdp/gateway/limit/AgentTokenBucketLimiterIT.java:31`** —— 把
 
 ```java
-            factory.setPassword("520117");
+            factory.setPassword("<本地口令>");
 ```
 
 改为
 
 ```java
             String pwd = System.getenv("REDIS_PASSWORD");
             org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
                     "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
             factory.setPassword(pwd);
 ```
 
 - [ ] **Step 9: 验证仓库内零明文口令**
 
-Run: `git grep -n "520117"`
+Run: `git grep -nE '52011[0-9]'`
 Expected: **零命中**（exit code 1）
 
 - [ ] **Step 10: 编译与回归**
 
 Run: `mvn -o -T 1C -DskipTests compile`
 Expected: exit 0
 
 Run: `mvn -o -pl agent-service test`
 Expected: 与改动前同样的结果（210 tests，含已知的 1 项预存失败 `SessionSnapshotServiceTest.快照固化_读回一致率百分之百`）。未设环境变量时依赖中间件的用例**跳过**而非失败。
 
@@ -2667,21 +2667,21 @@ class UserServiceImplSendCodeTest {
     @InjectMocks private UserServiceImpl userService;
 
     @Test
     void 手机号格式错误直接拒绝() {
         Result r = userService.sendCode("123", null, "127.0.0.1");
         assertFalse(r.getSuccess());
         assertEquals("手机格式错误", r.getErrorMsg());
     }
 
     @Test
-    void 60秒内重复发送被拒绝() {
+    void 六十秒内重复发送被拒绝() {
         when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
         when(valueOperations.setIfAbsent(eq("login:code:limit:phone:13800138000"), anyString(),
                 anyLong(), any(TimeUnit.class))).thenReturn(false);
 
         Result r = userService.sendCode("13800138000", null, "127.0.0.1");
 
         assertFalse(r.getSuccess());
         assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
     }
 
@@ -2993,21 +2993,21 @@ Run: `mvn -o -pl user-service test`
 Expected: 全绿（`UserServiceImplSendCodeTest` 5）
 
 Run: `mvn -o -pl gateway-service test`
 Expected: 全绿（未设凭据时 Redis IT 跳过）
 
 Run: `mvn -o -pl agent-service test`
 Expected: 与基线一致——210 tests 中 1 项预存失败（`SessionSnapshotServiceTest.快照固化_读回一致率百分之百`，MyBatis-Plus lambda cache，与本次无关）
 
 - [ ] **Step 3: 凭据零明文（SPEC-06 A9，必须实测）**
 
-Run: `git grep -n "520117"`
+Run: `git grep -nE '52011[0-9]'`
 Expected: **零命中**
 
 - [ ] **Step 4: 拉起中间件与业务服务**
 
 按 `e2e-env-bringup` 配方：Docker Desktop → `hmdp-redis`(6379) + `hmdp-rocketmq-namesrv`/`broker`(4.9.4, 9876/10909/10911)；Nacos standalone（8848，**同时占用 8080**）；MySQL 8.0.42 原生 3306。
 
 Java 必须用 21（PATH 上的 `java` 是 17）：
 
 ```bash
 set -a; source .env; set +a
diff --git "a/docs/superpowers/specs/2026-10-07-spec-03-06-07-\344\277\256\345\244\215-design.md" "b/docs/superpowers/specs/2026-10-07-spec-03-06-07-\344\277\256\345\244\215-design.md"
index 6ef3be3..18c20e8 100644
--- "a/docs/superpowers/specs/2026-10-07-spec-03-06-07-\344\277\256\345\244\215-design.md"
+++ "b/docs/superpowers/specs/2026-10-07-spec-03-06-07-\344\277\256\345\244\215-design.md"
@@ -243,28 +243,28 @@ public class SaTokenConfig implements WebMvcConfigurer {
 - 超限返回 `Result.fail("验证码发送过于频繁，请稍后再试")`。
 - **移除** `UserServiceImpl.java:67` 的 `log.debug("发送短信验证码成功，验证码:{}", code)`。
 - IP 取自 `X-Forwarded-For` 首段，回退 `request.getRemoteAddr()`。
 
 ### B7. CORS
 
 `gateway-service/application.yaml` 的 `allowed-origin-patterns: '*'` 改为显式白名单 `http://localhost:8080,http://127.0.0.1:8080`（前端 nginx 端口）。`allow-credentials: true` 保留——移除通配符后该组合才合法。
 
 ### B8. 凭据外置
 
-- 8 份服务 `application.yaml`：`password: 520117` → `password: ${MYSQL_PASSWORD:}` / `password: ${REDIS_PASSWORD:}`。
+- 8 份服务 `application.yaml`：`password: <本地口令>` → `password: ${MYSQL_PASSWORD:}` / `password: ${REDIS_PASSWORD:}`。
 - 8 份服务 `application.yaml` 增加 `spring.config.import: optional:file:.env[.properties],optional:file:../.env[.properties]`——两个相对路径分别覆盖"从仓库根 `java -jar`"与"从模块目录 `mvn -pl x test`"两种工作目录。
 - 新增入库的 `.env.example`（仅键名，无值）：`MYSQL_PASSWORD=`、`REDIS_PASSWORD=`、`INTERNAL_TOKEN=`、`ADMIN_USER_IDS=`。
 - `.gitignore` 增加 `.env`。未跟踪文件不被 `git grep` 检索，故 A9 成立。
 - 环境变量优先级高于 `spring.config.import` 引入的文件，`export` 仍可覆盖 `.env`。
 - `README.md:580` 移除明文密码展示。
-- 7 个测试文件中的 `"520117"` 字面量改为读环境变量，读不到时 `Assumptions` 跳过（D5）。
-- **SPEC-06 §5.7 的"轮换已泄露的 520117"是运维动作，不在代码交付范围**，在交付文档中以操作项列出。
+- 7 个测试文件中的 `"<本地口令>"` 字面量改为读环境变量，读不到时 `Assumptions` 跳过（D5）。
+- **SPEC-06 §5.7 的"轮换已泄露的该口令"是运维动作，不在代码交付范围**，在交付文档中以操作项列出。
 
 ### B9. `AgentRateLimitFilter`
 
 未登录/无 `loginId` 时保持 `chain.filter(exchange)` 放行，补注释说明：未登录流量紧接着由 `SaTokenGatewayConfig` 前置拦截，此处放行是纵深防御缺口而非漏洞（与需求方确认的行为）。
 
 ---
 
 ## 6. Workstream C — SPEC-07 Feign 契约与服务间调用
 
 ### C1. 补齐 `/user/list`（G1、G2）
@@ -358,45 +358,108 @@ public class FeignTokenRelayConfig {
 ### 7.2 全栈端到端（必须实测项）
 
 按 `e2e-env-bringup` 配方拉起中间件与业务服务后执行：
 
 | 断言 | 来源 | 方法 |
 |------|------|------|
 | A2 库存守恒 | SPEC-03 | 初始 Redis=100、DB=100，200 并发下单，断言成功订单恰好 100、两侧库存恰好 0、无重复订单 |
 | A4 MQ 故障注入 | SPEC-03 | 停 RocketMQ，断言返回 `Result.fail` 且 Redis 库存与用户标记已回滚 |
 | A1 直连 401 | SPEC-06 | `curl http://localhost:8084/voucher-order/my` 不带 token → 401（修复前是 200 + 业务体） |
 | A2 内部端点隔离 | SPEC-06 | `curl -X PUT http://localhost:8083/voucher/seckill/1/stock/` → 404/401，且调用后 DB 库存不变 |
-| A9 零明文密码 | SPEC-06 | `git grep -n "520117"` 零命中 |
+| A9 零明文密码 | SPEC-06 | `git grep -nE '52011[0-9]'` 零命中 |
 | A2 点赞排行榜 | SPEC-07 | 3 用户点赞后 `GET /blog/likes/1` 返回 3 个含 `nickName`/`icon` 的对象（修复前 400/500） |
 | A3 共同关注 | SPEC-07 | A 关注 X、Y，B 关注 Y、Z → `GET /follow/common/{B}` 返回 `[Y]` |
 | A7 秒杀经内部端点 | SPEC-07 | 拦截器开启后 `deductStock` 经 `/internal/voucher/...` + 内部密钥仍可调用 |
 
 > **陷阱**：网关鉴权过滤器在**路由之前**短路，未登录时对任意路径返回 HTTP 200 + `{"success":false}`。所有可达性验收**必须带 token**，否则会得到假绿。
 
 ### 7.3 收尾
 
 本次只提交代码与文档，不改动工作区中既有的无关未暂存删除（`docs/dev-plans/**`、`docs/superpowers/plans/**` 等），也不新增 `docs/specs/` 的跟踪状态。
 
 ---
 
 ## 8. 风险与残留
 
 | 项 | 说明 | 处置 |
 |----|------|------|
 | Redis Set 幂等非崩溃安全 | Redis 在"标记写入"与"DB 扣减"之间被 flush/重启，会退化为重复扣减 | 已记录。彻底解法是 DB 幂等表，属 SPEC-04/08 范畴 |
 | `@SaCheckRole` 注解链 | 依赖 `SaInterceptor.preHandle` 的注解鉴权行为 | 实现阶段以测试锁定，失效则退化为主法首行显式 `checkRole` |
 | `INTERNAL_TOKEN` 未配置 | 安全默认是全部拒绝 `/internal/**` | `.env.example` + README 明确告知；未配置时秒杀链路会失败（可见、可诊断） |
 | agent-service 预存失败 | `SessionSnapshotServiceTest.快照固化_读回一致率百分之百` 恒定失败（MyBatis-Plus lambda cache，纯 Mockito 环境） | 与本次无关，原样记录不修 |
-| 密码轮换 | `520117` 已进入 git 历史，必须视为已泄露 | 代码侧外置后，以运维操作项列出，不由本次执行 |
+| 密码轮换 | `<本地口令>` 已进入 git 历史，必须视为已泄露 | 代码侧外置后，以运维操作项列出，不由本次执行 |
 | `seckill:order:queue` 无消费者 | Lua 写入但全仓无读取方 | 不在任何 SPEC 内，记录不修 |
 | 环境不可用项 | 全栈 E2E 依赖 Docker Desktop + Nacos + RocketMQ 全部就绪 | 按需求方选择：先完成自动化测试，全栈实测待中间件确认后执行；不可用项标"待环境"+ 复现命令，不虚构执行结果 |
+| MQ 同步发送超时的库存漂移 | `syncSend` 超时 3s。broker 实际已收下消息、但发送调用超时，入口按"未发出"处理并回滚 Redis 预扣，消费者却照常消费成功——Redis 库存比应有值多 1，该场次 `Redis 库存 + 成功订单数 == 100` 不成立；DB 库存仍正确。`(voucherId, orderId)` 幂等键覆盖不到，因为两侧操作的是不同存储 | 已记录。属"入口与消费者的判定不一致"残留，需靠对账补偿收敛，归 SPEC-04 范畴 |
 
 ---
 
 ## 9. 验收标准映射
 
 | SPEC | 验收项 | 本设计覆盖 |
 |------|--------|-----------|
 | SPEC-03 | A1–A8、A10 | A1/A2（`deductStock` 去 Redis、幂等）、A3（syncSend + 回滚）、A4（Lua 返回 3 + 指标）、A5/A6（消费者错误语义 + 幂等）、A7（删 fallback）、A8（删未用注入 + 判空）、A10（编译与测试）；另含 §A7 契约冻结（README §2.1 派给 SPEC-03） |
 | SPEC-03 | A9 | 取"或 SPEC-11"分支：`createVoucherOrder` 留给 SPEC-11，未使用 `redissonClient` 注入已删 |
 | SPEC-06 | A1–A12 | A1/A7（`SaTokenConfig` + 401）、A2（内部端点迁移 + 密钥）、A3（`/internal/**` 密钥校验）、A4（`@SaCheckRole`）、A5（`UserInfoVO` 脱敏）、A6（限流 + 去日志）、A8（CORS）、A9（凭据外置）、A10（白名单不变）、A11（agent-service 回归）、A12（既有越权防护不动） |
 | SPEC-07 | A1–A11 | A1（`/user/list`）、A2/A3（点赞榜 + 共同关注）、A4/A5（契约测试）、A6（token 透传）、A7（内部端点链路）、A8（删 fallback）、A9（N+1 消除）、A10（agent 回归）、A11（编译） |
+
+---
+
+## 10. 实测记录
+
+> 本节记录 14 个任务实施完毕后的**真实执行结果**。已执行的写实测值；需全栈中间件的写复现步骤并标 **未执行 / 待环境**。口径：不虚构任何未跑过的结果。
+
+### 10.1 已实测（真实执行结果）
+
+| # | 命令 | 实测结果 |
+|---|------|---------|
+| M1 | `mvn -o -T 1C -DskipTests compile` | exit 0，10 个模块全部 `SUCCESS` |
+| M2 | `mvn -o -pl common test` | 16 run / 0 failures / 0 errors / 0 skipped |
+| M3 | `mvn -o -pl order-service test` | 15 run / 0 / 0 / 0 —— `SeckillVoucherServiceTest` 7、`SeckillOrderConsumerTest` 7、`QueryMyOrdersBatchTest` 1 |
+| M4 | `mvn -o -pl voucher-service test` | 4 run / 0 / 0 / 0 |
+| M5 | `mvn -o -pl user-service test` | 5 run / 0 / 0 / 0 |
+| M6 | `mvn -o -pl gateway-service test` | 5 run / 0 / 0 / 0 |
+| M7 | `mvn -o -pl agent-service test` | 203 run / **1 failure** / 0 errors / 6 skipped |
+| M8 | `git grep -nE '52011[0-9]'` | 零命中（exit 1）——SPEC-06 A9 通过 |
+
+M7 的唯一失败是**预存失败、与本批无关**：`SessionSnapshotServiceTest.快照固化_读回一致率百分之百`，纯 Mockito 环境下 MyBatis-Plus lambda cache 触发的问题（§8 已记录）。它在本次实施开始前即失败，不属 SPEC-03/06/07 任一范围。
+
+M2/M3/M4/M5/M6 全绿覆盖本批新增断言：秒杀入口四种 Lua 返回分支与发送失败回滚（§7.1）、消费者错误语义、`FeignContractTest`、`/internal/**` 无路由断言。
+
+### 10.2 待环境（未实测）
+
+以下均依赖 Docker 中间件 + Nacos + 8 个业务服务，本次**未启动全栈**，一律标 **未执行 / 待环境**。按 §7.2 配方拉起后逐条执行。
+
+| 项 | 来源 | 断言 | 复现步骤 |
+|----|------|------|---------|
+| A2 库存守恒 | SPEC-03 | Redis 初始 100、DB 初始 100，200 并发下单后成功订单恰好 100、两侧库存恰好 0、无用户持有两单。即 `Redis 库存 + 成功订单数 == 100` 且 `DB stock + 成功订单数 == 100` | 全栈起后重置该场次（`SET seckill:stock:{vid} 100`、`UPDATE tb_seckill_voucher SET stock=100`），用 200 并发请求打 `POST /voucher-order/seckill/{vid}`（每请求一个独立登录用户），再统计：成功单数、`GET seckill:stock:{vid}`、`SELECT stock FROM tb_seckill_voucher`、`SELECT user_id,COUNT(*) FROM tb_voucher_order WHERE voucher_id={vid} GROUP BY user_id HAVING COUNT(*)>1`（应为空）。**未执行 / 待环境** |
+| A4 MQ 故障注入 | SPEC-03 | 停 RocketMQ 后下单，响应必须是失败，且 Redis 库存与用户标记回滚到调用前值 | 先记录 `seckill:stock:{vid}` 与 `SISMEMBER seckill:order:{vid} {uid}`；`docker stop` RocketMQ 容器；`POST /voucher-order/seckill/{vid}` 单个请求；断言返回 `Result.fail("系统繁忙，请稍后重试")`，且两个 Redis 值等于调用前记录值。**未执行 / 待环境** |
+| A1 直连 401 | SPEC-06 | 不带 token `curl` 业务服务端口须返回 HTTP **401**（而非 200 + 业务错误体） | `curl -i http://localhost:8084/voucher-order/my`（直连 order-service，不经网关），断言响应首行含 `401`；对照修复前的 200 + `{"success":false}`。**未执行 / 待环境** |
+| A2 内部端点隔离 | SPEC-06 | 旧公开路径 `PUT /voucher/seckill/{id}/stock` 不再可用，调用后 DB 库存不变 | 记录 `SELECT stock FROM tb_seckill_voucher WHERE voucher_id=1`；`curl -i -X PUT 'http://localhost:8083/voucher/seckill/1/stock?orderId=1'` 不带 `X-Internal-Token`，断言非 200（401/404）；再次查库断言 stock 未变。**未执行 / 待环境** |
+| A2 点赞排行榜 | SPEC-07 | 3 个用户点赞同一博客后，排行端点返回 3 个含 `nickName`/`icon` 的用户对象 | 3 个独立账号各 `PUT /blog/like/1`；`GET /blog/likes/1`，断言数组长度 3 且每项 `nickName`、`icon` 均非空（修复前因 `/user/list` 不存在返回 400/500）。**未执行 / 待环境** |
+| A3 共同关注 | SPEC-07 | 两用户关注集合交集正确 | A 关注 X、Y；B 关注 Y、Z；`GET /follow/common/{B}`（带 A 的 token），断言返回且仅返回 `[Y]`。**未执行 / 待环境** |
+| A7 秒杀经内部端点 | SPEC-07 | 登录拦截器生效后，扣减仍能经 `/internal/voucher/...` + 共享密钥调用 | 断言秒杀链路在 B1 拦截器启用后仍端到端成功（A2 库存守恒跑通即隐含此项）；或单独 `curl -i -X PUT 'http://localhost:8083/internal/voucher/seckill/1/stock?orderId=1' -H "X-Internal-Token: $INTERNAL_TOKEN"`，断言 200。**未执行 / 待环境** |
+
+### 10.3 起全栈的关键前提（否则会得出错误结论）
+
+1. **中间件不是可选项、也不会自动就绪**：Redis、RocketMQ **4.9.4**、Nacos（standalone）、MySQL 8 必须全部先起，再启动服务。
+2. **Java 必须是 21**。本机 `PATH` 上的 `java` 是 **17**，而构建目标为 class file major 65（`java.version=21`，§1 基线）；服务须用 Java 21 二进制显式启动。本机 `JAVA_HOME=D:\Config\java` 指向 21.0.7，`PATH` 上的 `java` 却是 `.jdks/ms-17.0.18`——用 `JAVA_HOME` 或绝对路径，勿依赖 `which java`。
+3. **启动服务前必须加载 `.env`**：`set -a; source .env; set +a`。否则 `INTERNAL_TOKEN` 为空，按安全默认所有 `/internal/**` 返回 401，秒杀链路必然失败（§8 已列）。
+4. **`.env` 的 `ADMIN_USER_IDS` 必须设为你登录用户的 id**，"管理员得 200"那半才会通过。当前值为 `1`（已核对），新注册测试用户不会被命中——403 那半仍通过，200 那半不通过。
+5. **网关鉴权过滤器在路由之前短路**：不带 token 时对**任意**路径（含无路由路径）都返回 HTTP 200 + `{"success":false,"errorMsg":"未登录，请先登录"}`。因此任何不带 token 的可达性检查都是**假绿**，测路由可达性必须带 token（与 §7.2 陷阱同源）。
+
+### 10.4 已知残留
+
+- **`syncSend` 3 秒超时**：broker 已收下消息但发送调用超时，入口按"未发出"回滚 Redis 预扣，消费者却照常消费成功——该场次 Redis 库存多 1、`Redis 库存 + 成功订单数 == 100` 不成立，DB 库存仍正确。见 §8，彻底消除归 SPEC-04。
+- **幂等标记在 DB 事务之外**：Redis `SADD` 与 DB 扣减之间崩溃会造成静默少卖 1 件。选 Redis Set 方案时已接受（§3 D1）。
+- **`releaseUserMark` 自身失败仅记日志**：此时消息已被 ACK，预扣既未消费也未恢复，无观测。
+- **验证码 IP 维度信任 `X-Forwarded-For` 首段**：伪造该头可获全新额度。每手机号限额不受影响，仍是真正控制项（§5 B6）。
+- **`/user/info/{id}` 脱敏但仍无归属校验**：已隐藏 credits/birthday/gender，登录用户仍可枚举他人 `city`/`introduce`/`fans`/`followee`/`level`。满足 SPEC-06 §5.4 的"或 仅本人/管理员可见"条款，建议另开跟进单。
+- **`docs/specs/`（11 份源 SPEC）未纳入跟踪**（已核对：`git ls-files docs/specs/` 为 0，12 个未跟踪文件），其中 SPEC-06 多处含明文口令。A9 只因 `git grep` 不检索未跟踪文件才通过——一旦提交该目录，A9 立即失败。
+- **根 `pom.xml` 未设 `project.build.sourceEncoding`**（已核对缺失）：本平台 `javac` 默认 GBK，而仓库有大量中文测试方法名。构建当前通过，列为可移植性风险。
+- **全栈端到端未验证**：三项 P0 秒杀修复目前仅由单测与代码审查证明。
+
+### 10.5 一处值得点名的复核结论
+
+终审时有人提出：`queryMyOrders` 对 Feign 返回体做的 `(Map<?, ?>)` 强转会抛 `ClassCastException`，导致生产环境券字段恒为 null。**该主张被驳回**：`Result.data` 声明为 `Object`（已核对 `common/.../utils/Result.java:15`），Jackson 反序列化后是 `LinkedHashMap`，强转正确；且该模式在 N+1 重构之前就存在。
+
+> 但**只有端到端实跑能证伪这段推理**——单测里 Feign 被 mock，走的不是 Jackson 反序列化路径。下次拉起全栈时，应把"下单后 `GET /voucher-order/my` 返回的券标题/面额非空"作为一项显式检查（即 A9 N+1 消除的正确性断言，§7.2 未单列，此处补记）。
diff --git a/gateway-service/src/main/java/com/hmdp/gateway/filter/AgentRateLimitFilter.java b/gateway-service/src/main/java/com/hmdp/gateway/filter/AgentRateLimitFilter.java
index 52b28ea..7d3357c 100644
--- a/gateway-service/src/main/java/com/hmdp/gateway/filter/AgentRateLimitFilter.java
+++ b/gateway-service/src/main/java/com/hmdp/gateway/filter/AgentRateLimitFilter.java
@@ -33,24 +33,27 @@ public class AgentRateLimitFilter implements GlobalFilter, Ordered {
     private final AgentTokenBucketLimiter limiter;
 
     @Override
     public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
         String path = exchange.getRequest().getPath().value();
         if (!path.startsWith(AGENT_PREFIX)) {
             return chain.filter(exchange);
         }
         String token = exchange.getRequest().getHeaders().getFirst("Authorization");
         if (token == null || token.isBlank()) {
-            return chain.filter(exchange); // 未登录由 Sa-Token 鉴权拦截，此处不重复处理
+            // 有意放行（SPEC-06 §1.8）：未登录流量紧接着会被 SaTokenGatewayConfig 在本过滤器之后拒绝，
+            // 此处不重复处理。属已知的纵深防御缺口，非漏洞。
+            return chain.filter(exchange);
         }
         Object loginId = StpUtil.getLoginIdByToken(token);
         if (loginId == null) {
+            // 同上：无效 token 由 SaTokenGatewayConfig 拦截
             return chain.filter(exchange);
         }
         if (limiter.tryAcquire("agent:rl:" + loginId)) {
             return chain.filter(exchange);
         }
         ServerHttpResponse response = exchange.getResponse();
         response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
         response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
         DataBuffer buffer = response.bufferFactory().wrap(RATE_LIMIT_BODY.getBytes(StandardCharsets.UTF_8));
         return response.writeWith(Mono.just(buffer));
diff --git a/gateway-service/src/main/resources/application.yaml b/gateway-service/src/main/resources/application.yaml
index 8ba15c5..83accba 100644
--- a/gateway-service/src/main/resources/application.yaml
+++ b/gateway-service/src/main/resources/application.yaml
@@ -1,16 +1,21 @@
 # 服务器配置
 server:
   port: 8081 # 网关服务端口，所有外部请求统一通过此端口进入
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 云服务配置
   cloud:
     # 网关配置
     gateway:
       # 路由规则配置
       routes:
         # 用户服务路由
         - id: user-route # 路由唯一标识
           uri: lb://user-service # 目标服务URI，lb://表示使用负载均衡，user-service是服务名称
           predicates: # 路由断言，匹配条件
@@ -48,31 +53,33 @@ spring:
         # 智能客服Agent服务路由
         - id: agent-route
           uri: lb://agent-service
           predicates:
             - Path=/agent/**
       # 全局跨域配置
       globalcors:
         add-to-simple-url-handler-mapping: true # 是否将跨域配置添加到SimpleUrlHandlerMapping
         cors-configurations:
           '[/**]': # 匹配所有路径
-            allowed-origin-patterns: '*' # 允许所有来源（使用allowed-origin-patterns以支持通配符）
+            # 显式白名单（SPEC-06 §5.6）：'*' 与 allow-credentials: true 并存是无效且不安全的组合，
+            # Spring 会回显请求 Origin，等同允许任意站点携带用户凭证发起跨域请求
+            allowed-origin-patterns: 'http://localhost:8080,http://127.0.0.1:8080'
 #            allowed-origins: '*' # 允许所有来源
             allowed-headers: '*' # 允许所有请求头
             allowed-methods: '*' # 允许所有请求方法
             allow-credentials: true # 允许携带凭证（如Cookie）
   # Redis 配置（Sa-Token 会话持久化）
   data:
     redis:
       host: localhost
       port: 6379
-      password: 520117
+      password: ${REDIS_PASSWORD:}
       lettuce:
         pool:
           max-active: 10
           max-idle: 5
           min-idle: 0
 
 # Sa-Token 配置
 sa-token:
   token-name: Authorization
   timeout: 2592000
diff --git a/gateway-service/src/test/java/com/hmdp/gateway/limit/AgentTokenBucketLimiterIT.java b/gateway-service/src/test/java/com/hmdp/gateway/limit/AgentTokenBucketLimiterIT.java
index b23e1d9..ea5c2d7 100644
--- a/gateway-service/src/test/java/com/hmdp/gateway/limit/AgentTokenBucketLimiterIT.java
+++ b/gateway-service/src/test/java/com/hmdp/gateway/limit/AgentTokenBucketLimiterIT.java
@@ -21,21 +21,24 @@ import static org.junit.jupiter.api.Assertions.assertTrue;
 class AgentTokenBucketLimiterIT {
 
     private static StringRedisTemplate redis;
     private static AgentTokenBucketLimiter burstLimiter;
     private static AgentTokenBucketLimiter refillLimiter;
 
     @BeforeAll
     static void setup() {
         try {
             LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
-            factory.setPassword("520117");
+            String pwd = System.getenv("REDIS_PASSWORD");
+            org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
+                    "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
+            factory.setPassword(pwd);
             factory.afterPropertiesSet();
             redis = new StringRedisTemplate(factory);
             redis.afterPropertiesSet();
             redis.getConnectionFactory().getConnection().ping();
         } catch (Exception e) {
             redis = null;
         }
         Assumptions.assumeTrue(redis != null, "Redis 离线，跳过限流边界测试");
         DefaultRedisScript<Long> script = new DefaultRedisScript<>();
         script.setLocation(new ClassPathResource("limiter/token-bucket.lua"));
diff --git a/gateway-service/src/test/java/com/hmdp/gateway/route/GatewayRouteContractTest.java b/gateway-service/src/test/java/com/hmdp/gateway/route/GatewayRouteContractTest.java
index a5197a7..738591f 100644
--- a/gateway-service/src/test/java/com/hmdp/gateway/route/GatewayRouteContractTest.java
+++ b/gateway-service/src/test/java/com/hmdp/gateway/route/GatewayRouteContractTest.java
@@ -32,22 +32,25 @@ import static org.junit.jupiter.api.Assertions.assertTrue;
  * </ul>
  *
  * <p>Controller 前缀通过扫描磁盘源码获取，而非类路径反射：gateway-service 的测试类路径只含
  * {@code common}，看不到 order/social/rag 等模块的类，SPEC-01 §8.1 的"类路径扫描"在模块边界上
  * 不可实现。
  *
  * <p>纯单元测试，不依赖 Nacos / Redis / Spring 上下文，可入 CI。
  */
 class GatewayRouteContractTest {
 
-    /** 有意不对网关暴露的内部端点前缀（服务间 Feign 调用专用，见 InternalRetrievalController）。 */
-    private static final Set<String> INTERNAL_PREFIXES = Set.of("/internal/rag");
+    /**
+     * 有意不对网关暴露的内部端点前缀：服务间 Feign 调用专用，由共享密钥拦截器保护。
+     * 新增这类端点（如 {@code /internal/xxx}）时须登记到此白名单，否则断言 B 会误报无路由。
+     */
+    private static final Set<String> INTERNAL_PREFIXES = Set.of("/internal/rag", "/internal/voucher");
 
     /** 仅匹配类级映射：@RequestMapping(...) 与其后的 class 声明之间不含 ; 或 {。 */
     private static final Pattern CLASS_LEVEL_MAPPING = Pattern.compile(
             "@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)[^;{]*\\bclass\\b");
 
     @Test
     void everyRoutePrefixMatchesAController() throws IOException {
         Path root = repoRoot();
         Set<String> routes = routePrefixes(root);
         Set<String> controllers = controllerPrefixes(root);
@@ -99,20 +102,37 @@ class GatewayRouteContractTest {
         for (Path file : files) {
             if (!CLASS_LEVEL_MAPPING.matcher(read(file)).find()) {
                 unparsed.add(root.relativize(file).toString());
             }
         }
 
         assertTrue(unparsed.isEmpty(),
                 "以下 Controller 文件未解析出类级 @RequestMapping（正则可能已失配，需同步调整）：" + unparsed);
     }
 
+    /**
+     * 内部端点不得经网关暴露（SPEC-06 §5.2 方案 C）。
+     * 网关本就没有 /internal/** 路由，此处把该隐式约定变成可回归的显式约束：
+     * 一旦有人新增这样的路由，本测试立即失败。
+     */
+    @Test
+    void noRouteExposesInternalEndpoints() throws IOException {
+        Set<String> routes = routePrefixes(repoRoot());
+
+        List<String> leaked = routes.stream()
+                .filter(r -> covers(r, "/internal"))
+                .toList();
+
+        assertTrue(leaked.isEmpty(),
+                "内部端点不得经网关暴露（SPEC-06 §5.2 C）：" + leaked);
+    }
+
     /** 扫描根定位自检：文件数明显偏少即说明 repoRoot() 找错了目录。 */
     @Test
     void scanFindsTheExpectedControllerFiles() throws IOException {
         Path root = repoRoot();
         List<Path> files = controllerFiles(root);
         assertTrue(files.size() >= 20,
                 "只扫到 " + files.size() + " 个 Controller 文件，疑似扫描根定位错误：" + root);
     }
 
     /** 从 user.dir 向上定位仓库根。surefire 的工作目录是模块目录，故需上溯。 */
diff --git a/order-service/pom.xml b/order-service/pom.xml
index 06d46b5..a990cce 100644
--- a/order-service/pom.xml
+++ b/order-service/pom.xml
@@ -135,20 +135,25 @@
                     <artifactId>rocketmq-client</artifactId>
                 </exclusion>
             </exclusions>
         </dependency>
         <dependency>
             <groupId>org.apache.rocketmq</groupId>
             <artifactId>rocketmq-client</artifactId>
             <version>5.3.1</version>
         </dependency>
 
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
 
     </dependencies>
 
     <build>
         <plugins>
             <plugin>
                 <groupId>org.springframework.boot</groupId>
                 <artifactId>spring-boot-maven-plugin</artifactId>
             </plugin>
         </plugins>
diff --git a/order-service/src/main/java/com/hmdp/order/controller/SeckillConsistencyController.java b/order-service/src/main/java/com/hmdp/order/controller/SeckillConsistencyController.java
index aa297fc..c1127d6 100644
--- a/order-service/src/main/java/com/hmdp/order/controller/SeckillConsistencyController.java
+++ b/order-service/src/main/java/com/hmdp/order/controller/SeckillConsistencyController.java
@@ -1,20 +1,22 @@
 package com.hmdp.order.controller;
 
+import cn.dev33.satoken.annotation.SaCheckRole;
 import com.hmdp.dto.Result;
 import com.hmdp.order.service.ISeckillConsistencyService;
 import org.springframework.web.bind.annotation.*;
 
 import jakarta.annotation.Resource;
 
 @RestController
 @RequestMapping("/seckill/consistency")
+@SaCheckRole("admin")
 public class SeckillConsistencyController {
 
     @Resource
     private ISeckillConsistencyService consistencyService;
 
     @GetMapping("/order/{orderId}")
     public Result checkOrderConsistency(@PathVariable Long orderId) {
         return consistencyService.checkOrderConsistency(orderId);
     }
 
diff --git a/order-service/src/main/java/com/hmdp/order/fallback/VoucherFeignClientFallback.java b/order-service/src/main/java/com/hmdp/order/fallback/VoucherFeignClientFallback.java
deleted file mode 100644
index e702e9c..0000000
--- a/order-service/src/main/java/com/hmdp/order/fallback/VoucherFeignClientFallback.java
+++ /dev/null
@@ -1,23 +0,0 @@
-package com.hmdp.order.fallback;
-
-import com.hmdp.dto.Result;
-import com.hmdp.order.feign.VoucherFeignClient;
-import lombok.extern.slf4j.Slf4j;
-import org.springframework.stereotype.Component;
-
-@Component
-@Slf4j
-public class VoucherFeignClientFallback implements VoucherFeignClient {
-
-    @Override
-    public Result deductStock(Long voucherId) {
-        log.warn("voucher-service服务不可用，扣减库存降级处理: voucherId={}", voucherId);
-        return Result.fail("库存服务暂时不可用，订单将异步处理");
-    }
-
-    @Override
-    public Result getVoucherById(Long voucherId) {
-        log.warn("voucher-service服务不可用，查询优惠券降级处理: voucherId={}", voucherId);
-        return Result.fail("优惠券服务暂时不可用");
-    }
-}
diff --git a/order-service/src/main/java/com/hmdp/order/feign/VoucherFeignClient.java b/order-service/src/main/java/com/hmdp/order/feign/VoucherFeignClient.java
index ef2367f..6753524 100644
--- a/order-service/src/main/java/com/hmdp/order/feign/VoucherFeignClient.java
+++ b/order-service/src/main/java/com/hmdp/order/feign/VoucherFeignClient.java
@@ -1,18 +1,30 @@
 package com.hmdp.order.feign;
 
+import com.hmdp.config.FeignTokenRelayConfig;
+import com.hmdp.config.InternalTokenFeignConfig;
 import com.hmdp.dto.Result;
-import com.hmdp.order.fallback.VoucherFeignClientFallback;
 import org.springframework.cloud.openfeign.FeignClient;
-import org.springframework.web.bind.annotation.GetMapping;
 import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PostMapping;
 import org.springframework.web.bind.annotation.PutMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestParam;
 
-@FeignClient(name = "voucher-service", fallback = VoucherFeignClientFallback.class)
+import java.util.List;
+
+@FeignClient(name = "voucher-service",
+        configuration = {InternalTokenFeignConfig.class, FeignTokenRelayConfig.class})
 public interface VoucherFeignClient {
 
-    @PutMapping("voucher/seckill/{id}/stock")
-    Result deductStock(@PathVariable("id") Long voucherId);
+    /**
+     * 扣减库存（内部端点，走 X-Internal-Token；SPEC-03 §5.4 的 orderId 为幂等键）
+     */
+    @PutMapping("/internal/voucher/seckill/{id}/stock")
+    Result deductStock(@PathVariable("id") Long voucherId, @RequestParam("orderId") Long orderId);
 
-    @GetMapping("voucher/{id}")
-    Result getVoucherById(@PathVariable("id") Long voucherId);
+    /**
+     * 批量查询券详情（消除 queryMyOrders 的 N+1）
+     */
+    @PostMapping("/voucher/batch")
+    Result getVouchersByIds(@RequestBody List<Long> ids);
 }
diff --git a/order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java b/order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java
index dfedcc3..ee9d9c6 100644
--- a/order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java
+++ b/order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java
@@ -20,20 +20,21 @@ public class SeckillMetrics {
     private MeterRegistry meterRegistry;
 
     @Resource
     private StringRedisTemplate stringRedisTemplate;
 
     private Counter seckillRequestCounter;
     private Counter seckillSuccessCounter;
     private Counter seckillFailCounter;
     private Counter stockInsufficientCounter;
     private Counter duplicateOrderCounter;
+    private Counter redisStockMissingCounter;
     private Timer seckillLatencyTimer;
     private Counter mqSendSuccessCounter;
     private Counter mqSendFailCounter;
     private Counter mqConsumeSuccessCounter;
     private Counter mqConsumeFailCounter;
 
     private final AtomicLong pendingOrderCount = new AtomicLong(0);
 
     @PostConstruct
     public void init() {
@@ -55,20 +56,25 @@ public class SeckillMetrics {
         stockInsufficientCounter = Counter.builder("seckill.stock.insufficient")
                 .description("库存不足次数")
                 .tag("reason", "stock_insufficient")
                 .register(meterRegistry);
 
         duplicateOrderCounter = Counter.builder("seckill.duplicate.order")
                 .description("重复下单次数")
                 .tag("reason", "duplicate_order")
                 .register(meterRegistry);
 
+        redisStockMissingCounter = Counter.builder("seckill.stock.key.missing")
+                .description("Redis 库存key缺失次数（需预热，与库存不足区分）")
+                .tag("reason", "redis_stock_key_missing")
+                .register(meterRegistry);
+
         seckillLatencyTimer = Timer.builder("seckill.latency")
                 .description("秒杀请求延迟")
                 .register(meterRegistry);
 
         mqSendSuccessCounter = Counter.builder("seckill.mq.send.success")
                 .description("MQ发送成功数")
                 .tag("type", "mq_send")
                 .register(meterRegistry);
 
         mqSendFailCounter = Counter.builder("seckill.mq.send.fail")
@@ -106,20 +112,24 @@ public class SeckillMetrics {
     }
 
     public void incrementStockInsufficient() {
         stockInsufficientCounter.increment();
     }
 
     public void incrementDuplicateOrder() {
         duplicateOrderCounter.increment();
     }
 
+    public void incrementRedisStockMissing() {
+        redisStockMissingCounter.increment();
+    }
+
     public Timer.Sample startTimer() {
         return Timer.start(meterRegistry);
     }
 
     public void recordLatency(Timer.Sample sample) {
         sample.stop(seckillLatencyTimer);
     }
 
     public void incrementMqSendSuccess() {
         mqSendSuccessCounter.increment();
diff --git a/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java b/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java
index 72bbc76..aeb5492 100644
--- a/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java
+++ b/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java
@@ -6,20 +6,21 @@ import com.hmdp.dto.SeckillOrderMessage;
 import com.hmdp.entity.VoucherOrder;
 import com.hmdp.order.feign.VoucherFeignClient;
 import com.hmdp.order.mapper.VoucherOrderMapper;
 import com.hmdp.order.metrics.SeckillMetrics;
 import com.hmdp.utils.RedisConstants;
 import lombok.extern.slf4j.Slf4j;
 import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
 import org.apache.rocketmq.spring.core.RocketMQListener;
 import org.redisson.api.RLock;
 import org.redisson.api.RedissonClient;
+import org.springframework.dao.DuplicateKeyException;
 import org.springframework.data.redis.core.StringRedisTemplate;
 import org.springframework.stereotype.Component;
 
 import jakarta.annotation.Resource;
 import java.util.concurrent.TimeUnit;
 
 /**
  * 秒杀订单消息消费者
  * 
  * 负责消费秒杀资格校验通过后产生的订单消息，完成最终的下单操作。
@@ -82,72 +83,87 @@ public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessag
 
         log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}, retryCount={}",
                 orderId, userId, voucherId, message.getRetryCount());
 
         String lockKey = "lock:order:" + orderId;
         RLock lock = redissonClient.getLock(lockKey);
 
         try {
             boolean locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
             if (!locked) {
-                log.warn("获取订单锁失败，可能正在处理中: orderId={}", orderId);
-                return;
+                // 不能静默 ACK：return 在 RocketMQ 语义下等于"消费成功"，
+                // 消息不会重投、订单永久丢失且 Redis 预扣不回滚（SPEC-03 §1.4）
+                throw new IllegalStateException("获取订单锁失败，触发重试: orderId=" + orderId);
             }
 
             try {
                 VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
                 if (existingOrder != null) {
                     log.info("订单已存在，跳过处理: orderId={}", orderId);
                     seckillMetrics.incrementMqConsumeSuccess();
                     return;
                 }
 
                 Long count = voucherOrderMapper.selectCount(
                         new LambdaQueryWrapper<VoucherOrder>()
                                 .eq(VoucherOrder::getUserId, userId)
                                 .eq(VoucherOrder::getVoucherId, voucherId)
                 );
                 if (count > 0) {
                     log.warn("用户已购买过该优惠券，一人一单校验失败: userId={}, voucherId={}", userId, voucherId);
-                    rollbackRedisData(voucherId, userId);
+                    releaseUserMark(voucherId, userId);
                     seckillMetrics.incrementMqConsumeFail();
                     return;
                 }
 
-                Result deductResult = voucherFeignClient.deductStock(voucherId);
-                if (!deductResult.getSuccess()) {
-                    log.error("扣减库存失败: voucherId={}, result={}", voucherId, deductResult.getErrorMsg());
-                    rollbackRedisData(voucherId, userId);
+                Result deductResult;
+                try {
+                    deductResult = voucherFeignClient.deductStock(voucherId, orderId);
+                } catch (Exception e) {
+                    // 内部端点不可达/网络异常：属于"应该重试"，不能当作业务失败丢弃（SPEC-03 §1.7）
+                    log.error("调用库存扣减失败，触发重试: voucherId={}, orderId={}", voucherId, orderId, e);
+                    throw new RuntimeException("库存服务调用失败", e);
+                }
+                if (deductResult == null || !Boolean.TRUE.equals(deductResult.getSuccess())) {
+                    log.warn("扣减库存业务失败: voucherId={}, orderId={}, result={}",
+                            voucherId, orderId, deductResult == null ? null : deductResult.getErrorMsg());
+                    releaseUserMark(voucherId, userId);
                     seckillMetrics.incrementMqConsumeFail();
                     return;
                 }
 
                 VoucherOrder voucherOrder = new VoucherOrder();
                 voucherOrder.setId(orderId);
                 voucherOrder.setUserId(userId);
                 voucherOrder.setVoucherId(voucherId);
                 voucherOrder.setStatus(1);
 
-                int insertResult = voucherOrderMapper.insert(voucherOrder);
-                if (insertResult > 0) {
+                try {
+                    int insertResult = voucherOrderMapper.insert(voucherOrder);
+                    if (insertResult <= 0) {
+                        seckillMetrics.incrementMqConsumeFail();
+                        throw new RuntimeException("订单插入失败: orderId=" + orderId);
+                    }
+                } catch (DuplicateKeyException e) {
+                    // tb_voucher_order 的 uk_user_voucher（SPEC-02）拦下的并发重复：
+                    // 幂等跳过，不重试。库存已在 (voucherId, orderId) 幂等保护下只扣一次。
+                    log.warn("并发重复订单，幂等跳过: orderId={}, userId={}, voucherId={}",
+                            orderId, userId, voucherId);
                     seckillMetrics.incrementMqConsumeSuccess();
-                    log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
-                    stringRedisTemplate.opsForHash().delete(
-                            RedisConstants.SECKILL_STOCK_KEY + "order:detail:" + voucherId,
-                            orderId.toString()
-                    );
-                } else {
-                    log.error("订单插入失败: orderId={}", orderId);
-                    seckillMetrics.incrementMqConsumeFail();
-                    throw new RuntimeException("订单插入失败");
+                    return;
                 }
 
+                seckillMetrics.incrementMqConsumeSuccess();
+                log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
+                stringRedisTemplate.opsForHash().delete(
+                        RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId, orderId.toString());
+
             } finally {
                 if (lock.isHeldByCurrentThread()) {
                     lock.unlock();
                 }
             }
 
         } catch (InterruptedException e) {
             log.error("获取锁被中断: orderId={}", orderId, e);
             Thread.currentThread().interrupt();
             seckillMetrics.incrementMqConsumeFail();
@@ -158,28 +174,28 @@ public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessag
 
             if (message.getRetryCount() >= MAX_RETRY_COUNT) {
                 log.error("订单处理重试次数已达上限，需要人工干预: orderId={}, retryCount={}",
                         orderId, message.getRetryCount());
             }
             throw new RuntimeException("订单处理失败", e);
         }
     }
 
     /**
-     * 回滚Redis预扣数据
-     * 
-     * 在消息消费失败时调用，用于恢复Redis中的库存和用户购买记录。
-     * 保证Redis预扣数据与数据库最终状态的一致性。
-     * 
-     * @param voucherId 优惠券ID
-     * @param userId 用户ID
+     * 消费侧业务失败回滚（SPEC-03 §5.6）：只移除用户标记。
+     *
+     * <p>库存**不恢复**——原实现的无条件 INCR 会在该用户此前已成功下单的场景下
+     * 凭空多出库存，是超卖的潜在来源（SPEC-03 §1.6）。
+     *
+     * <p>已知取舍：本分支仅在 Redis 的 {@code seckill:order:{vid}} 集合丢失后可达
+     * （否则 Lua 的 SISMEMBER 已在入口拦下）。此情形下保留 DECR 会让 Redis 库存偏少，
+     * 方向上是少卖而非超卖，属安全侧。
      */
-    private void rollbackRedisData(Long voucherId, Long userId) {
+    private void releaseUserMark(Long voucherId, Long userId) {
         try {
-            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
-            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
-            log.info("Redis数据回滚成功: voucherId={}, userId={}", voucherId, userId);
+            stringRedisTemplate.opsForSet().remove(
+                    RedisConstants.SECKILL_ORDER_SET_KEY + voucherId, userId.toString());
         } catch (Exception e) {
-            log.error("Redis数据回滚失败: voucherId={}, userId={}, error={}", voucherId, userId, e.getMessage(), e);
+            log.error("移除用户秒杀标记失败: voucherId={}, userId={}", voucherId, userId, e);
         }
     }
 }
diff --git a/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java b/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java
index e6de538..f871f30 100644
--- a/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java
+++ b/order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java
@@ -33,51 +33,54 @@ public class SeckillOrderProducer {
 
     public static final String TOPIC_SECKILL_ORDER_DLQ = "seckill-order-dlq-topic";
 
     public static final String TOPIC_STOCK_SYNC = "stock-sync-topic";
 
     @Resource
     private RocketMQTemplate rocketMQTemplate;
 
     /**
      * 同步发送秒杀订单消息
-     * 
+     *
      * 适用于需要立即确认发送结果的场景，保证消息可靠性。
      * 如果发送失败，会立即返回false，调用方可以相应处理。
-     * 
+     * <p>秒杀主链路必须使用本方法：只有它能让调用方在失败时回滚 Redis 预扣并返回失败。
+     *
      * @param message 秒杀订单消息
      * @return true-发送成功，false-发送失败
      */
     public boolean sendSeckillOrderMessage(SeckillOrderMessage message) {
         try {
             rocketMQTemplate.syncSend(
                     TOPIC_SECKILL_ORDER,
                     MessageBuilder.withPayload(message).build(),
                     3000
             );
             log.info("秒杀订单消息发送成功: orderId={}, userId={}, voucherId={}",
                     message.getOrderId(), message.getUserId(), message.getVoucherId());
             return true;
         } catch (Exception e) {
             log.error("秒杀订单消息发送失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
             return false;
         }
     }
 
     /**
-     * 异步发送秒杀订单消息
-     * 
-     * 适用于高并发场景，不阻塞主线程，通过回调函数处理发送结果。
-     * 即使发送失败，也不会影响用户秒杀资格（Redis已预扣库存）。
-     * 
+     * 异步发送秒杀订单消息（**不保证投递**）
+     *
+     * <p>返回值语义是"提交成功"，**不代表发送成功**——真正的失败被吞在 onException 回调里，
+     * 调用方无法据此决策。因此本方法**不得**用于秒杀主链路，主链路必须使用
+     * {@link #sendSeckillOrderMessage}（syncSend），否则 Redis 已预扣而消息未投递，
+     * 用户会拿到一个永不兑现的 orderId（SPEC-03 §1.3）。
+     *
      * @param message 秒杀订单消息
-     * @return true-提交成功（不代表发送成功），false-提交失败
+     * @return true-已提交（不代表已投递），false-提交失败
      */
     public boolean sendSeckillOrderMessageAsync(SeckillOrderMessage message) {
         try {
             rocketMQTemplate.asyncSend(
                     TOPIC_SECKILL_ORDER,
                     MessageBuilder.withPayload(message).build(),
                     new org.apache.rocketmq.client.producer.SendCallback() {
                         @Override
                         public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                             log.info("秒杀订单消息异步发送成功: orderId={}, msgId={}",
diff --git a/order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java b/order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java
index ab4198f..1f45ad8 100644
--- a/order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java
+++ b/order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java
@@ -32,21 +32,21 @@ public class SeckillConsistencyServiceImpl implements ISeckillConsistencyService
     @Resource
     private VoucherFeignClient voucherFeignClient;
 
     @Resource
     private RedissonClient redissonClient;
 
     @Override
     public Result checkOrderConsistency(Long orderId) {
         log.info("开始检查订单一致性: orderId={}", orderId);
 
-        String orderDetailKey = "seckill:order:detail:";
+        String orderDetailKey = RedisConstants.SECKILL_ORDER_DETAIL_KEY;
         Map<Object, Object> orderInfo = stringRedisTemplate.opsForHash().entries(orderDetailKey);
 
         VoucherOrder dbOrder = voucherOrderMapper.selectById(orderId);
 
         if (dbOrder != null && orderInfo.isEmpty()) {
             log.info("订单一致性检查通过: orderId={}, 数据库存在，Redis已清理", orderId);
             return Result.ok("订单一致性正常");
         }
 
         if (dbOrder == null && !orderInfo.isEmpty()) {
diff --git a/order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java b/order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java
index cfe5c0a..afbd75f 100644
--- a/order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java
+++ b/order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java
@@ -1,45 +1,49 @@
 package com.hmdp.order.service.impl;
 
 import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
 import com.baomidou.mybatisplus.core.toolkit.Wrappers;
 import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
 import cn.hutool.core.bean.BeanUtil;
 import com.hmdp.order.dto.OrderQueryVO;
 import com.hmdp.dto.Result;
 import com.hmdp.dto.RefundMessages;
 import com.hmdp.dto.SeckillOrderMessage;
+import com.hmdp.dto.UserDTO;
 import com.hmdp.entity.Voucher;
 import com.hmdp.entity.VoucherOrder;
 import com.hmdp.order.feign.VoucherFeignClient;
 import com.hmdp.order.mapper.VoucherOrderMapper;
 import com.hmdp.order.metrics.SeckillMetrics;
 import com.hmdp.order.mq.SeckillOrderProducer;
 import com.hmdp.order.service.IVoucherOrderService;
+import com.hmdp.utils.RedisConstants;
 import com.hmdp.utils.RedisIdWorker;
 import com.hmdp.utils.UserHolder;
 import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
 import io.micrometer.core.instrument.Timer;
 import io.seata.spring.annotation.GlobalTransactional;
 import lombok.extern.slf4j.Slf4j;
-import org.redisson.api.RedissonClient;
 import org.springframework.core.io.ClassPathResource;
 import org.springframework.data.redis.core.StringRedisTemplate;
 import org.springframework.data.redis.core.script.DefaultRedisScript;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Transactional;
 
 import jakarta.annotation.Resource;
 import java.time.LocalDateTime;
+import java.util.ArrayList;
+import java.util.Collections;
+import java.util.HashMap;
 import java.util.List;
 import java.util.Map;
-import java.util.Collections;
+import java.util.Objects;
 
 /**
  * 优惠券订单服务实现类
  * 
  * 负责处理优惠券秒杀的核心业务逻辑，包括：
  * 1. 秒杀资格校验（通过Lua脚本保证原子性）
  * 2. 异步订单处理（通过消息队列削峰）
  * 3. 秒杀指标监控（记录成功/失败等关键指标）
  * 
  * 采用"Redis预扣库存 + MQ异步下单"的架构，保证高并发下的系统稳定性和数据最终一致性。
@@ -49,23 +53,20 @@ import java.util.Collections;
 public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
 
     @Resource
     private VoucherFeignClient voucherFeignClient;
 
     @Resource
     private RedisIdWorker redisIdWorker;
     @Resource
     private StringRedisTemplate stringRedisTemplate;
 
-    @Resource
-    private RedissonClient redissonClient;
-
     @Resource
     private SeckillOrderProducer seckillOrderProducer;
 
     @Resource
     private SeckillMetrics seckillMetrics;
 
     private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
     static {
         SECKILL_SCRIPT = new DefaultRedisScript<>();
         SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
@@ -84,81 +85,116 @@ public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, Vou
      * 
      * @param voucherId 优惠券ID
      * @return Result 包含订单ID（成功）或错误信息（失败）
      */
     @Override
     public Result seckillVoucher(Long voucherId) {
         Timer.Sample timerSample = seckillMetrics.startTimer();
         seckillMetrics.incrementSeckillRequest();
 
         try {
-            Long userId = UserHolder.getUser().getId();
+            UserDTO user = UserHolder.getUser();
+            if (user == null) {
+                seckillMetrics.incrementSeckillFail();
+                return Result.fail("未登录，请先登录");
+            }
+            Long userId = user.getId();
 
             long orderId = redisIdWorker.nextId("order");
 
-            Long result = stringRedisTemplate.execute(
+            Long scriptResult = stringRedisTemplate.execute(
                     SECKILL_SCRIPT,
                     Collections.emptyList(),
                     voucherId.toString(), userId.toString(), String.valueOf(orderId));
 
+            // 脚本返回 nil（Redis 异常）按 key 缺失处理，避免拆箱 NPE
+            long result = scriptResult == null ? 3L : scriptResult;
+
             if (result != 0) {
                 seckillMetrics.incrementSeckillFail();
                 if (result == 1) {
                     seckillMetrics.incrementStockInsufficient();
                     log.warn("秒杀失败-库存不足: userId={}, voucherId={}", userId, voucherId);
                     return Result.fail("库存不足");
-                } else {
+                } else if (result == 2) {
                     seckillMetrics.incrementDuplicateOrder();
                     log.warn("秒杀失败-重复下单: userId={}, voucherId={}", userId, voucherId);
                     return Result.fail("不能重复下单");
+                } else {
+                    // result == 3：seckill:stock:{voucherId} 不存在，需预热；与真实"库存不足"区分
+                    seckillMetrics.incrementRedisStockMissing();
+                    log.error("秒杀失败-Redis库存key缺失，需预热: voucherId={}", voucherId);
+                    return Result.fail("系统繁忙，请稍后重试");
                 }
             }
 
             SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);
 
-            boolean sendSuccess = seckillOrderProducer.sendSeckillOrderMessageAsync(message);
-            if (sendSuccess) {
-                seckillMetrics.incrementMqSendSuccess();
-            } else {
+            // 同步发送（SPEC-03 §5.2 方案 A）：asyncSend 的返回值只代表"提交成功"，
+            // 真正的失败被吞在回调里，用户会拿到一个永不兑现的 orderId
+            if (!seckillOrderProducer.sendSeckillOrderMessage(message)) {
+                rollbackSeckillReservation(voucherId, userId, orderId);
                 seckillMetrics.incrementMqSendFail();
-                log.warn("消息发送失败，但Redis已预扣库存，订单将异步处理: orderId={}", orderId);
+                seckillMetrics.incrementSeckillFail();
+                log.error("秒杀订单消息发送失败，已回滚Redis预扣: orderId={}, userId={}, voucherId={}",
+                        orderId, userId, voucherId);
+                return Result.fail("系统繁忙，请稍后重试");
             }
 
+            seckillMetrics.incrementMqSendSuccess();
             seckillMetrics.incrementSeckillSuccess();
-            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
-
+            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}",
+                    orderId, userId, voucherId);
             return Result.ok(orderId);
         } finally {
             seckillMetrics.recordLatency(timerSample);
         }
     }
 
+    /**
+     * 入口侧回滚（SPEC-03 §5.6）：库存恢复 + 移除用户标记 + 删除订单明细。
+     * 三者同源，必须一起回滚——任一遗漏都会让用户被永久标记"已购买"或库存凭空少 1。
+     */
+    private void rollbackSeckillReservation(Long voucherId, Long userId, Long orderId) {
+        try {
+            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
+            stringRedisTemplate.opsForSet().remove(
+                    RedisConstants.SECKILL_ORDER_SET_KEY + voucherId, userId.toString());
+            stringRedisTemplate.opsForHash().delete(
+                    RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId, orderId.toString());
+        } catch (Exception e) {
+            // 回滚失败无法在同一请求内自愈，记录待人工核对（补偿能力归 SPEC-04）
+            log.error("回滚秒杀预扣失败，需人工核对: voucherId={}, userId={}, orderId={}",
+                    voucherId, userId, orderId, e);
+        }
+    }
+
     /**
      * 创建优惠券订单（供传统同步流程使用）
      * 
      * 注意：此方法在秒杀场景中已由异步流程替代，仅保留用于兼容传统调用。
      * 方法通过分布式事务（Seata）保证数据库操作和库存扣减的一致性。
      * 
      * @param voucherOrder 优惠券订单实体
      */
     @GlobalTransactional(name = "createVoucherOrder", rollbackFor = Exception.class)
     @Transactional
     public void createVoucherOrder(VoucherOrder voucherOrder) {
         Long userId = UserHolder.getUser().getId();
 
         Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
         if (count > 0) {
             log.error("用户已经购买过一次！");
             return;
         }
 
-        Result result = voucherFeignClient.deductStock(voucherOrder.getVoucherId());
+        Result result = voucherFeignClient.deductStock(voucherOrder.getVoucherId(), voucherOrder.getId());
         if (!result.getSuccess()) {
             log.error("库存不足！");
             return;
         }
 
         save(voucherOrder);
     }
 
     /**
      * 按 userId 批量查询订单（agent-service 客服工具调用）
@@ -180,37 +216,54 @@ public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, Vou
         }
         if (days != null && days > 0) {
             wrapper.ge(VoucherOrder::getCreateTime, LocalDateTime.now().minusDays(days));
         }
 
         // 项目未配置 MP 分页插件，手动分页（count + limit/offset）
         long total = count(wrapper.clone());
         wrapper.last("LIMIT " + s + " OFFSET " + (long) (p - 1) * s);
         List<VoucherOrder> records = list(wrapper);
 
-        // 联查券信息，组装 VO（订单字段 + 券标题/金额），一次返回避免 agent 侧二次调用
-        List<OrderQueryVO> vos = records.stream().map(order -> {
-            OrderQueryVO vo = OrderQueryVO.of(order);
+        // 联查券信息，组装 VO：先按 voucherId 去重后**一次**批量拉取，避免逐条远程调用（N+1）
+        List<OrderQueryVO> vos = new ArrayList<>();
+        if (!records.isEmpty()) {
+            List<Long> voucherIds = records.stream()
+                    .map(VoucherOrder::getVoucherId)
+                    .filter(Objects::nonNull)
+                    .distinct()
+                    .toList();
+
+            Map<Long, Voucher> voucherMap = new HashMap<>();
             try {
-                Result voucherResult = voucherFeignClient.getVoucherById(order.getVoucherId());
-                if (voucherResult.getSuccess() && voucherResult.getData() != null) {
-                    Voucher voucher = BeanUtil.mapToBean((Map<?, ?>) voucherResult.getData(), Voucher.class, false, null);
+                Result voucherResult = voucherFeignClient.getVouchersByIds(voucherIds);
+                if (voucherResult.getSuccess() && voucherResult.getData() instanceof List<?> list) {
+                    for (Object item : list) {
+                        Voucher v = BeanUtil.mapToBean((Map<?, ?>) item, Voucher.class, false, null);
+                        voucherMap.put(v.getId(), v);
+                    }
+                }
+            } catch (Exception e) {
+                // 券信息联查失败不阻塞订单返回（降级：仅订单字段）
+                log.warn("批量联查券信息失败: voucherIds={}", voucherIds, e);
+            }
+
+            for (VoucherOrder order : records) {
+                OrderQueryVO vo = OrderQueryVO.of(order);
+                Voucher voucher = voucherMap.get(order.getVoucherId());
+                if (voucher != null) {
                     vo.setVoucherTitle(voucher.getTitle());
                     vo.setPayValue(voucher.getPayValue());
                     vo.setActualValue(voucher.getActualValue());
                 }
-            } catch (Exception e) {
-                // 券信息联查失败不阻塞订单返回（降级：仅订单字段）
-                log.warn("联查券信息失败: voucherId={}", order.getVoucherId(), e);
+                vos.add(vo);
             }
-            return vo;
-        }).toList();
+        }
 
         Result r = Result.ok(vos);
         r.setTotal(total);
         return r;
     }
 
     /**
      * 退款受理（FR-08 第二道闸门，T4.3/T4.4）
      * 双闸门语义：agent confirm 接口为第一道（actionId/归属/时效），本接口独立复核为最终裁决——
      * 原子 UPDATE ... WHERE status=2（已支付未核销）防并发漏单；影响 0 行返回具体原因
diff --git a/order-service/src/main/resources/application.yaml b/order-service/src/main/resources/application.yaml
index e382f32..c70bb3e 100644
--- a/order-service/src/main/resources/application.yaml
+++ b/order-service/src/main/resources/application.yaml
@@ -1,28 +1,33 @@
 # 服务器配置
 server:
   port: 8084 # 服务端口，用于接收HTTP请求
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 数据库配置
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
     url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
     username: root # 数据库用户名
-    password: 520117 # 数据库密码
+    password: ${MYSQL_PASSWORD:} # 数据库密码
   # Redis 配置（统一使用 spring.redis 路径）
   data:
     redis:
      host: localhost  # Redis 主机地址，统一为本地地址
      port: 6379 # Redis 端口，默认 6379
-     password: 520117 # Redis 密码
+     password: ${REDIS_PASSWORD:} # Redis 密码
     # Redis 连接池配置（使用 Lettuce 客户端）
      lettuce:
       pool:
         max-active: 10 # 最大活跃连接数
         max-idle: 10 # 最大空闲连接数
         min-idle: 1 # 最小空闲连接数
         time-between-eviction-runs: 10s # 连接池空闲连接检测周期
   # Jackson JSON 处理配置
   jackson:
     default-property-inclusion: non_null # JSON序列化时忽略值为null的字段
@@ -111,28 +116,31 @@ management:
     health:
       show-details: always
     prometheus:
       enabled: true
   metrics:
     tags:
       application: order-service
 
 # Feign配置
 feign:
-  circuitbreaker:
-    enabled: true
   client:
     config:
       default:
         connectTimeout: 5000
         readTimeout: 5000
 
+# 内部端点共享密钥（SPEC-06 §5.2）
+hmdp:
+  internal-token: ${INTERNAL_TOKEN:}
+  admin-user-ids: ${ADMIN_USER_IDS:}
+
 # Sa-Token 配置
 sa-token:
   token-name: Authorization
   timeout: 2592000
   active-timeout: -1
   is-concurrent: true
   is-share: true
   token-style: uuid
   is-log: false
 
diff --git a/order-service/src/main/resources/seckill.lua b/order-service/src/main/resources/seckill.lua
index 520889b..1af437d 100644
--- a/order-service/src/main/resources/seckill.lua
+++ b/order-service/src/main/resources/seckill.lua
@@ -1,31 +1,35 @@
 -- 优惠券秒杀Lua脚本
 -- 实现库存预检和一人一单校验的原子操作
--- 返回值: 0-成功, 1-库存不足, 2-重复下单
+-- 返回值: 0-成功, 1-库存不足, 2-重复下单, 3-库存key缺失(需预热)
+--
+-- 【库存 owner 约定】seckill:stock:{voucherId} 的扣减权只属于本脚本（order-service 是秒杀流量入口）。
+-- voucher-service 只扣 DB 库存，不得再操作该 key——两侧同时扣减会造成 2 倍速消耗（SPEC-03 §1.2）。
 --
 -- 此脚本在秒杀流程中起到核心作用，保证在高并发场景下库存扣减和一人一单校验的原子性。
 -- 通过Redis单线程执行特性，避免了并发导致的超卖和重复购买问题。
--- 脚本执行成功后，会将订单信息写入Redis队列，供下游消费者异步处理，实现秒杀流量的削峰填谷。
+-- 脚本执行成功后，订单消息由 Java 端同步发送到 MQ，供下游消费者异步处理，实现秒杀流量的削峰填谷。
 
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
-    return 1
+    -- 分开返回：key 缺失是运维态（Redis 重启/flush/过期），与真实"库存不足"必须可区分
+    return 3
 end
 
 -- 2. 检查库存是否充足
 if stock <= 0 then
     return 1
 end
 
 -- 3. 检查是否重复下单（一人一单）
 if redis.call('SISMEMBER', orderKey, userId) == 1 then
     return 2
diff --git a/order-service/src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java b/order-service/src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java
new file mode 100644
index 0000000..ade7b04
--- /dev/null
+++ b/order-service/src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java
@@ -0,0 +1,147 @@
+package com.hmdp.order.mq;
+
+import com.hmdp.dto.Result;
+import com.hmdp.dto.SeckillOrderMessage;
+import com.hmdp.entity.VoucherOrder;
+import com.hmdp.order.feign.VoucherFeignClient;
+import com.hmdp.order.mapper.VoucherOrderMapper;
+import com.hmdp.order.metrics.SeckillMetrics;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.mockito.InjectMocks;
+import org.mockito.Mock;
+import org.mockito.junit.jupiter.MockitoExtension;
+import org.mockito.junit.jupiter.MockitoSettings;
+import org.mockito.quality.Strictness;
+import org.redisson.api.RLock;
+import org.redisson.api.RedissonClient;
+import org.springframework.dao.DuplicateKeyException;
+import org.springframework.data.redis.core.HashOperations;
+import org.springframework.data.redis.core.SetOperations;
+import org.springframework.data.redis.core.StringRedisTemplate;
+
+import java.util.concurrent.TimeUnit;
+
+import static org.junit.jupiter.api.Assertions.*;
+import static org.mockito.ArgumentMatchers.*;
+import static org.mockito.Mockito.*;
+
+@ExtendWith(MockitoExtension.class)
+@MockitoSettings(strictness = Strictness.LENIENT)
+class SeckillOrderConsumerTest {
+
+    @Mock private VoucherOrderMapper voucherOrderMapper;
+    @Mock private VoucherFeignClient voucherFeignClient;
+    @Mock private StringRedisTemplate stringRedisTemplate;
+    @Mock private RedissonClient redissonClient;
+    @Mock private RLock rLock;
+    @Mock private SeckillMetrics seckillMetrics;
+    @Mock private SetOperations<String, String> setOperations;
+    @Mock private HashOperations<String, Object, Object> hashOperations;
+    @InjectMocks private SeckillOrderConsumer consumer;
+
+    private final SeckillOrderMessage msg = new SeckillOrderMessage(9001L, 7L, 1L);
+
+    @Test
+    void 获取锁失败时抛出异常触发重试_而非静默ACK丢弃() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
+
+        assertThrows(RuntimeException.class, () -> consumer.onMessage(msg));
+    }
+
+    @Test
+    void 扣库存抛Feign异常时抛出触发重试_不当作业务失败丢弃() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
+        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(voucherFeignClient.deductStock(1L, 9001L))
+                .thenThrow(new RuntimeException("voucher-service 不可达"));
+
+        assertThrows(RuntimeException.class, () -> consumer.onMessage(msg));
+    }
+
+    @Test
+    void 扣库存返回库存不足时_移除用户标记但不恢复库存_且不重试() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
+        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.fail("库存不足"));
+
+        assertDoesNotThrow(() -> consumer.onMessage(msg));
+
+        verify(setOperations).remove("seckill:order:1", "7");
+        // 关键：不得恢复库存——原实现的无条件 INCR 会凭空造出库存（超卖源）
+        verify(stringRedisTemplate, never()).opsForValue();
+    }
+
+    @Test
+    void 一人一单冲突时_只移除用户标记不恢复库存() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
+        when(voucherOrderMapper.selectCount(any())).thenReturn(1L);
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+
+        assertDoesNotThrow(() -> consumer.onMessage(msg));
+
+        verify(setOperations).remove("seckill:order:1", "7");
+        verify(stringRedisTemplate, never()).opsForValue();
+        verify(voucherFeignClient, never()).deductStock(anyLong(), anyLong());
+    }
+
+    @Test
+    void 插入撞唯一索引时幂等跳过不重试() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
+        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
+        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());
+        when(voucherOrderMapper.insert(any(VoucherOrder.class)))
+                .thenThrow(new DuplicateKeyException("uk_user_voucher"));
+
+        assertDoesNotThrow(() -> consumer.onMessage(msg));
+
+        verify(seckillMetrics).incrementMqConsumeSuccess();
+    }
+
+    @Test
+    void 订单已存在时幂等跳过() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(new VoucherOrder());
+
+        assertDoesNotThrow(() -> consumer.onMessage(msg));
+
+        verify(voucherFeignClient, never()).deductStock(anyLong(), anyLong());
+        verify(seckillMetrics).incrementMqConsumeSuccess();
+    }
+
+    @Test
+    void 成功路径删除的明细键与脚本写入端一致() throws Exception {
+        when(redissonClient.getLock(anyString())).thenReturn(rLock);
+        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
+        when(rLock.isHeldByCurrentThread()).thenReturn(true);
+        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
+        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
+        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());
+        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
+        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
+
+        consumer.onMessage(msg);
+
+        // seckill.lua:16 写的是 seckill:order:detail:{voucherId}；
+        // 原实现用 SECKILL_STOCK_KEY + "order:detail:" 拼成 seckill:stock:order:detail:，
+        // 删的是永不存在的 key，明细 hash 永久残留（SPEC-03 §A7）
+        verify(hashOperations).delete("seckill:order:detail:1", "9001");
+    }
+}
diff --git a/order-service/src/test/java/com/hmdp/order/service/QueryMyOrdersBatchTest.java b/order-service/src/test/java/com/hmdp/order/service/QueryMyOrdersBatchTest.java
new file mode 100644
index 0000000..0ee2d10
--- /dev/null
+++ b/order-service/src/test/java/com/hmdp/order/service/QueryMyOrdersBatchTest.java
@@ -0,0 +1,84 @@
+package com.hmdp.order.service;
+
+import com.hmdp.dto.Result;
+import com.hmdp.entity.VoucherOrder;
+import com.hmdp.order.dto.OrderQueryVO;
+import com.hmdp.order.feign.VoucherFeignClient;
+import com.hmdp.order.mapper.VoucherOrderMapper;
+import com.hmdp.order.service.impl.VoucherOrderServiceImpl;
+import org.junit.jupiter.api.BeforeEach;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.mockito.InjectMocks;
+import org.mockito.Mock;
+import org.mockito.junit.jupiter.MockitoExtension;
+import org.mockito.junit.jupiter.MockitoSettings;
+import org.mockito.quality.Strictness;
+import org.springframework.test.util.ReflectionTestUtils;
+
+import java.util.List;
+import java.util.Map;
+
+import static org.junit.jupiter.api.Assertions.*;
+import static org.mockito.ArgumentMatchers.*;
+import static org.mockito.Mockito.*;
+
+/**
+ * SPEC-07 A9：{@code queryMyOrders} 每页只做**一次**批量券查询。
+ *
+ * <p>契约测试只证明两侧路径能对上，证明不了"一次批量"而非"逐条 N+1"——
+ * 本类把它变成自动化断言。
+ */
+@ExtendWith(MockitoExtension.class)
+@MockitoSettings(strictness = Strictness.LENIENT)
+class QueryMyOrdersBatchTest {
+
+    @Mock private VoucherOrderMapper voucherOrderMapper;
+    @Mock private VoucherFeignClient voucherFeignClient;
+    @InjectMocks private VoucherOrderServiceImpl service;
+
+    @BeforeEach
+    void stubPage() {
+        // ServiceImpl 的 baseMapper 由 MyBatis-Plus 在容器内注入，纯 Mockito 环境下为 null，
+        // 需手工回填——否则 count()/list() 走 baseMapper 时 NPE（不改生产代码）
+        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
+
+        // 3 条订单，共享 2 个 voucherId（1、2）——N+1 实现会是 3 次调用
+        when(voucherOrderMapper.selectCount(any())).thenReturn(3L);
+        when(voucherOrderMapper.selectList(any()))
+                .thenReturn(List.of(order(9001L, 1L), order(9002L, 2L), order(9003L, 1L)));
+        when(voucherFeignClient.getVouchersByIds(any()))
+                .thenReturn(Result.ok(List.of(voucherMap(1L, "券A"), voucherMap(2L, "券B"))));
+    }
+
+    @Test
+    void 分页查询每页只发一次批量券请求_且不再逐条调用() {
+        Result r = service.queryMyOrders(7L, null, null, null, 1, 5);
+
+        assertTrue(r.getSuccess());
+        assertEquals(3L, r.getTotal());
+        @SuppressWarnings("unchecked")
+        List<OrderQueryVO> vos = (List<OrderQueryVO>) r.getData();
+        assertEquals(3, vos.size());
+        assertEquals("券A", vos.get(0).getVoucherTitle());
+
+        // A9 核心：去重后一次批量（voucherIds=[1,2]），而不是每条订单一次。
+        // 单发方法 getVoucherById 已按 SPEC-07 从契约中删除，故逐条调用在编译期即不可表达；
+        // 这里补一条总调用次数断言，锁死"每页恰好一次远程调用"。
+        verify(voucherFeignClient, times(1)).getVouchersByIds(List.of(1L, 2L));
+        verifyNoMoreInteractions(voucherFeignClient);
+    }
+
+    private static VoucherOrder order(Long id, Long voucherId) {
+        VoucherOrder o = new VoucherOrder();
+        o.setId(id);
+        o.setUserId(7L);
+        o.setVoucherId(voucherId);
+        o.setStatus(2);
+        return o;
+    }
+
+    private static Map<String, Object> voucherMap(Long id, String title) {
+        return Map.of("id", id, "title", title, "payValue", 100L, "actualValue", 1000L);
+    }
+}
diff --git a/order-service/src/test/java/com/hmdp/order/service/SeckillVoucherServiceTest.java b/order-service/src/test/java/com/hmdp/order/service/SeckillVoucherServiceTest.java
new file mode 100644
index 0000000..c0d34e5
--- /dev/null
+++ b/order-service/src/test/java/com/hmdp/order/service/SeckillVoucherServiceTest.java
@@ -0,0 +1,134 @@
+package com.hmdp.order.service;
+
+import com.hmdp.dto.Result;
+import com.hmdp.dto.UserDTO;
+import com.hmdp.order.metrics.SeckillMetrics;
+import com.hmdp.order.mq.SeckillOrderProducer;
+import com.hmdp.order.service.impl.VoucherOrderServiceImpl;
+import com.hmdp.utils.RedisIdWorker;
+import com.hmdp.utils.UserHolder;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.BeforeEach;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.mockito.InjectMocks;
+import org.mockito.Mock;
+import org.mockito.junit.jupiter.MockitoExtension;
+import org.mockito.junit.jupiter.MockitoSettings;
+import org.mockito.quality.Strictness;
+import org.springframework.data.redis.core.HashOperations;
+import org.springframework.data.redis.core.SetOperations;
+import org.springframework.data.redis.core.StringRedisTemplate;
+import org.springframework.data.redis.core.ValueOperations;
+
+import static org.junit.jupiter.api.Assertions.*;
+import static org.mockito.ArgumentMatchers.*;
+import static org.mockito.Mockito.*;
+
+@ExtendWith(MockitoExtension.class)
+@MockitoSettings(strictness = Strictness.LENIENT)
+class SeckillVoucherServiceTest {
+
+    @Mock private StringRedisTemplate stringRedisTemplate;
+    @Mock private RedisIdWorker redisIdWorker;
+    @Mock private SeckillOrderProducer seckillOrderProducer;
+    @Mock private SeckillMetrics seckillMetrics;
+    @Mock private ValueOperations<String, String> valueOperations;
+    @Mock private SetOperations<String, String> setOperations;
+    @Mock private HashOperations<String, Object, Object> hashOperations;
+    @InjectMocks private VoucherOrderServiceImpl service;
+
+    @BeforeEach
+    void login() {
+        UserDTO user = new UserDTO();
+        user.setId(7L);
+        UserHolder.saveUser(user);
+        when(redisIdWorker.nextId("order")).thenReturn(9001L);
+    }
+
+    @AfterEach
+    void logout() {
+        UserHolder.removeUser();
+    }
+
+    private void scriptReturns(Long value) {
+        when(stringRedisTemplate.execute(any(), anyList(), any(), any(), any())).thenReturn(value);
+    }
+
+    @Test
+    void 脚本返回1_库存不足() {
+        scriptReturns(1L);
+        Result r = service.seckillVoucher(1L);
+        assertFalse(r.getSuccess());
+        assertEquals("库存不足", r.getErrorMsg());
+        verify(seckillMetrics).incrementStockInsufficient();
+    }
+
+    @Test
+    void 脚本返回2_重复下单() {
+        scriptReturns(2L);
+        Result r = service.seckillVoucher(1L);
+        assertFalse(r.getSuccess());
+        assertEquals("不能重复下单", r.getErrorMsg());
+        verify(seckillMetrics).incrementDuplicateOrder();
+    }
+
+    @Test
+    void 脚本返回3_key缺失_走专门指标且不与库存不足混淆() {
+        scriptReturns(3L);
+        Result r = service.seckillVoucher(1L);
+        assertFalse(r.getSuccess());
+        verify(seckillMetrics).incrementRedisStockMissing();
+        verify(seckillMetrics, never()).incrementStockInsufficient();
+    }
+
+    @Test
+    void 脚本返回null_按key缺失处理不抛NPE() {
+        scriptReturns(null);
+        Result r = service.seckillVoucher(1L);
+        assertFalse(r.getSuccess());
+        verify(seckillMetrics).incrementRedisStockMissing();
+    }
+
+    @Test
+    void 脚本返回0_消息发送成功_返回订单号且计成功() {
+        scriptReturns(0L);
+        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);
+
+        Result r = service.seckillVoucher(1L);
+
+        assertTrue(r.getSuccess());
+        assertEquals(9001L, r.getData());
+        verify(seckillMetrics).incrementSeckillSuccess();
+        verify(seckillMetrics).incrementMqSendSuccess();
+    }
+
+    @Test
+    void 脚本返回0_消息发送失败_返回失败且回滚预扣_不计成功() {
+        scriptReturns(0L);
+        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);
+        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
+
+        Result r = service.seckillVoucher(1L);
+
+        assertFalse(r.getSuccess());
+        // 回滚三件事：库存 +1、移除用户标记、删除明细。
+        // 只断言"取过 handles"不足以证明回滚发生——取了不用照样能通过，
+        // 而静默失败会让用户被永久标记"已购买"且库存凭空少 1，故必须锁定具体键与参数。
+        verify(valueOperations).increment("seckill:stock:1");
+        verify(setOperations).remove("seckill:order:1", "7");
+        verify(hashOperations).delete("seckill:order:detail:1", "9001");
+        verify(seckillMetrics).incrementMqSendFail();
+        verify(seckillMetrics, never()).incrementSeckillSuccess();
+    }
+
+    @Test
+    void 未登录时返回失败而非NPE() {
+        UserHolder.removeUser();
+        Result r = service.seckillVoucher(1L);
+        assertFalse(r.getSuccess());
+        assertEquals("未登录，请先登录", r.getErrorMsg());
+    }
+}
diff --git a/rag-service/src/main/resources/application.yaml b/rag-service/src/main/resources/application.yaml
index ff0a4ca..2ca9e3e 100644
--- a/rag-service/src/main/resources/application.yaml
+++ b/rag-service/src/main/resources/application.yaml
@@ -1,27 +1,32 @@
 server:
   port: 8087
 
 #NOTE 这里向量数据库选择 PostgreSQL+pgvector(扩展),是用来存文档和向量的
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   datasource:
     driver-class-name: org.postgresql.Driver     # PostgreSQL 驱动
     url: jdbc:postgresql://localhost:5433/rag_db    # PostgreSQL 驱动
     username: ${RAG_DB_USER:rag_user}
     password: ${RAG_DB_PASSWORD}
   jackson:
     default-property-inclusion: non_null
   data:
     redis:
       host: localhost
       port: 6379
-      password: 520117
+      password: ${REDIS_PASSWORD:}
 
 mybatis-plus:
   type-aliases-package: com.hmdp.rag.entity
 
 glm:
   api-key: ${GLM_API_KEY}
   base-url: https://open.bigmodel.cn/api/paas/v4
   llm-model: glm-4-flash
   embedding-model: embedding-2
   connect-timeout: 30s
@@ -45,18 +50,22 @@ rag:
 rocketmq:
   name-server: 127.0.0.1:9876
   consumer:
     group: rag-doc-process-group
     topic: rag-document-process
 
 logging:
   level:
     com.hmdp.rag: debug
 
+# 内部端点共享密钥（SPEC-06 §5.2）
+hmdp:
+  internal-token: ${INTERNAL_TOKEN:}
+
 sa-token:
   token-name: Authorization
   timeout: 2592000
   active-timeout: -1
   is-concurrent: true
   is-share: true
   token-style: uuid
   is-log: false
diff --git a/shop-service/src/main/java/com/hmdp/shop/controller/ShopController.java b/shop-service/src/main/java/com/hmdp/shop/controller/ShopController.java
index b0cf704..281fb84 100644
--- a/shop-service/src/main/java/com/hmdp/shop/controller/ShopController.java
+++ b/shop-service/src/main/java/com/hmdp/shop/controller/ShopController.java
@@ -1,13 +1,14 @@
 package com.hmdp.shop.controller;
 
 
+import cn.dev33.satoken.annotation.SaCheckRole;
 import cn.hutool.core.util.StrUtil;
 import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
 import com.hmdp.dto.Result;
 import com.hmdp.entity.Shop;
 import com.hmdp.shop.service.IShopService;
 import com.hmdp.utils.SystemConstants;
 import org.springframework.web.bind.annotation.*;
 
 import jakarta.annotation.Resource;
 
@@ -33,33 +34,35 @@ public class ShopController {
     public Result queryShopById(@PathVariable("id") Long id) {
         return shopService.queryById(id);
     }
 
     /**
      * 新增商铺信息
      * @param shop 商铺数据
      * @return 商铺id
      */
     @PostMapping
+    @SaCheckRole("admin")
     public Result saveShop(@RequestBody Shop shop) {
         // 写入数据库
         shopService.save(shop);
         // 返回店铺id
         return Result.ok(shop.getId());
     }
 
     /**
      * 更新商铺信息
      * @param shop 商铺数据
      * @return 无
      */
     @PutMapping
+    @SaCheckRole("admin")
     public Result updateShop(@RequestBody Shop shop) {
         // 写入数据库
         return shopService.update(shop);
     }
 
     /**
      * 根据商铺类型分页查询商铺信息
      * @param typeId 商铺类型
      * @param current 页码
      * @return 商铺列表
diff --git a/shop-service/src/main/resources/application.yaml b/shop-service/src/main/resources/application.yaml
index 9eacf9c..39b1840 100644
--- a/shop-service/src/main/resources/application.yaml
+++ b/shop-service/src/main/resources/application.yaml
@@ -1,28 +1,33 @@
 # 服务器配置
 server:
   port: 8082 # 服务端口，用于接收HTTP请求
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 数据库配置
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
     url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
     username: root # 数据库用户名
-    password: 520117 # 数据库密码
+    password: ${MYSQL_PASSWORD:} # 数据库密码
   # Redis 配置（统一使用 spring.redis 路径）
   data:
     redis:
      host: localhost  # Redis 主机地址，统一为本地地址
      port: 6379 # Redis 端口，默认 6379
-     password: 520117 # Redis 密码
+     password: ${REDIS_PASSWORD:} # Redis 密码
     # Redis 连接池配置（使用 Lettuce 客户端）
      lettuce:
       pool:
         max-active: 10 # 最大活跃连接数
         max-idle: 10 # 最大空闲连接数
         min-idle: 1 # 最小空闲连接数
         time-between-eviction-runs: 10s # 连接池空闲连接检测周期
   # Jackson JSON 处理配置
   jackson:
     default-property-inclusion: non_null # JSON序列化时忽略值为null的字段
@@ -30,19 +35,23 @@ spring:
 # MyBatis Plus 配置
 mybatis-plus:
   type-aliases-package: com.hmdp.entity # 实体类别名扫描包，用于XML映射文件中简化类名
   mapper-locations: classpath*:/mapper/**/*.xml # Mapper XML文件扫描路径
 
 # 日志配置
 logging:
   level:
     com.hmdp: debug # 日志级别，debug 用于开发环境，生产环境建议调整为 info 或 warn
 
+# hmdp 自定义配置
+hmdp:
+  admin-user-ids: ${ADMIN_USER_IDS:} # 管理接口（商户增改、券创建等）的授权用户id白名单，逗号分隔
+
 # Sa-Token 配置
 sa-token:
   token-name: Authorization
   timeout: 2592000
   active-timeout: -1
   is-concurrent: true
   is-share: true
   token-style: uuid
   is-log: false
\ No newline at end of file
diff --git a/social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java b/social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java
index 355d294..a25a361 100644
--- a/social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java
+++ b/social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java
@@ -1,30 +1,31 @@
 package com.hmdp.social.feign;
 
+import com.hmdp.config.FeignTokenRelayConfig;
 import com.hmdp.dto.Result;
 import com.hmdp.dto.UserDTO;
 import org.springframework.cloud.openfeign.FeignClient;
 import org.springframework.web.bind.annotation.GetMapping;
 import org.springframework.web.bind.annotation.PathVariable;
 import org.springframework.web.bind.annotation.PostMapping;
 import org.springframework.web.bind.annotation.RequestBody;
 
 import java.util.List;
 
 /**
  * 用户服务Feign客户端
  */
-@FeignClient(name = "user-service")
+@FeignClient(name = "user-service", configuration = FeignTokenRelayConfig.class)
 public interface UserFeignClient {
 
     /**
      * 根据用户id查询用户信息
      */
     @GetMapping("/user/{id}")
     Result getUserById(@PathVariable("id") Long id);
 
     /**
-     * 根据用户id列表查询用户信息
+     * 根据用户id列表批量查询用户信息（点赞排行榜 / 共同关注）
      */
-    @GetMapping("/user/list")
+    @PostMapping("/user/list")
     Result getUserByIds(@RequestBody List<Long> ids);
 }
diff --git a/social-service/src/main/resources/application.yaml b/social-service/src/main/resources/application.yaml
index f9ef096..d44501f 100644
--- a/social-service/src/main/resources/application.yaml
+++ b/social-service/src/main/resources/application.yaml
@@ -1,28 +1,33 @@
 # 服务器配置
 server:
   port: 8085 # 服务端口，用于接收HTTP请求
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 数据库配置
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
     url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
     username: root # 数据库用户名
-    password: 520117 # 数据库密码
+    password: ${MYSQL_PASSWORD:} # 数据库密码
   # Redis 配置
   data:
     redis:
      host: localhost  # Redis 主机地址，统一为本地地址
      port: 6379 # Redis 端口，默认 6379
-     password: 520117 # Redis 密码
+     password: ${REDIS_PASSWORD:} # Redis 密码
     # Redis 连接池配置（使用 Lettuce 客户端）
      lettuce:
        pool:
          max-active: 10 # 最大活跃连接数
          max-idle: 10 # 最大空闲连接数
          min-idle: 1 # 最小空闲连接数
          time-between-eviction-runs: 10s # 连接池空闲连接检测周期
   # Jackson JSON 处理配置
   jackson:
     default-property-inclusion: non_null # JSON序列化时忽略值为null的字段
diff --git a/user-service/pom.xml b/user-service/pom.xml
index 51dfe9a..284f8b2 100644
--- a/user-service/pom.xml
+++ b/user-service/pom.xml
@@ -81,20 +81,26 @@
             <artifactId>sa-token-redis-jackson</artifactId>
             <version>${sa-token.version}</version>
         </dependency>
 
         <dependency>
             <groupId>org.springframework.cloud</groupId>
             <artifactId>spring-cloud-starter-bootstrap</artifactId>
             <version>4.1.2</version>
         </dependency>
 
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+
     </dependencies>
 
     <build>
         <plugins>
             <plugin>
                 <groupId>org.springframework.boot</groupId>
                 <artifactId>spring-boot-maven-plugin</artifactId>
             </plugin>
         </plugins>
     </build>
diff --git a/user-service/src/main/java/com/hmdp/user/controller/UserController.java b/user-service/src/main/java/com/hmdp/user/controller/UserController.java
index cb19371..25d1a66 100644
--- a/user-service/src/main/java/com/hmdp/user/controller/UserController.java
+++ b/user-service/src/main/java/com/hmdp/user/controller/UserController.java
@@ -1,29 +1,34 @@
 package com.hmdp.user.controller;
 
 
 import cn.dev33.satoken.stp.StpUtil;
 import cn.hutool.core.bean.BeanUtil;
 import com.hmdp.dto.LoginFormDTO;
 import com.hmdp.dto.Result;
 import com.hmdp.dto.UserDTO;
+import com.hmdp.dto.UserInfoVO;
 import com.hmdp.entity.User;
 import com.hmdp.entity.UserInfo;
 import com.hmdp.user.service.IUserInfoService;
 import com.hmdp.user.service.IUserService;
 import com.hmdp.utils.UserHolder;
 import lombok.extern.slf4j.Slf4j;
 import org.springframework.web.bind.annotation.*;
 
 import jakarta.annotation.Resource;
+import jakarta.servlet.http.HttpServletRequest;
 import jakarta.servlet.http.HttpSession;
 
+import java.util.Collections;
+import java.util.List;
+
 /**
  * <p>
  * 前端控制器
  * </p>
  *
  * @author 虎哥
  * @since 2021-12-22
  */
 @Slf4j
 @RestController
@@ -33,23 +38,34 @@ public class UserController {
     @Resource
     private IUserService userService;
 
     @Resource
     private IUserInfoService userInfoService;
 
     /**
      * 发送手机验证码
      */
     @PostMapping("code")
-    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
+    public Result sendCode(@RequestParam("phone") String phone,
+                           HttpSession session,
+                           HttpServletRequest request) {
         // 发送短信验证码并保存验证码
-        return userService.sendCode(phone,session);
+        return userService.sendCode(phone, session, resolveClientIp(request));
+    }
+
+    /** 取真实客户端 IP：优先 X-Forwarded-For 首段（网关转发场景），回退 remoteAddr */
+    private String resolveClientIp(HttpServletRequest request) {
+        String forwarded = request.getHeader("X-Forwarded-For");
+        if (forwarded != null && !forwarded.isBlank()) {
+            return forwarded.split(",")[0].trim();
+        }
+        return request.getRemoteAddr();
     }
 
     /**
      * 登录功能
      * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
      */
     @PostMapping("/login")
     public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
         // 实现登录功能
         return userService.login(loginForm,session);
@@ -76,24 +92,22 @@ public class UserController {
     }
 
     @GetMapping("/info/{id}")
     public Result info(@PathVariable("id") Long userId){
         // 查询详情
         UserInfo info = userInfoService.getById(userId);
         if (info == null) {
             // 没有详情，应该是第一次查看详情
             return Result.ok();
         }
-        info.setCreateTime(null);
-        info.setUpdateTime(null);
-        // 返回
-        return Result.ok(info);
+        // 脱敏返回（SPEC-06 §5.4）：不回传 credits/birthday/gender
+        return Result.ok(BeanUtil.copyProperties(info, UserInfoVO.class));
     }
 
     @GetMapping("/{id}")
     public Result queryUserById(@PathVariable("id") Long userId){
         // 查询详情
         User user = userService.getById(userId);
         if (user == null) {
             return Result.ok();
         }
         UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
@@ -104,11 +118,28 @@ public class UserController {
     @PostMapping("/sign")
     public Result sign(){
         return userService.sign();
     }
 
     @GetMapping("/sign/count")
     public Result signCount(){
         return userService.signCount();
     }
 
+    /**
+     * 批量查询用户（供 social-service 的点赞排行榜与共同关注，SPEC-07 G1）
+     * 脱敏：复用既有 UserDTO 投影，仅返回 id/nickName/icon
+     */
+    @PostMapping("/list")
+    public Result queryUserByIds(@RequestBody List<Long> ids) {
+        if (ids == null || ids.isEmpty()) {
+            return Result.ok(Collections.emptyList());
+        }
+        if (ids.size() > 100) {
+            return Result.fail("批量查询数量不能超过 100");
+        }
+        return Result.ok(userService.listByIds(ids).stream()
+                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
+                .toList());
+    }
+
 }
\ No newline at end of file
diff --git a/user-service/src/main/java/com/hmdp/user/service/IUserService.java b/user-service/src/main/java/com/hmdp/user/service/IUserService.java
index 1421be9..8404a54 100644
--- a/user-service/src/main/java/com/hmdp/user/service/IUserService.java
+++ b/user-service/src/main/java/com/hmdp/user/service/IUserService.java
@@ -10,18 +10,23 @@ import jakarta.servlet.http.HttpSession;
 /**
  * <p>
  *  服务类
  * </p>
  *
  * @author 虎哥
  * @since 2021-12-22
  */
 public interface IUserService extends IService<User> {
 
-    Result sendCode(String phone, HttpSession session);
+    /**
+     * 发送手机验证码（SPEC-06 §5.5 增加频控）
+     *
+     * @param clientIp 调用方 IP，用于每 IP 24 小时上限
+     */
+    Result sendCode(String phone, HttpSession session, String clientIp);
 
     Result login(LoginFormDTO loginForm, HttpSession session);
 
     Result sign();
 
     Result signCount();
 }
\ No newline at end of file
diff --git a/user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java b/user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java
index 6987ec3..eda6a19 100644
--- a/user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java
+++ b/user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java
@@ -38,44 +38,87 @@ import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
  * @since 2025-12-7
  */
 @Service
 @Slf4j
 public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
 
     @Resource
     private StringRedisTemplate stringRedisTemplate;
 
 
+    /** 验证码频控口径（SPEC-06 §5.5） */
+    private static final long CODE_PHONE_INTERVAL_SECONDS = 60L;
+    private static final long CODE_PHONE_MAX_PER_DAY = 10L;
+    private static final long CODE_IP_MAX_PER_DAY = 20L;
+    private static final long CODE_COUNT_TTL_HOURS = 24L;
+
     //NOTE 发送验证码
     @Override
-    public Result sendCode(String phone, HttpSession session) {
+    public Result sendCode(String phone, HttpSession session, String clientIp) {
 
         //校验手机号
-        if(RegexUtils.isPhoneInvalid(phone)){
+        if (RegexUtils.isPhoneInvalid(phone)) {
             //不符合，返回错误信息
             return Result.fail("手机格式错误");
         }
+
+        // 频控（SPEC-06 §5.5）：未被频控前，该接口可对任意手机号无限轰炸
+        if (!checkSendCodeLimit(phone, clientIp)) {
+            return Result.fail("验证码发送过于频繁，请稍后再试");
+        }
+
         //符合，生成验证码
         String code = RandomUtil.randomNumbers(6);
 
-
         //保存验证码到redis中
-//        session.setAttribute("code",code);   NOTE 这里用redis代替session
-        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
+        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
 
-
-        // 发送验证码
-        log.debug("发送短信验证码成功，验证码:{}",code);
+        // 发送验证码。注意：**不得**在此打印验证码明文——
+        // user-service 的 logging.level.com.hmdp 为 debug，明文会直接落到日志（SPEC-06 §5.5）
+        log.debug("发送短信验证码成功，phone={}", phone);
         //返回ok
         return Result.ok();
     }
 
+    /**
+     * 验证码发送频控：手机号 60 秒 1 次 + 24 小时 10 次；IP 24 小时 20 次。
+     * Redis 异常时放行（不因限流组件故障阻断登录），仅在计数超限时拒绝。
+     */
+    private boolean checkSendCodeLimit(String phone, String clientIp) {
+        try {
+            Boolean allowed = stringRedisTemplate.opsForValue().setIfAbsent(
+                    LOGIN_CODE_LIMIT_KEY + phone, "1", CODE_PHONE_INTERVAL_SECONDS, TimeUnit.SECONDS);
+            if (Boolean.FALSE.equals(allowed)) {
+                return false;
+            }
+            if (incrWithTtl(LOGIN_CODE_COUNT_KEY + phone) > CODE_PHONE_MAX_PER_DAY) {
+                return false;
+            }
+            if (clientIp != null && !clientIp.isBlank()
+                    && incrWithTtl(LOGIN_CODE_IP_COUNT_KEY + clientIp) > CODE_IP_MAX_PER_DAY) {
+                return false;
+            }
+            return true;
+        } catch (Exception e) {
+            log.warn("验证码频控检查异常，放行: phone={}", phone, e);
+            return true;
+        }
+    }
+
+    private long incrWithTtl(String key) {
+        Long count = stringRedisTemplate.opsForValue().increment(key);
+        if (count != null && count == 1L) {
+            stringRedisTemplate.expire(key, CODE_COUNT_TTL_HOURS, TimeUnit.HOURS);
+        }
+        return count == null ? 0L : count;
+    }
+
 
     //NOTE 登录功能实现
     @Override
     public Result login(LoginFormDTO loginForm, HttpSession session) {
         //1校验手机号
         String phone = loginForm.getPhone();
         if(RegexUtils.isPhoneInvalid(phone)){
             //2如果不符合，报错
             return Result.fail("手机号格式错误");
         }
diff --git a/user-service/src/main/resources/application.yaml b/user-service/src/main/resources/application.yaml
index 5d077dd..1085f95 100644
--- a/user-service/src/main/resources/application.yaml
+++ b/user-service/src/main/resources/application.yaml
@@ -1,28 +1,33 @@
 # 服务器配置
 server:
   port: 8086 # 服务端口，用于接收HTTP请求
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 数据库配置
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
     url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
     username: root # 数据库用户名
-    password: 520117 # 数据库密码
+    password: ${MYSQL_PASSWORD:} # 数据库密码
   # Redis 配置
   data:
     redis:
      host: localhost  # Redis 主机地址，统一为本地地址
      port: 6379 # Redis 端口，默认 6379
-     password: 520117 # Redis 密码
+     password: ${REDIS_PASSWORD:} # Redis 密码
     # Redis 连接池配置（使用 Lettuce 客户端）
      lettuce:
        pool:
          max-active: 10 # 最大活跃连接数
          max-idle: 10 # 最大空闲连接数
          min-idle: 1 # 最小空闲连接数
          time-between-eviction-runs: 10s # 连接池空闲连接检测周期
   # Jackson JSON 处理配置
   jackson:
     default-property-inclusion: non_null # JSON序列化时忽略值为null的字段
diff --git a/user-service/src/test/java/com/hmdp/user/service/UserServiceImplSendCodeTest.java b/user-service/src/test/java/com/hmdp/user/service/UserServiceImplSendCodeTest.java
new file mode 100644
index 0000000..75ac457
--- /dev/null
+++ b/user-service/src/test/java/com/hmdp/user/service/UserServiceImplSendCodeTest.java
@@ -0,0 +1,87 @@
+package com.hmdp.user.service;
+
+import com.hmdp.dto.Result;
+import com.hmdp.user.service.impl.UserServiceImpl;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.mockito.InjectMocks;
+import org.mockito.Mock;
+import org.mockito.junit.jupiter.MockitoExtension;
+import org.mockito.junit.jupiter.MockitoSettings;
+import org.mockito.quality.Strictness;
+import org.springframework.data.redis.core.StringRedisTemplate;
+import org.springframework.data.redis.core.ValueOperations;
+
+import java.util.concurrent.TimeUnit;
+
+import static org.junit.jupiter.api.Assertions.*;
+import static org.mockito.ArgumentMatchers.*;
+import static org.mockito.Mockito.*;
+
+@ExtendWith(MockitoExtension.class)
+@MockitoSettings(strictness = Strictness.LENIENT)
+class UserServiceImplSendCodeTest {
+
+    @Mock private StringRedisTemplate stringRedisTemplate;
+    @Mock private ValueOperations<String, String> valueOperations;
+    @InjectMocks private UserServiceImpl userService;
+
+    @Test
+    void 手机号格式错误直接拒绝() {
+        Result r = userService.sendCode("123", null, "127.0.0.1");
+        assertFalse(r.getSuccess());
+        assertEquals("手机格式错误", r.getErrorMsg());
+    }
+
+    @Test
+    void 六十秒内重复发送被拒绝() {
+        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
+        when(valueOperations.setIfAbsent(eq("login:code:limit:phone:13800138000"), anyString(),
+                anyLong(), any(TimeUnit.class))).thenReturn(false);
+
+        Result r = userService.sendCode("13800138000", null, "127.0.0.1");
+
+        assertFalse(r.getSuccess());
+        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
+    }
+
+    @Test
+    void 手机号24小时超过10次被拒绝() {
+        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
+        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
+                .thenReturn(true);
+        when(valueOperations.increment("login:code:count:phone:13800138000")).thenReturn(11L);
+
+        Result r = userService.sendCode("13800138000", null, "127.0.0.1");
+
+        assertFalse(r.getSuccess());
+        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
+    }
+
+    @Test
+    void IP二十四小时超过二十次被拒绝() {
+        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
+        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
+                .thenReturn(true);
+        when(valueOperations.increment("login:code:count:phone:13800138000")).thenReturn(1L);
+        when(valueOperations.increment("login:code:count:ip:127.0.0.1")).thenReturn(21L);
+
+        Result r = userService.sendCode("13800138000", null, "127.0.0.1");
+
+        assertFalse(r.getSuccess());
+        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
+    }
+
+    @Test
+    void 正常发送写入验证码并返回成功() {
+        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
+        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
+                .thenReturn(true);
+        when(valueOperations.increment(anyString())).thenReturn(1L);
+
+        Result r = userService.sendCode("13800138000", null, "127.0.0.1");
+
+        assertTrue(r.getSuccess());
+        verify(valueOperations).set(eq("login:code:13800138000"), anyString(), eq(2L), eq(TimeUnit.MINUTES));
+    }
+}
diff --git a/voucher-service/pom.xml b/voucher-service/pom.xml
index ed0e901..23c84cb 100644
--- a/voucher-service/pom.xml
+++ b/voucher-service/pom.xml
@@ -81,20 +81,26 @@
             <artifactId>sa-token-redis-jackson</artifactId>
             <version>${sa-token.version}</version>
         </dependency>
 
         <dependency>
             <groupId>org.springframework.cloud</groupId>
             <artifactId>spring-cloud-starter-bootstrap</artifactId>
             <version>4.1.2</version>
         </dependency>
 
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+
     </dependencies>
 
     <build>
         <plugins>
             <plugin>
                 <groupId>org.springframework.boot</groupId>
                 <artifactId>spring-boot-maven-plugin</artifactId>
             </plugin>
         </plugins>
     </build>
diff --git a/voucher-service/src/main/java/com/hmdp/voucher/controller/InternalVoucherController.java b/voucher-service/src/main/java/com/hmdp/voucher/controller/InternalVoucherController.java
new file mode 100644
index 0000000..eebdad0
--- /dev/null
+++ b/voucher-service/src/main/java/com/hmdp/voucher/controller/InternalVoucherController.java
@@ -0,0 +1,35 @@
+package com.hmdp.voucher.controller;
+
+import com.hmdp.dto.Result;
+import com.hmdp.voucher.service.IVoucherService;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PutMapping;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RequestParam;
+import org.springframework.web.bind.annotation.RestController;
+
+import jakarta.annotation.Resource;
+
+/**
+ * 内部端点（SPEC-06 §5.2 方案 A）
+ *
+ * <p>仅供 order-service 经 Nacos 直连调用，与公开业务接口物理隔离：
+ * 网关无 {@code /internal/**} 路由，服务侧由 common 的 InternalTokenInterceptor
+ * 校验 {@code X-Internal-Token}。不再依赖"网关不转发"这一隐式假设。
+ */
+@RestController
+@RequestMapping("/internal/voucher")
+public class InternalVoucherController {
+
+    @Resource
+    private IVoucherService voucherService;
+
+    /**
+     * 扣减秒杀券库存（原 PUT /voucher/seckill/{id}/stock，见 SPEC-06 §1.2）
+     */
+    @PutMapping("/seckill/{id}/stock")
+    public Result deductStock(@PathVariable("id") Long voucherId,
+                              @RequestParam("orderId") Long orderId) {
+        return voucherService.deductStock(voucherId, orderId);
+    }
+}
diff --git a/voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java b/voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java
index e6091b7..cb828b2 100644
--- a/voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java
+++ b/voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java
@@ -1,20 +1,24 @@
 package com.hmdp.voucher.controller;
 
 
+import cn.dev33.satoken.annotation.SaCheckRole;
 import com.hmdp.dto.Result;
 import com.hmdp.entity.Voucher;
 import com.hmdp.voucher.service.IVoucherService;
 import org.springframework.web.bind.annotation.*;
 
 import jakarta.annotation.Resource;
 
+import java.util.Collections;
+import java.util.List;
+
 /**
  * <p>
  *  前端控制器
  * </p>
  *
  * @author 虎哥
  * @since 2021-12-22
  */
 @RestController
 @RequestMapping("/voucher")
@@ -22,31 +26,33 @@ public class VoucherController {
 
     @Resource
     private IVoucherService voucherService;
 
     /**
      * 新增普通券
      * @param voucher 优惠券信息
      * @return 优惠券id
      */
     @PostMapping
+    @SaCheckRole("admin")
     public Result addVoucher(@RequestBody Voucher voucher) {
         voucherService.save(voucher);
         return Result.ok(voucher.getId());
     }
 
     /**
      * 新增秒杀券
      * @param voucher 优惠券信息，包含秒杀信息
      * @return 优惠券id
      */
     @PostMapping("seckill")
+    @SaCheckRole("admin")
     public Result addSeckillVoucher(@RequestBody Voucher voucher) {
         voucherService.addSeckillVoucher(voucher);
         return Result.ok(voucher.getId());
     }
 
     /**
      * 查询店铺的优惠券列表
      * @param shopId 店铺id
      * @return 优惠券列表
      */
@@ -63,19 +69,23 @@ public class VoucherController {
     @GetMapping("/{id}")
     public Result queryVoucherById(@PathVariable("id") Long id) {
         Voucher voucher = voucherService.getById(id);
         if (voucher == null) {
             return Result.fail("券不存在");
         }
         return Result.ok(voucher);
     }
 
     /**
-     * 扣减优惠券库存
-     * @param voucherId 优惠券id
-     * @return 扣减结果
+     * 批量查询券（order-service 的 queryMyOrders 联查用，SPEC-07 §5.4 消除 N+1）
      */
-    @PutMapping("/seckill/{id}/stock")
-    public Result deductStock(@PathVariable("id") Long voucherId) {
-        return voucherService.deductStock(voucherId);
+    @PostMapping("/batch")
+    public Result queryVouchersByIds(@RequestBody List<Long> ids) {
+        if (ids == null || ids.isEmpty()) {
+            return Result.ok(Collections.emptyList());
+        }
+        if (ids.size() > 100) {
+            return Result.fail("批量查询数量不能超过 100");
+        }
+        return Result.ok(voucherService.listByIds(ids));
     }
 }
\ No newline at end of file
diff --git a/voucher-service/src/main/java/com/hmdp/voucher/service/IVoucherService.java b/voucher-service/src/main/java/com/hmdp/voucher/service/IVoucherService.java
index d41b5bd..7087629 100644
--- a/voucher-service/src/main/java/com/hmdp/voucher/service/IVoucherService.java
+++ b/voucher-service/src/main/java/com/hmdp/voucher/service/IVoucherService.java
@@ -11,12 +11,19 @@ import com.baomidou.mybatisplus.extension.service.IService;
  *
  * @author 虎哥
  * @since 2021-12-22
  */
 public interface IVoucherService extends IService<Voucher> {
 
     Result queryVoucherOfShop(Long shopId);
 
     void addSeckillVoucher(Voucher voucher);
 
-    Result deductStock(Long voucherId);
+    /**
+     * 扣减秒杀券库存（SPEC-03 §5.1/§5.4）
+     *
+     * @param voucherId 优惠券 id
+     * @param orderId   订单 id——作为服务端幂等键：消费重试时同一 orderId 只扣减一次
+     * @return 成功；或库存不足失败
+     */
+    Result deductStock(Long voucherId, Long orderId);
 }
\ No newline at end of file
diff --git a/voucher-service/src/main/java/com/hmdp/voucher/service/impl/VoucherServiceImpl.java b/voucher-service/src/main/java/com/hmdp/voucher/service/impl/VoucherServiceImpl.java
index 021ffc1..19b3b75 100644
--- a/voucher-service/src/main/java/com/hmdp/voucher/service/impl/VoucherServiceImpl.java
+++ b/voucher-service/src/main/java/com/hmdp/voucher/service/impl/VoucherServiceImpl.java
@@ -1,39 +1,46 @@
 package com.hmdp.voucher.service.impl;
 
 import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
 import com.hmdp.dto.Result;
 import com.hmdp.entity.Voucher;
 import com.hmdp.voucher.mapper.VoucherMapper;
 import com.hmdp.entity.SeckillVoucher;
 import com.hmdp.voucher.service.ISeckillVoucherService;
 import com.hmdp.voucher.service.IVoucherService;
+import lombok.extern.slf4j.Slf4j;
 import org.springframework.data.redis.core.StringRedisTemplate;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Transactional;
 
 import jakarta.annotation.Resource;
+import java.time.Duration;
 import java.util.List;
 
+import static com.hmdp.utils.RedisConstants.SECKILL_DEDUCT_KEY;
 import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
 
 /**
  * <p>
  *  服务实现类
  * </p>
  *
  * @author 虎哥
  * @since 2021-12-22
  */
+@Slf4j
 @Service
 public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {
 
+    /** 秒杀扣减幂等 Set 的 TTL：只需覆盖 MQ 重试窗口（秒~分钟级），24h 足够且能自清理。 */
+    private static final Duration SECKILL_DEDUCT_TTL = Duration.ofHours(24);
+
     @Resource
     private ISeckillVoucherService seckillVoucherService;
 
     @Resource
     private StringRedisTemplate stringRedisTemplate;
 
 
     @Override
     public Result queryVoucherOfShop(Long shopId) {
         // 查询优惠券信息
@@ -52,27 +59,52 @@ public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> impl
         seckillVoucher.setVoucherId(voucher.getId());
         seckillVoucher.setStock(voucher.getStock());
         seckillVoucher.setBeginTime(voucher.getBeginTime());
         seckillVoucher.setEndTime(voucher.getEndTime());
         seckillVoucherService.save(seckillVoucher);
         //保存秒杀库存到redis中
         stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),voucher.getStock().toString());
 
     }
 
+    /**
+     * 扣减秒杀券库存（SPEC-03 §5.1/§5.4）
+     *
+     * <p>唯一 owner 原则：{@code seckill:stock:{voucherId}} 的扣减权只属于 order-service 的
+     * seckill.lua（秒杀流量入口在那里）。本方法**只扣 DB**，不再操作该 key——原先的 decrement
+     * 与入口的 DECR 叠加成双扣，使 Redis 库存以 2 倍速度消耗（SPEC-03 §1.2）。
+     *
+     * <p>幂等：以 {@code (voucherId, orderId)} 为键，重复请求（MQ 重试）直接返回成功，
+     * 避免"扣库存成功后、插单前崩溃"导致的重试再次扣减 DB（SPEC-03 G4）。
+     */
     @Override
-    public Result deductStock(Long voucherId) {
-        // 扣减库存
+    @Transactional
+    public Result deductStock(Long voucherId, Long orderId) {
+        String deductKey = SECKILL_DEDUCT_KEY + voucherId;
+        Long first = stringRedisTemplate.opsForSet().add(deductKey, orderId.toString());
+        if (first == null) {
+            // Redis 故障（无返回值）：宁可少卖也不超卖，但与"已扣减"区分开，否则该窗口完全不可观测
+            log.warn("秒杀扣减幂等标记写入失败(Redis 无返回)，跳过 DB 扣减，stock 可能少扣: voucherId={}, orderId={}",
+                    voucherId, orderId);
+            return Result.ok();
+        }
+        if (first == 0L) {
+            // 正常 MQ 重投：该订单已扣减过，幂等返回，不必打日志
+            return Result.ok();
+        }
+        // TTL 每次 add 都刷新，而不是只在建 Set 时设一次：券可能连卖数天，若 TTL 锚定在首次创建，
+        // 集合会在售卖中途过期，近期订单的重试将再次扣减 DB（重复扣减）。
+        stringRedisTemplate.expire(deductKey, SECKILL_DEDUCT_TTL);
+
         boolean success = seckillVoucherService.update()
                 .setSql("stock = stock - 1")
                 .eq("voucher_id", voucherId)
                 .gt("stock", 0)
                 .update();
         if (!success) {
-            // 扣减失败
+            // 未扣成功，放开幂等标记，避免订单被永久误标为"已扣"
+            stringRedisTemplate.opsForSet().remove(deductKey, orderId.toString());
             return Result.fail("库存不足");
         }
-        // 更新Redis中的库存
-        stringRedisTemplate.opsForValue().decrement(SECKILL_STOCK_KEY + voucherId);
         return Result.ok();
     }
 }
\ No newline at end of file
diff --git a/voucher-service/src/main/resources/application.yaml b/voucher-service/src/main/resources/application.yaml
index 1044311..bf3fe01 100644
--- a/voucher-service/src/main/resources/application.yaml
+++ b/voucher-service/src/main/resources/application.yaml
@@ -1,28 +1,33 @@
 # 服务器配置
 server:
   port: 8083 # 服务端口，用于接收HTTP请求
 
 # Spring 配置
 spring:
+  # 本地凭据（SPEC-06 G8）：optional 保证文件缺失时不阻塞启动；环境变量优先级更高，可覆盖
+  config:
+    import:
+      - optional:file:.env[.properties]
+      - optional:file:../.env[.properties]
   # 数据库配置
   datasource:
     driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
     url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
     username: root # 数据库用户名
-    password: 520117 # 数据库密码
+    password: ${MYSQL_PASSWORD:} # 数据库密码
   # Redis 配置
   data:
    redis:
      host: localhost  # Redis 主机地址，统一为本地地址
      port: 6379 # Redis 端口，默认 6379
-     password: 520117 # Redis 密码
+     password: ${REDIS_PASSWORD:} # Redis 密码
     # Redis 连接池配置（使用 Lettuce 客户端）
      lettuce:
        pool:
          max-active: 10 # 最大活跃连接数
          max-idle: 10 # 最大空闲连接数
          min-idle: 1 # 最小空闲连接数  8907
          time-between-eviction-runs: 10s # 连接池空闲连接检测周期
   # Jackson JSON 处理配置
   jackson:
     default-property-inclusion: non_null # JSON序列化时忽略值为null的字段
@@ -88,19 +93,24 @@ seata:
 # 事务监控配置
 management:
   endpoints:
     web:
       exposure:
         include: seata,health,info
   endpoint:
     seata:
       enabled: true
 
+# 内部端点共享密钥（SPEC-06 §5.2）
+hmdp:
+  internal-token: ${INTERNAL_TOKEN:}
+  admin-user-ids: ${ADMIN_USER_IDS:}
+
 # Sa-Token 配置
 sa-token:
   token-name: Authorization
   timeout: 2592000
   active-timeout: -1
   is-concurrent: true
   is-share: true
   token-style: uuid
   is-log: false
\ No newline at end of file
diff --git a/voucher-service/src/test/java/com/hmdp/voucher/service/VoucherServiceImplDeductStockTest.java b/voucher-service/src/test/java/com/hmdp/voucher/service/VoucherServiceImplDeductStockTest.java
new file mode 100644
index 0000000..5209f49
--- /dev/null
+++ b/voucher-service/src/test/java/com/hmdp/voucher/service/VoucherServiceImplDeductStockTest.java
@@ -0,0 +1,95 @@
+package com.hmdp.voucher.service;
+
+import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
+import com.hmdp.dto.Result;
+import com.hmdp.entity.SeckillVoucher;
+import com.hmdp.voucher.service.impl.VoucherServiceImpl;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.api.extension.ExtendWith;
+import org.mockito.Answers;
+import org.mockito.InjectMocks;
+import org.mockito.Mock;
+import org.mockito.junit.jupiter.MockitoExtension;
+import org.springframework.data.redis.core.SetOperations;
+import org.springframework.data.redis.core.StringRedisTemplate;
+import org.springframework.transaction.annotation.Transactional;
+
+import java.lang.reflect.Method;
+
+import static org.junit.jupiter.api.Assertions.*;
+import static org.mockito.ArgumentMatchers.*;
+import static org.mockito.Mockito.*;
+
+@ExtendWith(MockitoExtension.class)
+class VoucherServiceImplDeductStockTest {
+
+    /** MyBatis-Plus 的 update() 返回链式 wrapper，用 deep stubs 才能 stub 到链尾的 update() */
+    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private ISeckillVoucherService seckillVoucherService;
+    @Mock private StringRedisTemplate stringRedisTemplate;
+    @Mock private SetOperations<String, String> setOperations;
+    @InjectMocks private VoucherServiceImpl voucherService;
+
+    /**
+     * 把 DB 条件更新（stock = stock - 1 WHERE voucher_id = ? AND stock > 0）的结果固定住。
+     *
+     * <p>deep stubs 只对返回接口/抽象类型生成 mock；MyBatis-Plus 的
+     * {@code update()} 返回的是具体类 {@code UpdateChainWrapper}，链式调用会拿到
+     * 未 mock 的真对象并在 {@code setSql} 处 NPE。这里显式 mock 该 wrapper，
+     * 断言仍只针对 deductStock 的行为。
+     */
+    private void dbUpdateReturns(boolean result) {
+        UpdateChainWrapper<SeckillVoucher> wrapper = mock(UpdateChainWrapper.class);
+        when(seckillVoucherService.update()).thenReturn(wrapper);
+        when(wrapper.setSql(anyString())).thenReturn(wrapper);
+        when(wrapper.eq(anyString(), any())).thenReturn(wrapper);
+        when(wrapper.gt(anyString(), any())).thenReturn(wrapper);
+        when(wrapper.update()).thenReturn(result);
+    }
+
+    @Test
+    void 首次扣减成功_写幂等键且不再操作库存key() {
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(1L);
+        dbUpdateReturns(true);
+
+        Result r = voucherService.deductStock(1L, 9001L);
+
+        assertTrue(r.getSuccess());
+        // 关键断言（SPEC-03 A7）：不再 DECR/INCR seckill:stock:{id}
+        verify(stringRedisTemplate, never()).opsForValue();
+    }
+
+    @Test
+    void 重复调用直接返回成功_不再扣减DB() {
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(0L);
+
+        Result r = voucherService.deductStock(1L, 9001L);
+
+        assertTrue(r.getSuccess());
+        verifyNoInteractions(seckillVoucherService);
+        verify(stringRedisTemplate, never()).opsForValue();
+    }
+
+    @Test
+    void DB扣减失败时返回库存不足_并放开幂等标记() {
+        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
+        when(setOperations.add("seckill:deduct:1", "9001")).thenReturn(1L);
+        dbUpdateReturns(false);
+
+        Result r = voucherService.deductStock(1L, 9001L);
+
+        assertFalse(r.getSuccess());
+        assertEquals("库存不足", r.getErrorMsg());
+        // 未扣成功必须放开标记，否则该订单被永久误标为"已扣"
+        verify(setOperations).remove("seckill:deduct:1", "9001");
+        verify(stringRedisTemplate, never()).opsForValue();
+    }
+
+    @Test
+    void deductStock必须带事务() throws Exception {
+        Method m = VoucherServiceImpl.class.getMethod("deductStock", Long.class, Long.class);
+        assertNotNull(m.getAnnotation(Transactional.class),
+                "SPEC-03 §1.8：两步扣减必须原子，缺少 @Transactional");
+    }
+}
```
