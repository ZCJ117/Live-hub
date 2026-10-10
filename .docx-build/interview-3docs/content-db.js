// 数据库设计与持久层 · 8 道主问题
module.exports = {
  outFile: "数据库设计与持久层_面试题.docx",
  title: "数据库设计与持久层 · 面试题与标准答案",
  subtitle:
    "基于《左常健 · 后端研发简历》Live-Hub 项目经历（“MySQL、MyBatis-Plus”、“熟悉 MySQL，理解 B+ 树索引与事务隔离级别，能用 EXPLAIN 分析慢查询并优化”）+ D:\\hm-dianping 源码实证整理。本板块最值得准备的三条是「仓库内 DDL 与实体大面积对不上」「唯一约束缺失导致 DB 层没有兜底」「分页插件未注册导致所有分页静默失效」，它们都能在源码中逐条查证。",
  overview:
    "5 个业务服务（shop / voucher / order / social / user）共用同一个 MySQL 库 hmdp，未分库；agent 与 rag 使用独立 schema，不在本次范围。持久层全部基于 MyBatis-Plus 3.5.6 的 ServiceImpl + BaseMapper，4 个服务的 resources/mapper 目录均为空，即全仓 0 条自定义 XML，所有查询都由 Wrapper 或 setSql 拼装。版本库中提供的建表脚本是 docs/SQL/start.sql，包含 tb_sign、tb_seckill_voucher、tb_shop_type、tb_follow、tb_voucher、tb_shop、tb_shop_comments、tb_voucher_order、tb_user_info 共 9 张表，另有 Seata 的 undo_log.sql。需注意：tb_user、tb_blog、tb_blog_comments 三张表的建表语句在仓库中不存在，且已提供的部分 DDL 与实体定义存在系统性不一致（详见 Q5）。",
  overviewBullets: [
    "共库：5 个业务服务数据源均指向 jdbc:mysql://127.0.0.1:3306/hmdp，未分库（README.md 已如实说明）",
    "持久层：MyBatis-Plus 3.5.6，全仓 0 自定义 XML（order/shop/user/voucher 的 mapper 目录为空），全部 Wrapper 化",
    "主键：IdType.AUTO（Shop/Blog/User/Follow/ShopType）与 IdType.INPUT（VoucherOrder → RedisIdWorker、SeckillVoucher → INPUT）两种策略并存",
    "索引：tb_follow / tb_voucher_order / tb_shop_comments 等有单列普通索引，但全库 0 个唯一约束（除主键）",
    "⚠ 分页：全仓无 MybatisPlusInterceptor / PaginationInnerInterceptor → 5 处 page() 调用不产生 LIMIT；order-service 已手工 count+LIMIT 绕过并在注释中写明",
    "⚠ DDL：docs/SQL/start.sql 中 tb_shop 的列与 Shop 实体完全不符；多处缺 AUTO_INCREMENT；tb_user_info 主键列名与实体不一致",
  ],
  questions: [
    // ---------------- Q1 ----------------
    {
      cat: "库表设计", level: "基础",
      title: "数据库是怎么设计的？为什么选 MySQL？5 个服务共用一个库有什么问题？",
      focus: "考察点：存储选型的判断依据、微服务下数据边界与共享库的取舍、对项目自身「说了要拆库但没拆」的诚实度。",
      follows: [
        "为什么不用 PostgreSQL 或者 MongoDB？这些表里哪些最适合文档型存储？",
        "5 个服务共库，事务是不是就可以直接用本地事务了？为什么秒杀还要用 Seata / Redis + MQ？",
        "如果让你拆库，你会按什么维度拆？拆完第一个要解决的问题是什么？",
      ],
      blocks: [
        { t: "h", x: "1．表清单与职责" },
        {
          t: "code",
          x: "docs/SQL/start.sql 中提供的建表语句（共 9 张 + undo_log）：\n\n  表名                  业务域     关键字段                                        行号\n  -----------------------------------------------------------------------------------------------\n  tb_sign               用户        user_id / year / month / date / is_backup         :2-10\n  tb_user_info          用户        city / introduce / fans / followee / credits    :114-127\n  tb_user               ⚠ 缺失     （实体：phone / password / nickName / icon）      —\n  tb_shop_type          商户        name / icon / sort                               :24-32\n  tb_shop               ⚠ 不符     （见 Q5：列与 Shop 实体完全不一致）                :63-77\n  tb_blog               ⚠ 缺失     （实体：shop_id/user_id/title/images/content…）    —\n  tb_shop_comments      ⚠ 命名不符  （列与 BlogComments 实体一致，表名不一致）          :80-94\n  tb_voucher            券          shop_id / title / pay_value / type / status      :47-60\n  tb_seckill_voucher    券          voucher_id(PK) / stock / begin_time / end_time   :13-21\n  tb_voucher_order      订单        user_id / voucher_id / status / pay_time …       :97-111\n  tb_follow             社交        user_id / follow_user_id                         :35-43\n  undo_log              Seata       Seata AT 模式回滚日志                            undo_log.sql\n\n业务域映射：用户(tb_user/tb_user_info/tb_sign) · 商户(tb_shop/tb_shop_type)\n          券(tb_voucher/tb_seckill_voucher) · 订单(tb_voucher_order)\n          社交(tb_blog/tb_shop_comments/tb_follow)",
        },
        { t: "h", x: "2．选型理由" },
        {
          t: "p",
          x: "**选 MySQL 的核心原因是「数据本身是强关系的」**：订单要关联用户与券，券要关联商户，关注是用户与用户的多对多——这些是典型的实体关系模型，需要事务保证（下单要同时扣库存与写订单）、需要多表 join（查券时要带出所属商户）、需要唯一约束兜底（一人一单）。MySQL 的 InnoDB 事务、B+ 树索引、唯一约束正是为这类场景设计的。",
        },
        {
          t: "p",
          x: "**什么时候不该用 MySQL**：字段结构不固定的（如商品的多变属性、配置项）适合文档型（MongoDB）；纯 KV 且要求极致 QPS 的适合 Redis；海量日志与全文检索的适合 ES。本项目里没有这类需求，统一 MySQL 是正确的简化。",
        },
        {
          t: "p",
          x: "**一个可以主动提的细节是大字段的处理**：`tb_shop_comments.content` 与 `tb_shop.content` 用的是 `text` 类型（`start.sql:69,86`）。InnoDB 的 `text` 会部分外溢到溢出页，导致主键索引变大、扫描变慢。如果评论正文很长，更合适的是拆到独立的 `tb_comment_content` 表或用 `varchar(2000)` 限制长度。当前设计把正文与查询字段放在一起，属于「能跑但不够讲究」。",
        },
        { t: "h", x: "3．追问 1：为什么共库" },
        {
          t: "code",
          x: "5 个服务的 application.yaml 中数据源完全相同：\n  url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC\n\n而项目自己的设计文档 docs/md/hmdp微服务拆分方案.md:58 写的是：\n  「按模块拆分数据库，创建5个数据库实例」\n\n→ 设计目标与实现不一致。README.md 中已如实说明「业务库统一为 MySQL 的 hmdp 库」。",
        },
        {
          t: "p",
          x: "共库带来的四类具体问题（每一条都能举例）：① **发布耦合**——任何一张表的 DDL 变更需要所有服务一起评估，服务独立发布的能力被数据库抵消；② **边界失效**——一个服务可以直接 join 另一个服务的表，代码里没有任何机制阻止（比如 social 完全可以 join tb_user 而不是走 Feign），微服务边界退化为口头约定；③ **资源竞争**——连接池、慢查询、锁等待跨服务互相影响，一个服务的慢 SQL 会拖垮全部；④ **无法独立伸缩与选型**——所有服务共享同一个库的容量上限，也不能针对某个域换存储。",
        },
        {
          t: "p",
          x: "**要主动说明这是有意识的取舍而非疏漏**：5 个库意味着需要维护 5 套连接池、5 份 DDL、跨库无法 join、本地事务全部失效（连「同一个库里的简单事务」都要变成分布式事务），对一个单机 8 核 16G 的学习项目来说，收益远小于成本。**先共库跑通业务、把微服务的服务治理能力验证完整，再按需拆库，是务实的路径。**",
        },
        { t: "h", x: "4．追问 2：共库为什么还要分布式事务（这是一个很好的陷阱题）" },
        {
          t: "p",
          x: "**共库解决了「同一个事务里的数据在同一个 MySQL」的问题，但没解决「同一个业务动作被拆到多个服务、每个服务用自己的连接」的问题。** Seata AT 模式补偿的是**跨服务、跨连接**的事务——即使两个服务连的是同一个库，order-service 的 `@GlobalTransactional` 用的是自己的连接，voucher-service 的扣库存用的是另一个连接，两者不在同一个本地事务里，仍然需要分布式事务框架来协调。",
        },
        {
          t: "p",
          x: "所以秒杀链路最终选择放弃 Seata、改用「Redis Lua 预扣 + RocketMQ 异步最终一致」，**根本原因是性能而非共库与否**：Seata AT 需要在每个参与者上加全局锁、写 undo_log、两阶段提交，在秒杀这种高并发写场景下会成为瓶颈（`docs/perf-plan/架构演进与性能优化落地方案.md` 中对此有分析）。用 Redis 做前置的原子扣减，把数据库层的并发放到 MQ 里串行化，才是高并发秒杀的标准解法。",
        },
        {
          t: "p",
          x: "**回答这个追问的关键是区分「事务边界」和「部署边界」**：共库改变的是数据放在哪，不改变业务动作跨了几个服务实例、用了几条连接。能讲清这一点，说明对分布式事务的动机理解到位。",
        },
        { t: "h", x: "5．追问 3：怎么拆库" },
        {
          t: "code",
          x: "建议的拆分维度与顺序：\n\n  第 1 步：拆「写入压力最大」的        订单库（order → hmdp_order）\n  第 2 步：拆「数据量最大、增长最快」的  社交库（social → hmdp_social）\n  第 3 步：拆「与其他域耦合最弱」的      用户库（user → hmdp_user）\n  最后：商户与券（两者耦合紧，可一并考虑）\n\n拆完立刻要解决的问题（按优先级）：\n  ① 跨库 join → 改为「先查 A 库拿 id 列表，再批量查 B 库」或建冗余读模型\n  ② 本地事务失效 → 引入本地消息表 / MQ 事务消息做最终一致\n  ③ 唯一约束跨库失效 → 原来靠「一个库里的唯一索引」保证的不变量，\n     现在要靠 Redis 原子操作或独立的 ID 分配服务兜底\n  ④ 分页与统计跨库 → 需要聚合层或 CQRS 读模型",
        },
      ],
      sources: [
        "docs/SQL/start.sql（9 张表建表语句，行号见上方表格）",
        "docs/SQL/undo_log.sql（Seata AT 模式回滚表）",
        "各服务 src/main/resources/application.yaml（数据源，5 服务共用 hmdp 库）",
        "docs/md/hmdp微服务拆分方案.md:58（原设计目标：拆 5 个库，与实现不符）",
        "README.md（已如实说明业务库统一为 hmdp）",
        "docs/perf-plan/架构演进与性能优化落地方案.md（Seata → Redis+Lua+RocketMQ 的演进分析）",
      ],
    },

    // ---------------- Q2 ----------------
    {
      cat: "主键设计", level: "基础",
      title: "主键是怎么生成的？为什么订单用自定义 ID 而商户用自增？RedisIdWorker 是怎么实现的？",
      focus: "考察点：自增主键与分布式 ID 的适用边界、雪花算法的位运算实现、对时钟回拨这类经典问题的认知。",
      follows: [
        "为什么要从 2022-01-01 开始算时间戳，而不是从 1970 年？",
        "如果服务器时钟回拨了，这个 ID 生成器会怎样？",
        "32 位的序列号够用吗？一天能生成多少个？",
      ],
      blocks: [
        { t: "h", x: "1．两种策略并存" },
        {
          t: "code",
          x: "自增（IdType.AUTO）—— 数据库负责生成：\n  Shop        @TableId(value = \"id\", type = IdType.AUTO)   Shop.java:38\n  ShopType    @TableId(value = \"id\", type = IdType.AUTO)   ShopType.java:33\n  Blog        @TableId(value = \"id\", type = IdType.AUTO)   Blog.java:33\n  User        @TableId(value = \"id\", type = IdType.AUTO)   User.java:32\n  Follow      @TableId(value = \"id\", type = IdType.AUTO)   Follow.java:32\n  BlogComments@TableId(value = \"id\", type = IdType.AUTO)   BlogComments.java:32\n\n外部输入（IdType.INPUT）—— 应用负责生成：\n  VoucherOrder  @TableId(value = \"id\", type = IdType.INPUT)  VoucherOrder.java:32\n  SeckillVoucher@TableId(value = \"voucher_id\", type = IdType.INPUT)  SeckillVoucher.java:32\n\n生成点：order-service/.../service/impl/VoucherOrderServiceImpl.java:96\n  long orderId = redisIdWorker.nextId(\"order\");",
        },
        {
          t: "p",
          x: "**为什么订单必须用自定义 ID**：① **不能让用户从订单号推断业务量**——自增 ID 意味着「第 1000 号订单」暴露了平台总单量，竞品可以据此估算你的规模；② **不能暴露增长速度**——今天下单拿到 10523，明天拿到 10530，就能算出日订单量；③ **分库分表后自增会冲突**——多个库各自自增会产生重复主键，而分布式 ID 天然全局唯一；④ **需要在写库之前就拿到 ID**——秒杀链路是「Redis Lua 预扣时就生成 orderId 并写入订单详情 Hash」，此时数据库还没有记录，自增主键给不出来。**第 ④ 点是最直接的原因，与前三点共同决定了必须自定义生成。**",
        },
        {
          t: "p",
          x: "反过来，商户、类型、博客这类**由后台录入、不需要提前生成、也不介意暴露数量**的数据，用自增最简单高效：InnoDB 的自增主键是顺序写入，不会造成页分裂，插入性能最好。**「按需选择」比「一刀切都用分布式 ID」更专业。**",
        },
        { t: "h", x: "2．RedisIdWorker 的实现" },
        {
          t: "code",
          x: "common/src/main/java/com/hmdp/utils/RedisIdWorker.java（全量 42 行）\n\n  private static final long BEGIN_TIMESTAMP = 1640995200L;   // :14  2022-01-01 00:00:00\n  private static final int  COUNT_BITS      = 32;            // :16\n\n  public long nextId(String keyPrefix) {\n      LocalDateTime now = LocalDateTime.now();                             // :27\n      long nowSecond = now.toEpochSecond(ZoneOffset.UTC);                  // :28\n      long timestamp = nowSecond - BEGIN_TIMESTAMP;                        // :29\n\n      String date = now.format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd\")); // :33\n      long count = stringRedisTemplate.opsForValue()\n              .increment(\"icr:\" + keyPrefix + \":\" + date);                // :35\n\n      return timestamp << COUNT_BITS | count;                              // :37\n  }\n\n位布局（64 位 long）：\n  ┌─────────────────────────────┬──────────────────────────────────┐\n  │  高 32 位：秒级时间戳差值     │  低 32 位：当日自增序列           │\n  │  (nowSecond - 1640995200)   │  INCR icr:{prefix}:{yyyy-MM-dd}  │\n  └─────────────────────────────┴──────────────────────────────────┘\n\n时间戳部分：1 秒 1 个刻度，32 位可表示 2^32 秒 ≈ 136 年 → 够用\n序列部分：  2^32 ≈ 42.9 亿 / 天 / 每个 prefix → 远超实际需求",
        },
        { t: "h", x: "3．追问 1：为什么基准时间是 2022-01-01" },
        {
          t: "p",
          x: "**为了让高 32 位装得下更长的时间跨度。** 64 位里必须切一块给时间戳、一块给序列号。如果从 1970 年（Unix 纪元）开始算，到 2026 年已经过去约 17.7 亿秒，需要 **31 位**才能表示，留给序列号的只剩 33 位中的 1 位——实际上会挤占序列空间。把基准点挪到项目开始的时间（2022-01-01），`timestamp` 从 0 开始，2026 年时约 1.5 亿秒，只需 **28 位**，32 位足够用到 2158 年。",
        },
        {
          t: "p",
          x: "这是**雪花算法类 ID 生成器的通用设计手法**：把「纪元」定在业务开始的时间而不是 1970，用最小的位数覆盖预期的生命周期，把省下的位留给序列号。`BEGIN_TIMESTAMP = 1640995200L` 正是 2022-01-01 00:00:00 UTC 的 Unix 秒数。",
        },
        { t: "h", x: "4．追问 2：时钟回拨（这是必问的坑）" },
        {
          t: "code",
          x: "当前实现：timestamp = nowSecond - BEGIN_TIMESTAMP，直接依赖系统时钟\n\n  假设 12:00:05 生成了 ID（timestamp = T+5）\n  此时 NTP 校时把系统时间回拨到 12:00:00（timestamp = T）\n  → 新生成的 ID 高 32 位变小，**ID 反而比之前生成的更小**\n\n后果（按严重程度）：\n  ① 若把 ID 当作「单调递增」使用（比如游标分页的 lastId、\n     「取比上次更大的 ID」做增量同步），会漏数据\n  ② 若同一秒内多次回拨跨秒，可能与更早的 ID 产生冲突（同一 timestamp 段\n     但序列号已经被 INCR 到更大值——实际上不会冲突，但顺序被打乱）\n  ③ 若时钟回拨跨天，date 变化 → 换了 key → INCR 从 1 重新开始，\n     而此时 timestamp 变小 → 可能生成与历史某条完全相同的 ID\n     ⚠ 这是真实的**主键冲突**风险（概率极低但存在）",
        },
        {
          t: "p",
          x: "**工业界对时钟回拨的标准处理**：① 检测到回拨时**拒绝生成并抛异常/等待**（雪花算法的经典做法：回拨 ≤5ms 就自旋等待，超过就直接报错，避免生成可能重复的 ID）；② 把「最后生成的时间戳」持久化（内存变量或 Redis），每次生成前比较，发现回拨立即拒绝；③ 使用不依赖本地时钟的方案（如数据库号段模式 `LEAF-segment`、或美团 Leaf-snowflake 用 ZooKeeper 兜底 WorkerId）。",
        },
        {
          t: "p",
          x: "**诚实的说法是**：本项目使用单机 Redis 且未做时钟回拨检测，风险来自「服务器时间被人工修改或 NTP 大幅校正」。生产环境应加上「与上次生成时间比较，回拨则拒绝」的保护逻辑。**能指出这个缺陷并说出标准解法，比声称「我的 ID 生成器没问题」要好得多。**",
        },
        { t: "h", x: "5．追问 3：序列号够不够 + 两个实现细节" },
        {
          t: "p",
          x: "**2^32 ≈ 42.9 亿 / 天 / 每个 prefix**，按秒杀场景每分钟 10 万单计算，一天 1.44 亿单，占用不到 4%。**完全够用，且有 30 倍余量。** 而且 key 里带了日期，每天自动重置，不会累积。",
        },
        {
          t: "p",
          x: "**细节一：时间戳用 UTC、日期用本地时区。** `:28` 的 `now.toEpochSecond(ZoneOffset.UTC)` 把本地墙上时间当作 UTC 来解释，`:33` 的 `date` 用 `now.format(...)` 按 JVM 默认时区格式化。两者都源自同一个 `LocalDateTime now`，所以内部自洽——但这个 ID 的含义是「本地时区墙上时间的伪 UTC 秒数」，**在不同时区的机器上生成的 ID 不可比**。对单机部署无影响，多机房部署时需要注意统一时区（与「用户板块 Q7」提到的时区问题是同一类根因）。",
        },
        {
          t: "p",
          x: "**细节二：强依赖 Redis 可用性。** `:35` 的 `INCR` 一旦 Redis 不可用就会抛异常，而秒杀下单是先 `nextId` 再执行 Lua（`VoucherOrderServiceImpl:96-101`）——**Redis 挂了秒杀必然失败**。这在架构上是可接受的（因为库存也在 Redis，Redis 挂了本来就没法卖），但要意识到：**这是一个「Redis 单点 = 全链路单点」的设计**，与「把库存放 Redis」的决策捆绑在一起。如果要解耦，可以用号段模式（一次取 1000 个 ID 缓存在本地，减少 Redis 依赖），代价是服务重启会浪费未用完的号段（造成 ID 空洞，但 ID 空洞无所谓）。",
        },
      ],
      sources: [
        "common/src/main/java/com/hmdp/utils/RedisIdWorker.java:11-42（全量）",
        "common/src/main/java/com/hmdp/entity/VoucherOrder.java:32（IdType.INPUT）",
        "common/src/main/java/com/hmdp/entity/SeckillVoucher.java:32（IdType.INPUT，voucher_id）",
        "common/src/main/java/com/hmdp/entity/Shop.java:38 / ShopType.java:33 / Blog.java:33 / User.java:32 / Follow.java:32（IdType.AUTO）",
        "order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java:96（nextId(\"order\") 调用点）",
      ],
    },

    // ---------------- Q3 ----------------
    {
      cat: "索引", level: "进阶",
      title: "项目里建了哪些索引？有没有查询用不到索引？你怎么用 EXPLAIN 定位慢查询？",
      focus: "考察点：索引清单的掌握、能否把索引与真实查询逐一对上、EXPLAIN 的实操细节（而不是背书）。",
      follows: [
        "tb_follow 上的两个单列索引够用吗？为什么不是联合索引？",
        "热门笔记按 liked 排序，这个查询能走索引吗？",
        "如果一张表现在有 5000 万行，你最担心哪个查询？",
      ],
      blocks: [
        { t: "h", x: "1．索引清单" },
        {
          t: "code",
          x: "docs/SQL/start.sql 中显式声明的索引（除主键外的全部）：\n\n  表                  索引名              列                  类型\n  --------------------------------------------------------------------\n  tb_follow           PRIMARY             (id)                主键\n                      idx_user_id         (user_id)           普通单列\n                      idx_follow_user_id  (follow_user_id)    普通单列\n\n  tb_shop_comments    PRIMARY             (id)                主键\n                      idx_blog_id         (blog_id)           普通单列\n                      idx_user_id         (user_id)           普通单列\n\n  tb_voucher_order    PRIMARY             (id)                主键\n                      idx_user_id         (user_id)           普通单列\n                      idx_voucher_id      (voucher_id)        普通单列\n\n  tb_shop             PRIMARY             (id)                主键\n                      idx_shop_id         (shop_id)           普通单列\n                      idx_user_id         (user_id)           普通单列\n\n  tb_sign / tb_shop_type / tb_voucher / tb_seckill_voucher / tb_user_info\n                      PRIMARY             (主键)               无二级索引\n\n  ⚠ 全库 0 个唯一索引（UNIQUE KEY），除主键约束外没有任何唯一性保护\n  ⚠ tb_blog / tb_user 的建表语句在仓库中缺失，其索引情况无法确认",
        },
        {
          t: "p",
          x: "**InnoDB 的索引结构是 B+ 树**：非叶子节点只存键值用于导航，叶子节点存完整行数据（聚簇索引）或主键值（二级索引），且叶子之间用双向链表相连——这正是「范围查询高效」和「按主键顺序扫描快」的原因。二级索引查询需要「回表」（先在二级索引找到主键，再回聚簇索引取整行），除非查询列全在索引里（覆盖索引）。",
        },
        { t: "h", x: "2．追问 1：索引与真实查询的逐条比对" },
        {
          t: "code",
          x: "查询                                                    用到的索引            评价\n  ------------------------------------------------------------------------------------------\n  FollowServiceImpl.isFollow:71                            idx_user_id          ✓ 命中\n    WHERE user_id = ? AND follow_user_id = ?\n\n  FollowServiceImpl.follow:56-57（取关）                    idx_user_id          ✓ 命中\n    WHERE user_id = ? AND follow_user_id = ?\n\n  BlogServiceImpl.saveBlog:178（查作者的全部粉丝）            idx_follow_user_id   ✓ 命中\n    WHERE follow_user_id = ?\n\n  Consumer:104-114（订单幂等：查是否已下单）                  idx_user_id          △ 命中但不优\n    WHERE user_id = ? AND voucher_id = ?\n    → 走 idx_user_id 后需要回表过滤 voucher_id\n    → 若建 (user_id, voucher_id) 联合索引可全程走索引\n\n  BlogServiceImpl.queryHotBlog:54                          ⚠ 未知（tb_blog DDL 缺失）\n    ORDER BY liked DESC LIMIT 10\n    → 若 liked 无索引 → 全表扫描 + filesort\n\n  ShopServiceImpl.queryShopByType:438-440                   ⚠ 未知（tb_shop DDL 与实体不符）\n    WHERE type_id = ? ORDER BY ?\n    → type_id 是否有索引无法从仓库确认",
        },
        {
          t: "p",
          x: "**最值得讲的优化点是订单幂等查询的那条联合索引。** 当前 `idx_user_id` 与 `idx_voucher_id` 是两个独立的单列索引，MySQL 在 `WHERE user_id = ? AND voucher_id = ?` 时通常只会选其中一个（选择区分度更高的），然后回表逐行过滤另一个条件。如果建 `UNIQUE KEY uk_user_voucher (user_id, voucher_id)`（这同时也解决了 Q4 的唯一约束问题），则：① 一次索引定位即可；② 不需要回表（如果只 select 计数）；③ **数据库层天然保证了「一人一单」这个核心不变量**。**「一个索引同时解决性能与正确性两个问题」是很漂亮的答案。**",
        },
        {
          t: "p",
          x: "**反过来也要说明「索引不是越多越好」**：每个索引都会增加写入成本（INSERT 时要维护所有索引）、占用磁盘、且优化器在索引过多时可能选错。像 `tb_shop_type`（分类表）这种数据量极小（几十行）、查询频繁的表，**全表扫描反而比走索引快**，不建索引是正确的。**能说出「什么时候不该建索引」，比只会背「给 where 和 order by 加索引」更能体现判断力。**",
        },
        { t: "h", x: "3．追问 2：ORDER BY 能不能用索引" },
        {
          t: "code",
          x: "BlogServiceImpl.queryHotBlog:54-56\n  query().orderByDesc(\"liked\").page(new Page<>(current, MAX_PAGE_SIZE));\n  → SQL:  SELECT ... FROM tb_blog ORDER BY liked DESC LIMIT ?, ?\n\n能否用索引，取决于两个条件：\n  ① WHERE 与 ORDER BY 的组合是否符合「最左前缀 + 有序性」\n     本例无 WHERE，纯粹按 liked 排序 → 需要 liked 上有索引（或 (liked) 为最左列的索引）\n  ② ⟺ 索引列顺序与 ORDER BY 一致，且排序方向一致（MySQL 8.0 支持降序索引，\n     8.0 以前对 DESC 排序可能无法直接利用升序索引反向扫描）\n\n→ 如果 liked 没有索引：EXPLAIN 会显示 filesort + type=ALL（全表扫描）\n   数据量 10 万行时，每次请求都要把全表读出来排序再取前 10 条，\n   这是最典型的「列表页慢查询」形态。\n\n→ 如果有 INDEX(liked)：可以走索引反向扫描，直接从尾部取 10 条，O(log N + 10)\n   但注意「热门榜」按 liked 排序有个业务特性：liked 会被高频更新，\n   索引维护成本高，且热点行的更新会争抢同一批索引页。\n\n工业界的替代方案（值得主动提）：\n  - 按时间窗口缓存榜单（如每 5 分钟用定时任务算一次热门榜写入 Redis ZSet），\n    彻底把排序从在线查询里移走 —— 这也正是社交模块 Redis ZSet 的用武之地\n  - 或对大数据量场景改用「热度分片」：把 (liked / 100) 做成桶，按桶索引",
        },
        { t: "h", x: "4．追问 3：EXPLAIN 实操（重点：要讲得出字段含义，不能背书）" },
        {
          t: "code",
          x: "EXPLAIN SELECT * FROM tb_follow WHERE user_id = 5 AND follow_user_id = 9;\n\n  字段          期望值          说明\n  -----------------------------------------------------------------------------\n  type          ref             访问类型。性能排序：\n                                system > const > eq_ref > ref > range > index > ALL\n                                看到 ALL 就是全表扫描，是最需要警惕的信号\n  key           idx_user_id     实际使用的索引（不是 possible_keys 里那几个候选）\n  key_len       8               索引使用的字节数。bigint 非空 = 8；\n                                若 key_len 只有 8 而条件有两列，说明只用到了第一列\n                                → 这是判断「联合索引用了几列」的关键技巧\n  rows          ~1              预估扫描行数，越小越好\n  filtered      10.00           过滤后剩余的百分比\n  Extra         Using index     覆盖索引，无需回表\n                Using where     在存储引擎返回后还要过滤\n                Using filesort  ⚠ 需要额外排序，无法利用索引有序性\n                Using temporary ⚠ 用到临时表，常见于 GROUP BY / DISTINCT\n                Using join buffer  被驱动表没有可用索引\n\n重点：\n  - 只看 type=ALL / index + Extra 里有 filesort / temporary 这三类信号\n  - rows 的乘积（多表 join 时各表 rows 相乘）估算实际扫描量\n  - EXPLAIN ANALYZE（MySQL 8.0.18+）会真正执行并给出各步骤实际耗时，比 EXPLAIN 更准\n  - 想看优化器成本明细：EXPLAIN FORMAT=JSON",
        },
        {
          t: "p",
          x: "**要如实说明的一点**：仓库里**没有任何 EXPLAIN 的输出记录或慢查询日志分析产物**（全仓检索 `EXPLAIN` / `slow_query_log` → 无相关文件），简历上「能用 EXPLAIN 分析慢查询并优化」目前是**能力声明而非项目实证**。不要编造「我通过 EXPLAIN 优化了多少条慢 SQL」——**正确的做法是说「我知道怎么用，项目里目前的数据量还没触发慢查询，所以没有留下分析记录，但我知道该看哪几个字段」**，然后现场把上面那张表讲清楚。**面试官更在意你能不能说清 `key_len` 和 `Extra` 的含义，而不是你有没有跑过。**",
        },
        {
          t: "p",
          x: "另外要补充**索引失效的常见场景**（高频追问）：① 对索引列做函数运算或类型转换（`WHERE DATE(create_time) = '2026-10-06'`）；② 隐式类型转换（字符串列用数字查询，或反之）；③ 前导模糊匹配（`LIKE '%abc'` 能用 `LIKE 'abc%'` 不能用）；④ OR 连接的条件中有一侧无索引；⑤ 联合索引不满足最左前缀；⑥ `!=` / `NOT IN` / `IS NOT NULL` 在低区分度列上，优化器可能直接放弃索引。**本项目里 `BlogServiceImpl:221` 与 `ShopServiceImpl` 的 `ORDER BY FIELD(id, ...)` 属于第 ① 类的变体**——对列施加了函数，无法利用索引有序性（但如社交板块 Q7 所述，那里 id 列表只有 2~10 个，实际影响很小）。",
        },
      ],
      sources: [
        "docs/SQL/start.sql:35-43（tb_follow）、:80-94（tb_shop_comments）、:97-111（tb_voucher_order）、:63-77（tb_shop）",
        "social-service/src/main/java/com/hmdp/social/service/impl/FollowServiceImpl.java:56-57,71",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:54-56,178,221",
        "order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java:104-114（订单幂等查询）",
        "shop-service/src/main/java/com/hmdp/shop/service/impl/ShopServiceImpl.java:438-440,488",
        "全仓检索 EXPLAIN / slow_query_log → 无慢查询分析产物",
      ],
      gap: {
        claim:
          "简历「个人优势」：「数据库：熟悉 MySQL，理解 B+ 树索引与事务隔离级别，能用 EXPLAIN 分析慢查询并优化。」",
        fact:
          "源码实证：仓库中没有任何 EXPLAIN 的执行输出、没有慢查询日志（slow_query_log）配置或分析产物；同时代码中存在若干**尚未验证是否走索引**的查询——tb_blog 按 liked 排序（BlogServiceImpl:54）、tb_shop 按 type_id 过滤（ShopServiceImpl:438），而这两张表的 DDL 在仓库中缺失或与实体不符，无法从代码判断索引情况。",
        advice:
          "把这句改述为**能力声明**而非项目成果：「理解 EXPLAIN 的关键字段（type / key / key_len / rows / Extra），能据此判断索引是否命中、是否存在 filesort 与临时表」。面试时如果被追问「你优化过哪条慢 SQL」，如实说「项目数据量还没触发慢查询，所以没有留下分析记录」，然后**现场把 key_len 判断联合索引使用列数、Extra 出现 Using filesort 意味着什么讲清楚**——面试官在意的是你会不会看，而不是你有没有跑过。",
      },
    },

    // ---------------- Q4 ----------------
    {
      cat: "约束设计", level: "进阶",
      title: "「一人一单」和「不能重复关注」这两条业务不变量，数据库层有保证吗？",
      focus: "考察点：把业务不变量下沉到数据层的意识、唯一约束与分布式锁的职责划分、能否发现「Redis 去重掩盖了 DB 脏数据」。",
      follows: [
        "为什么说「应用层判重靠不住」？举一个具体的失败场景。",
        "加了唯一索引之后，插入冲突了要怎么办？直接抛异常给用户吗？",
        "唯一索引会不会影响性能？和高并发写入有冲突吗？",
      ],
      blocks: [
        { t: "h", x: "1．两条不变量的当前防线" },
        {
          t: "code",
          x: "不变量一：一个用户对同一张秒杀券只能下一单\n  防线 1（主）：Redis Lua 里的 SISMEMBER 判重\n      order-service/src/main/resources/seckill.lua\n          if redis.call('SISMEMBER', orderKey, userId) == 1 then return 2 end\n  防线 2（次）：Consumer 落库前的 selectCount 幂等检查\n      SeckillOrderConsumer.java:104-114\n          selectCount(userId, voucherId) > 0 → rollbackRedisData + return\n  防线 3（DB）：❌ 无\n      docs/SQL/start.sql:97-111  tb_voucher_order\n          PRIMARY KEY (id), KEY idx_user_id (user_id), KEY idx_voucher_id (voucher_id)\n          ⚠ 没有 UNIQUE (user_id, voucher_id)\n\n不变量二：一个用户对同一个用户只能关注一次\n  防线 1：Redis Set 天然去重（FollowServiceImpl:52 的 SADD）\n  防线 2：❌ 无\n  防线 3（DB）：❌ 无\n      docs/SQL/start.sql:35-43  tb_follow\n          PRIMARY KEY (id), KEY idx_user_id, KEY idx_follow_user_id\n          ⚠ 没有 UNIQUE (user_id, follow_user_id)",
        },
        { t: "h", x: "2．追问 1：为什么说应用层判重靠不住" },
        {
          t: "p",
          x: "**因为应用层的「判重」本质是「先查后写」，而这两个动作之间永远存在时间窗口。** 无论是 Redis 的 `SISMEMBER → SADD`、还是 MySQL 的 `SELECT COUNT → INSERT`，在并发下都可能两个请求同时查询到「不存在」，然后各自写入一次。要消除这个窗口，只有两条路：**要么把「查+写」做成原子操作（Redis 的单命令/Lua），要么把唯一性下沉到数据库（唯一索引）。**",
        },
        {
          t: "p",
          x: "具体到本项目，有三个真实场景会让 Redis 这道防线失效：",
        },
        {
          t: "code",
          x: "场景 A：Redis 数据丢失\n  秒杀券的 seckill:order:{voucherId} 这个 Set 没有持久化保证\n  → Redis 重启 / 误删 / 主从切换丢失数据\n  → 所有用户的「已购」记录清空，用户可以再次下单\n  → **数据库里没有任何约束能拦住他** —— 同一用户产生多张订单\n\n场景 B：Redis 与 DB 不一致\n  Lua 预扣成功（Set 里有记录）但 MQ 发送失败\n  （VoucherOrderServiceImpl:118-124 只记日志 + 打点，不回滚 Redis）\n  → 用户「占坑」但没订单，后续重试被 SISMEMBER 拦住 → 少卖\n  反向场景：Consumer 回滚 Redis 成功但 DB 已插入 → 用户可重复下单\n\n场景 C：代码路径绕过\n  任何不经过 Lua 的写入路径（后台补单、数据修复脚本、\n  未来新增的「运营赠送券」接口）都不会维护那个 Set\n  → Redis 判重形同虚设",
        },
        {
          t: "p",
          x: "**核心论点：Redis 是「加速层」，不应该是「唯一防线」。** 它可以承担 99.99% 的判重压力（性能必须这么做），但剩下的 0.01% 必须由数据库兜底——**因为数据库是数据最后的归宿，如果它自己都不保证唯一性，那就没有任何东西保证了。**",
        },
        { t: "h", x: "3．加唯一索引后冲突怎么办（这是关键的后半问）" },
        {
          t: "code",
          x: "方案一：捕获唯一键冲突，转为幂等成功（推荐给「一人一单」）\n\n  ALTER TABLE tb_voucher_order\n    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);\n\n  try {\n      orderMapper.insert(order);\n  } catch (DuplicateKeyException e) {\n      // 说明该用户已经下过单 —— 不是错误，是「重复请求」\n      log.warn(\"重复下单已被数据库拦截: userId={}, voucherId={}\", userId, voucherId);\n      rollbackRedisData(voucherId, userId);   // 把 Redis 预扣回滚掉\n      return;                                  // 按成功处理（幂等）\n  }\n\n  → 语义：数据库说「已经有了」= 目标状态已达成 = 成功\n  → 这正是幂等设计的标准手法：把「冲突」识别为「已完成」\n\n方案二：INSERT IGNORE / ON DUPLICATE KEY UPDATE\n  INSERT IGNORE INTO tb_voucher_order ...     → 冲突时静默跳过，affected rows = 0\n  → 需要判断影响行数来决定后续逻辑，可读性不如方案一\n\n⚠ 不要做的事：把 DuplicateKeyException 直接抛给用户\n   → 用户看到 500，无法区分「系统故障」与「你已经买过了」\n   → 体验差，且掩盖了真正的链路问题",
        },
        {
          t: "p",
          x: "**加唯一索引的代价要与性能一起讨论**（这是面试官喜欢追问的点）：唯一索引在 INSERT 时需要额外做一次唯一性检查，高并发写入同一组 (user_id, voucher_id) 时可能产生**锁等待甚至死锁**。但在「一人一单」这个场景下，**冲突本来就是罕见事件**（Redis 前置拦截了 99.99% 的重复请求），所以这个代价几乎可以忽略。**真正的风险是「如果 Redis 防线设计得不好、大量请求穿透到 DB，唯一索引就会成为热点瓶颈」**——所以正确的关系是「Redis 挡流量、DB 保正确」，两者缺一不可，而不是二选一。",
        },
        { t: "h", x: "4．追问 2：为什么现在没加（要能说清原因，而不是辩解）" },
        {
          t: "p",
          x: "**最可能的实际原因是「没有意识到需要」**——因为功能测试时（低并发、无 Redis 故障）表现完全正常，加不加唯一索引看不出区别。**这正是这类缺陷最危险的地方：它不是「功能没做」，而是「保护没做」，在正常情况下完全隐形。**",
        },
        {
          t: "p",
          x: "另外还有一个技术上的顾虑值得提：`tb_voucher_order.id` 是 `IdType.INPUT`（应用生成的订单号，见 Q2），所以主键本身不承担任何业务含义；而 `(user_id, voucher_id)` 唯一索引只是**业务约束**。如果未来业务变成「一个用户对同一张券可以买多份」（比如限制每人限购 3 张），这个唯一索引就要改成「计数约束」，那就无法用唯一索引表达了——**届时需要换成「Lua 里的计数器 + DB 侧的 (user_id, voucher_id, seq) 唯一索引」**。能主动指出「唯一约束表达的是当前业务规则，规则变了约束也要变」，说明理解的是设计意图而不只是背索引语法。",
        },
      ],
      sources: [
        "docs/SQL/start.sql:97-111（tb_voucher_order，无 UNIQUE(user_id, voucher_id)）",
        "docs/SQL/start.sql:35-43（tb_follow，无 UNIQUE(user_id, follow_user_id)）",
        "order-service/src/main/resources/seckill.lua（SISMEMBER 判重）",
        "order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java:86-142（RLock + selectById + selectCount 三道应用层防线）",
        "order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java:103-124（Lua 结果判断 + MQ 发送失败不回滚）",
        "social-service/src/main/java/com/hmdp/social/service/impl/FollowServiceImpl.java:40-64（先查 Redis 再写 DB）",
      ],
    },

    // ---------------- Q5 ----------------
    {
      cat: "映射一致性", level: "进阶",
      title: "仓库里的建表语句和实体类对得上吗？如果对不上，运行时会出什么问题？",
      focus: "考察点：能否把 DDL 与实体逐字段对照（而不是想当然）、对 MyBatis-Plus 映射机制的掌握、遇到不一致时的处理方法（核对而非猜测）。",
      follows: [
        "UserInfo 实体的 @TableId 是 user_id，但 DDL 的主键列是 id，会怎样？",
        "布尔类型映射 tinyint 有什么坑？",
        "怎么确认线上跑的表结构和仓库里的 SQL 文件是否一致？",
      ],
      blocks: [
        { t: "h", x: "1．问题一：tb_user_info 主键列名不一致" },
        {
          t: "code",
          x: "实体  common/src/main/java/com/hmdp/entity/UserInfo.java:33\n  @TableId(value = \"user_id\", type = IdType.AUTO)\n  private Long userId;\n\nDDL   docs/SQL/start.sql:114-127\n  CREATE TABLE `tb_user_info` (\n      `id` bigint NOT NULL COMMENT '主键',        ← 列名是 id，且没有 AUTO_INCREMENT\n      `city` varchar(50) ...\n      ...\n      PRIMARY KEY (`id`)\n  );\n\n→ 表里**没有 user_id 这一列**。\n\n受影响接口：\n  UserController.info:78-90   GET /user/info/{id}\n      userInfoService.getById(userId)\n      → MyBatis-Plus 生成： SELECT ... FROM tb_user_info WHERE user_id = ?\n      → MySQL 报错： Unknown column 'user_id' in 'where clause'\n      → 500",
        },
        {
          t: "p",
          x: "**这个不一致有两种可能的解释，必须区分开**：① DDL 文件是旧版本、线上实际的表已经是 `user_id` 列（那么 SQL 文件过期，应更新文件）；② 线上表真的是 `id` 列（那么 `/user/info/{id}` 这个接口**在当前代码下必然报错**）。**从仓库无法判断是哪一种——这正是一个必须「核对而非猜测」的典型场景。**",
        },
        {
          t: "p",
          x: "**正确的排查方法**（面试时讲这个比直接下结论更专业）：`SHOW CREATE TABLE tb_user_info;` 或查 `information_schema.columns`；确认后二选一修：若表是 `id`，改实体为 `@TableId(value = \"id\")` 并把字段名调整为 `id`；若表是 `user_id`，更新 DDL 文件。**注意 `tb_user_info` 的主键语义确实是「用户 id」，所以列名叫 `user_id` 更合理——但前提是表真的这么建。**",
        },
        { t: "h", x: "2．问题二：DDL 大面积缺少 AUTO_INCREMENT" },
        {
          t: "code",
          x: "逐表对照（DDL 中主键是否声明 AUTO_INCREMENT vs 实体是否声明 IdType.AUTO）：\n\n  DDL 表名              主键定义                      AUTO_INCREMENT   对应实体     实体策略\n  --------------------------------------------------------------------------------------------------\n  tb_sign               id bigint unsigned NOT NULL   ❌ 无             —            无实体\n  tb_shop_type          id bigint unsigned NOT NULL   ❌ 无            ShopType     IdType.AUTO  ⚠冲突\n  tb_follow             id bigint NOT NULL            ❌ 无            Follow       IdType.AUTO  ⚠冲突\n  tb_voucher            id bigint unsigned NOT NULL   ❌ 无            Voucher      IdType.AUTO  ⚠冲突\n  tb_shop               id bigint NOT NULL            ❌ 无            Shop         IdType.AUTO  ⚠冲突\n  tb_shop_comments      id bigint NOT NULL            ❌ 无            BlogComments IdType.AUTO  ⚠冲突\n  tb_voucher_order      id bigint NOT NULL            ❌ 无            VoucherOrder IdType.INPUT ✓（应用生成，不需要自增）\n  tb_seckill_voucher    voucher_id bigint unsigned…   ❌ 无            SeckillVoucher IdType.INPUT ✓\n  tb_user_info          id bigint NOT NULL            ❌ 无            UserInfo     IdType.AUTO  ⚠冲突（且列名也不对）\n\n→ 除了两个 INPUT 策略的，其余全部存在冲突。",
        },
        {
          t: "p",
          x: "**如果 DDL 忠实反映了线上表结构，那么在 `IdType.AUTO` 下 MyBatis-Plus 会生成不带 id 的 INSERT（指望数据库自增），而列没有 AUTO_INCREMENT 且 NOT NULL 无默认值 → MySQL 报错 `Field 'id' doesn't have a default value`**（严格模式），或者被静默填 0（非严格模式，`sql_mode` 未开 `STRICT_TRANS_TABLES` 时），后者会导致第二条插入就主键冲突。",
        },
        {
          t: "p",
          x: "**但功能显然是能跑通的（项目有大量测试且据称通过）**，所以更合理的推断是：**`docs/SQL/start.sql` 是一个不完整/手工整理过的参考脚本，并非线上真实 DDL**。这个判断本身值得在面试中说出来——**「仓库里的 SQL 文件不可信」这个结论，比纠结某一行对不对更重要。** 同时给出一条改进建议：把建表脚本纳入 Flyway/Liquibase 版本管理，让「代码里的表结构」和「仓库里的脚本」永远同步（本项目已有 `sql/phase3-voucher-fields.sql`、`sql/phase4-*.sql` 这类增量脚本，说明有这个意识，只是基础 DDL 没有一起维护）。",
        },
        { t: "h", x: "3．问题三：Boolean 映射 tinyint 的语义丢失" },
        {
          t: "code",
          x: "案例 A：UserInfo.level —— 0~9 级的会员等级被压成布尔\n\n  DDL  docs/SQL/start.sql:123\n       `level` tinyint DEFAULT '0' COMMENT '会员级别：0-未开通，1-9级'\n\n  实体 common/.../entity/UserInfo.java:74\n       private Boolean level;\n\n  → 数据库里 level = 5（5 级会员）\n  → MyBatis 用 BooleanTypeHandler 读取：rs.getBoolean(\"level\")\n     底层是 rs.getInt() != 0 → 5 != 0 → true\n  → Java 侧拿到 true，**「5 级」这个信息永久丢失**\n  → 再写回时：true → 1 → 用户从 5 级掉到 1 级\n\n案例 B：BlogComments.status —— 三态被压成两态\n\n  DDL  docs/SQL/start.sql:88\n       `status` tinyint DEFAULT '0' COMMENT '状态：0-正常，1-被举报，2-禁止查看'\n\n  实体 common/.../entity/BlogComments.java:68\n       private Boolean status;\n\n  → status = 1（被举报）和 status = 2（禁止查看）都映射为 true\n  → **无法区分「被举报」与「禁止查看」两种业务状态**\n\n对照案例：BlogComments.liked 用 Integer（点赞数）✓ 正确\n         Shop.score 用 Integer 且注释「评分 1~5 分，乘 10 保存，避免小数」✓ 正确\n         —— 说明作者在有意识地避免小数与枚举的精度问题，只是 level/status 这两处没做到",
        },
        {
          t: "p",
          x: "**根因是「Java 类型选择必须能承载数据库列的取值域」**：`Boolean` 只能表达两态，任何取值超过 2 种的列都不该用它。正确做法：① `level` 用 `Integer`（或自定义枚举 + `@EnumValue`）；② `status` 用 `Integer` 或枚举（如 `CommentStatus.REPORTED`）。**用枚举更好，因为能把「0/1/2 是什么意思」固化在类型里，避免各处散落的魔法数字**——这也是当前代码里 `VoucherOrder.status`（1~6 六态，用 Integer + 注释）面临的同一个问题。",
        },
        {
          t: "p",
          x: "顺带补充一个常被追问的坑：**`tinyint(1)` 是 MyBatis/MySQL 生态里最容易混淆的类型**——MySQL 的 `tinyint(1)` 只是「显示宽度为 1」，语义上仍是 tinyint，但很多 ORM（包括 MySQL Connector/J 在 `tinyInt1isBit=true` 默认配置下）会把 `tinyint(1)` 当作 BOOLEAN 处理。这类问题在字段宽度写成 `tinyint(1)` 时特别容易踩，本项目 DDL 里写的是 `tinyint`（无宽度）和 `tinyint unsigned`，不受这个默认行为影响。",
        },
        { t: "h", x: "4．问题四：tb_shop 的 DDL 与实体完全不符" },
        {
          t: "code",
          x: "DDL  docs/SQL/start.sql:63-77\n  CREATE TABLE `tb_shop` (\n      `id` bigint NOT NULL,\n      `shop_id` bigint NOT NULL,          ← 商铺的 id？但它自己就是 tb_shop\n      `user_id` bigint NOT NULL,\n      `title` varchar(255) NOT NULL,      ← 「标题」\n      `images` varchar(1024) COMMENT '多张用\"|\"隔开',\n      `content` text COMMENT '评论内容',   ← 「评论内容」\n      `likes` int DEFAULT '0',            ← 「点赞数量」\n      `comments` int DEFAULT '0',\n      ...\n  );\n\n实体  common/src/main/java/com/hmdp/entity/Shop.java:30-118\n  @TableName(\"tb_shop\")\n  id / name / typeId / images / area / address / x / y / avgPrice\n     / sold / comments / score / openHours / createTime / updateTime / distance(非表字段)\n\n对照结论：\n  - 实体有 name/typeId/area/address/x/y/avgPrice/sold/score/openHours\n    → DDL 中**一个都没有**\n  - DDL 有 shop_id/user_id/title/content/likes\n    → 实体中**一个都没有**\n  - 两边的列集合**几乎完全不相交**，形态上 DDL 更像一张「博客表」\n\n同一问题的另一半：DDL 里的 tb_shop_comments（:80-94）\n  列是 user_id / blog_id / parent_id / answer_id / content / liked / status\n  → 与 BlogComments 实体完全一致，但实体的 @TableName 是 \"tb_blog_comments\"\n  → 说明这是一次「表改名 + 内容错位」后 DDL 未同步",
        },
        {
          t: "p",
          x: "**这段分析的价值不在于「找到了 bug」，而在于展示一种工作方法**：拿到一份 SQL 脚本，第一件事是**与实体逐字段对照**，而不是默认它是对的。DDL 与实体不一致的后果很严重（查询报错、插入失败、字段静默丢失），但排查起来往往要绕很远——因为报错信息指向的是 SQL，而不是「你的 DDL 文件过期了」。",
        },
        {
          t: "p",
          x: "**面试时的建议表述**：「仓库里的 docs/SQL/start.sql 与实体定义存在多处不一致，我无法从代码判断线上真实的表结构是哪一版。如果是线上表为准，说明脚本过期需要更新；如果是脚本为准，那么有几个接口在当前代码下会直接报错。我的判断是脚本是手工整理过的参考资料，已知的增量脚本（sql/phase3-voucher-fields.sql 等）倒是与代码同步的。」——**诚实 + 给出判断依据 + 给出改进方案，这是最好的姿态。**",
        },
      ],
      sources: [
        "common/src/main/java/com/hmdp/entity/UserInfo.java:33,59,74（user_id / gender / level）",
        "common/src/main/java/com/hmdp/entity/BlogComments.java:32,68（AUTO / status Boolean）",
        "common/src/main/java/com/hmdp/entity/Shop.java:30-118（完整字段列表）",
        "docs/SQL/start.sql:63-77（tb_shop DDL）、:80-94（tb_shop_comments DDL）、:114-127（tb_user_info DDL）、其余各表",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:78-90（受影响的 /user/info/{id}）",
        "sql/phase3-voucher-fields.sql、sql/phase4-*.sql（与代码同步的增量脚本对照）",
      ],
    },

    // ---------------- Q6 ----------------
    {
      cat: "表设计评审", level: "深入",
      title: "如果让你评审这套表设计，你会提出哪些改进？",
      focus: "考察点：从「能跑」到「规范」的评审视角、字段类型与命名约定的敏感度、对逻辑删除与时区这类工程细节的掌握。",
      follows: [
        "为什么建议用逻辑删除而不是物理删除？代价是什么？",
        "images 字段用分隔符存多个图片，这样设计有什么问题？",
        "出参脱敏你更倾向于 DTO 还是 @JsonIgnore？",
      ],
      blocks: [
        { t: "h", x: "1．命名与注释不一致" },
        {
          t: "code",
          x: "① 表名与实体名不一致（详见 Q5）\n     DDL: tb_shop_comments  ↔  实体 @TableName(\"tb_blog_comments\")\n\n② VoucherOrder.status 的语义在两边不一致\n     DDL    docs/SQL/start.sql:102\n            '订单状态：1-未支付，2-已支付，3-已撤销，4-已取消，5-退款中，6-已退款'\n     实体   common/.../entity/VoucherOrder.java\n            '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款'\n                     ↑ status=3 到底是「已撤销」还是「已核销」？\n     → 这两个状态在业务上完全不同（撤销 = 用户主动放弃；核销 = 到店消费），\n       注释互相矛盾意味着必然有一方是错的，且没有任何代码或枚举能仲裁\n\n③ 同一语义的字段在不同表里命名不同\n     tb_follow.follow_user_id   vs   其他表的 user_id\n     tb_shop.shop_id（指向什么？） vs  tb_shop 自己的主键 id\n\n④ 图片分隔符约定不统一\n     tb_shop DDL:63-77   COMMENT '多张用\"|\"隔开'\n     Shop 实体:53-54      「商铺图片，多个图片以','隔开」\n     Blog 实体:65-66      「探店的照片，最多9张，多张以\",\"隔开」\n     → 同一个项目里两种分隔符约定，且 DDL 与实体对同一张表给出不同答案\n     → ⚠ 前端按一种约定拆分后拿到另一种数据，会解析出错误的图片列表",
        },
        {
          t: "p",
          x: "**改进建议：把枚举/约定的定义收口到一处**——状态码用 Java 枚举（`@EnumValue` + MyBatis-Plus 的 `IEnum` 支持），分隔符约定用常量或注解，注释与代码由同一个来源生成。**「同一个事实只有一个定义」是消除这类不一致的根本方法。**",
        },
        { t: "h", x: "2．缺少逻辑删除" },
        {
          t: "code",
          x: "全仓检索 @TableLogic / deleted / is_deleted → 0 命中\n所有 DDL 也都没有 deleted 标志列\n\n→ 当前所有删除都是物理删除：\n     FollowServiceImpl.follow:56-57  remove(new QueryWrapper<Follow>()...)  → DELETE FROM tb_follow\n\n问题：\n  ① 数据不可恢复 —— 用户误取关、误删笔记，无法找回\n  ② 无法审计 —— 谁在什么时候删了什么，没有痕迹\n  ③ 外键/关联数据失联 —— 删了 blog 行，tb_shop_comments 里的 blog_id 变成悬空引用\n  ④ 与「一人一单」类约束的交互变复杂 —— 物理删除后用户又能再下一单，\n     如果业务上「取消订单」要占用名额，就说不清了\n\n改进：MyBatis-Plus 原生支持逻辑删除，成本极低：\n  DDL:  ALTER TABLE tb_follow ADD COLUMN deleted tinyint NOT NULL DEFAULT 0;\n  实体: @TableLogic private Integer deleted;\n  → 之后所有 remove() 自动变成 UPDATE ... SET deleted = 1\n    所有 query() 自动追加 WHERE deleted = 0\n  → 零改造接入，无需改业务代码",
        },
        {
          t: "p",
          x: "**但要说清代价**（这是追问的方向）：① **唯一索引会失效**——`UNIQUE(user_id, follow_user_id)` 加上逻辑删除后，用户取关再关注会撞唯一键（旧行还在，只是 deleted=1），需要把唯一键改成 `(user_id, follow_user_id, deleted)` 或用「删除时间戳」列代替布尔值（`deleted_at` 为 NULL 表示未删除，唯一索引对 NULL 不生效——这是更优雅的解法）；② **所有查询都要带 `WHERE deleted = 0`**（MP 自动追加，但手写 SQL 会漏）；③ **表会持续膨胀**，需要归档任务；④ 对高频写入的表（如博客点赞明细）逻辑删除的写入放大更明显。",
        },
        {
          t: "p",
          x: "**是否该用取决于业务**：用户、订单、券这些有审计与合规要求的表**应该用**；签到流水、日志型数据可以定期物理清理。**本项目里 tb_follow（关注关系）与 tb_blog（笔记）是最需要逻辑删除的两张表**，因为误操作的成本很高而数据量不大。",
        },
        { t: "h", x: "3．字段类型与存储" },
        {
          t: "code",
          x: "① 大字段混在热表\n     tb_shop.content / tb_shop_comments.content 用 text\n     → text 列在行溢出时会存到溢出页，主键索引随之变大、扫描变慢\n     → 建议：评论正文拆到 tb_comment_content(comment_id, content)，\n       或限制长度用 varchar(2000)；同时避免 SELECT *（当前 Wrapper 查询\n       未做列裁剪，会连同 text 一起取出）\n\n② 时间字段统一用 timestamp\n     各表 create_time/update_time 均为 timestamp DEFAULT CURRENT_TIMESTAMP\n     → timestamp 上限是 2038-01-19（32 位），之后会溢出\n     → 建议改用 datetime（8 字节，范围到 9999 年）\n     → 但 datetime 不会自动带时区语义，需要应用层保证\n\n③ 金额用 bigint 存「分」✓ 正确\n     tb_voucher: `pay_value` bigint COMMENT '支付金额（分）'\n                 `actual_value` bigint COMMENT '抵扣金额（分）'\n     → 用整数分存储、避免浮点误差，这是正确做法，值得主动指出\n       （对照：Shop.score 也用 Integer「乘 10 保存，避免小数」）\n\n④ 缺少字段长度与精度的校验\n     tb_shop_comments.content 是 text 无长度限制，\n     但 UserInfo.introduce 是 varchar(128) 且实体注释「不要超过128个字符」\n     → 应用层没有 @Size 校验，超长会直接抛 SQLException\n\n⑤ 主键类型不统一\n     部分表 id 是 bigint unsigned，部分是 bigint（有符号）\n     实体统一用 Long（有符号）\n     → unsigned bigint 在 Java 侧 Long 无法表达 2^63 以上的值，\n       且不同表 join 时类型不一致可能阻碍索引使用\n     → 建议统一为 bigint（有符号）",
        },
        { t: "h", x: "4．追问 1：出参脱敏，DTO 还是 @JsonIgnore" },
        {
          t: "code",
          x: "项目里两种做法都存在，效果却不同：\n\n  做法一：@JsonIgnore（黑名单）—— ShopType 实体就是这么做的\n      common/.../entity/ShopType.java\n          @JsonIgnore private LocalDateTime createTime;\n          @JsonIgnore private LocalDateTime updateTime;\n      ✓ 简单，不用额外类\n      ✗ 新增敏感字段时必须记得加注解，否则泄露\n      ✗ 只影响 JSON 序列化，DTO 拷贝、日志打印、缓存写入仍然带着该字段\n\n  做法二：DTO（白名单）—— UserDTO 是这么做的\n      common/.../dto/UserDTO.java  只有 id / nickName / icon 三个字段\n      ✓ 新增字段默认不可见，必须显式添加 —— 安全默认（secure by default）\n      ✓ DTO 可以裁剪成「这个接口恰好需要的字段」，避免过度返回\n      ✗ 需要多写一个类与转换代码\n\n  做法三：手工置空（最差）—— UserInfo 是这么做的\n      user-service/.../UserController.java:86-87\n          info.setCreateTime(null);\n          info.setUpdateTime(null);\n      ✗ 靠一行行 setNull，漏一个就泄露\n      ✗ 直接修改了实体对象（如果该对象还被别处引用，会误伤）\n\n结论：**对外接口一律用 DTO（白名单）；实体内部字段如果连日志都不该出现\n      （如 password），再加 @JsonIgnore 与 @ToString.Exclude 双保险。**\n\n  ⚠ 当前 User 实体（User.java:43）的 password 字段**既没有 @JsonIgnore\n    也没有 @ToString.Exclude** —— 目前没有接口直接返回 User，所以没泄露；\n    但一旦有人写了个「返回 User」的接口，密码哈希（甚至明文，如果未来\n    密码登录实现时忘了加密）就会进入响应体和日志。这是应当立即补上的防御。",
        },
        { t: "h", x: "5．追问 2：单机数据库的容量与扩展" },
        {
          t: "p",
          x: "当前是单实例 MySQL，无主从、无分库分表。**要主动说明这台机器的上限与扩展路径**：",
        },
        {
          t: "p",
          x: "**读扩展**：一主多从 + 读写分离。订单查询、商户查询这些读多写少的走从库，写入走主库。代价是主从延迟——「刚下单立刻查订单」可能查不到，需要「写后读主」或按业务容忍度处理。项目里 `queryMyOrders` 这类查询就是典型受益者。",
        },
        {
          t: "p",
          x: "**写扩展**：分库分表（ShardingSphere）。按 `user_id` 取模分片是最常用的方式（保证同一用户的订单落在同一分片，避免跨分片查询）。**代价是：自增主键失效（正好呼应 Q2 为什么要用 RedisIdWorker 生成 ID）、跨分片查询与聚合变复杂、分布式事务、全局唯一索引无法简单保证。**",
        },
        {
          t: "p",
          x: "**归档**：订单表是典型的「热数据少、冷数据多」——用户只会查最近几个月。把 1 年前的订单归档到 `tb_voucher_order_history` 或对象存储，主表只保留热数据，是最低成本、最高收益的优化。**很多时候「加机器/分库分表」之前，先看看有没有历史数据可以归档。**",
        },
        {
          t: "p",
          x: "**但也要给出判断**：本项目单机 8 核 16G，数据量在百万级以内，**当前没有任何一条理由需要分库分表**——过早分片会带来远超收益的复杂度。**「知道怎么扩展」和「知道现在不该扩展」同样重要**，这是面试官很看重的分寸感。",
        },
      ],
      sources: [
        "docs/SQL/start.sql:63-77（tb_shop images 分隔符 '|'）、:80-94（tb_shop_comments text/status）、:97-111（tb_voucher_order status 注释）、:47-60（tb_voucher 金额用 bigint 分）",
        "common/src/main/java/com/hmdp/entity/ShopType.java（@JsonIgnore 做法）",
        "common/src/main/java/com/hmdp/entity/Shop.java:53-54,68-70（images 逗号分隔、score 乘 10）",
        "common/src/main/java/com/hmdp/entity/Blog.java:65-66（images 逗号分隔）",
        "common/src/main/java/com/hmdp/entity/User.java:43（password 无 @JsonIgnore）",
        "common/src/main/java/com/hmdp/dto/UserDTO.java:6-10（DTO 白名单做法）",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:86-87（手工 setNull 做法）",
        "全仓检索 @TableLogic / deleted → 0 命中（无逻辑删除）",
      ],
    },

    // ---------------- Q7 ----------------
    {
      cat: "数据一致性", level: "深入",
      title: "tb_shop.likes、tb_blog.liked、comments 这些计数字段是怎么维护的？会出现不一致吗？",
      focus: "考察点：冗余设计的取舍、计数器与明细表的一致性方案、能否识别出「有的维护了、有的完全没维护」这种半成品状态。",
      follows: [
        "为什么不在查询时实时 COUNT，非要冗余一个计数字段？",
        "如果计数不准了，怎么校准？",
        "秒杀的库存 stock 字段也是类似的计数，它的一致性怎么保证？",
      ],
      blocks: [
        { t: "h", x: "1．三类计数字段的现状盘点" },
        {
          t: "code",
          x: "计数列                             维护方式                        一致性\n  ---------------------------------------------------------------------------------------------\n  tb_blog.liked                      ✓ 点赞时 UPDATE liked = liked ± 1    △ 有维护，但会漂移\n     实体 Blog.java:77                BlogServiceImpl.java:110,118\n     写入口: likeBlog / dislikeBlog\n\n  tb_blog.comments                   ❌ 完全没有维护                     ✗ 永远不变\n     实体 Blog.java:82                BlogCommentsServiceImpl.saveComment\n                                     （只 save 评论，不更新计数）\n\n  tb_shop.comments                   ❌ 没有评论写入路径                 —\n     实体 Shop.java:65                （/blog/comments 写的是 tb_blog_comments，\n                                     不是 tb_shop_comments）\n\n  tb_shop.likes                      ❌ DDL 中有该列（start.sql:70），          —\n                                     但 Shop 实体中没有 likes 字段 →\n                                     ORM 完全不感知这一列\n\n  tb_shop.sold                       ? 无写入路径                          —\n  tb_voucher.stock（普通券）           ? 无写入路径                          —\n  tb_seckill_voucher.stock           ✓ 秒杀全链路维护（Lua + 条件更新）      △ 见下方\n  tb_follow.fans / UserInfo.fans     ❌ 没有维护                          ✗ 永远不变\n  UserInfo.followee                  ❌ 没有维护                          ✗ 永远不变",
        },
        {
          t: "p",
          x: "**结论很清晰：项目里有三种状态并存——「维护了」（liked）、「完全没维护」（comments / fans / followee）、「ORM 都不认识」（tb_shop.likes）。** 这种「有的做了一半」的状态比「全都没做」更危险，因为它会让开发者误以为计数是可用的。",
        },
        { t: "h", x: "2．追问 1：为什么要冗余计数（而不是实时 COUNT）" },
        {
          t: "p",
          x: "**因为「列表页展示计数」这个需求极其高频，而 `COUNT(*)` 的代价与数据量成正比。** 想象社交首页展示 20 条笔记，每条都要显示点赞数：实时 COUNT 就是 20 次 `SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?`——即使 `blog_id` 上有索引，20 次索引扫描也远贵于直接读一个整数列。**冗余计数把「聚合计算的成本」从「每次查询」转移到了「每次写入」，在读多写少的场景下收益巨大。**",
        },
        {
          t: "p",
          x: "这与分页的 `total` 是同一个道理——项目里 `Page.getTotal()` 也是同样的冗余思路（虽然因分页插件缺失而没有生效，见 Q8）。",
        },
        { t: "h", x: "3．追问 2：计数漂移怎么校准" },
        {
          t: "code",
          x: "漂移的三个真实来源（本项目都会发生）：\n\n  ① 并发重复点赞（社交板块 Q4 分析过）\n     ZSCORE 判重与 ZADD 写入之间有竞态窗口\n     → 两人同时点赞？不，是**同一用户双击** → liked +2 但只有 1 条记录\n\n  ② 双写非原子\n     DB update 成功 → Redis 写入抛异常\n     → 计数变了、Redis 没有记录 → 下次该用户再点赞判定为「未赞过」→ 再 +1\n\n  ③ Redis 数据丢失\n     blog:liked:{id} 被删 → 所有用户都被判定为「未赞过」\n     → 每人再点一次 → liked 膨胀到远大于真实人数\n     （注意 isBlogLiked:89 的类型不符时就 delete(key)，会直接触发这个场景）",
        },
        {
          t: "p",
          x: "**校准方案（按推荐度排序）**：",
        },
        {
          t: "code",
          x: "方案 A：明细表为准，定期重算（最可靠）\n  建 tb_blog_like(blog_id, user_id, create_time, UNIQUE(blog_id, user_id))\n  → Redis 的 blog:liked 从这里重建（真正的「Redis 只是缓存」）\n  → 定时任务：UPDATE tb_blog b SET liked =\n       (SELECT COUNT(*) FROM tb_blog_like l WHERE l.blog_id = b.id)\n  → 对账任务比较「DB 计数」与「Redis ZCARD」，差异超阈值则告警\n  ⚠ 代价：多一张表、多一次写入、Redis 与 DB 双写要处理失败\n\n方案 B：放弃冗余计数，改为实时 COUNT（最简单）\n  → 列表页改为一次批量聚合：\n     SELECT b.*, (SELECT COUNT(*) FROM tb_blog_like l WHERE l.blog_id = b.id) cnt\n     FROM tb_blog b WHERE ... （10 条，每条一次索引扫描，完全可接受）\n  → 彻底消除不一致，代价是列表查询变慢（但在 10 条/页的规模下微不足道）\n\n方案 C：不存明细，只信 Redis（最差）\n  → 即当前实现。Redis 一丢，数据永久不可恢复\n\n⚠ 关键决策点：**点赞明细到底存不存 DB？**\n   存 → 可以用方案 A，Redis 可靠可重建，但要付双写成本\n   不存 → 只能有 Redis 一份明细，一旦丢失就真的没了，\n          那就必须保证 Redis 的持久化（AOF everysec）+ 定期 RDB 备份",
        },
        {
          t: "p",
          x: "**本项目当前的处境是「选了方案 C 的成本，却没做方案 C 的防护」**：既没有明细表，Redis 也没有针对性的持久化配置说明（全仓无 `appendonly` 配置，使用的是默认的镜像 Redis 配置）。**这是一个可以被追问到底的点，主动承认并说出方案 A 是最加分的回答。**",
        },
        { t: "h", x: "4．追问 3：秒杀库存 stock 的一致性" },
        {
          t: "code",
          x: "stock 也是一类「计数」，但它的处理方式与点赞完全不同，这是刻意的设计：\n\n  券库存（tb_seckill_voucher.stock）—— 必须强一致\n    防线 1：Redis Lua 原子预扣（seckill.lua: DECR stockKey）\n    防线 2：DB 条件更新式扣减\n        voucher-service/.../VoucherServiceImpl.java:63-77\n            update().setSql(\"stock = stock - 1\").eq(\"voucher_id\", id).gt(\"stock\", 0).update()\n        → 「stock > 0」放在 WHERE 里，由数据库保证不会扣成负数\n    防线 3：库存与订单在同一个业务流程里，MQ 消费失败会回滚 Redis\n    → 三重防线，因为「超卖」是业务红线\n\n  博客点赞数（tb_blog.liked）—— 最终一致即可\n    只有 DB 的 UPDATE liked = liked ± 1 一道\n    → 因为「点赞数差一两个」用户完全无感，不必付出强一致的代价\n\n⚠ 但要注意秒杀库存链路本身也有缺陷（详见「高性能秒杀」板块）：\n   - 入口 Lua 的 DECR 与 VoucherServiceImpl:75 的\n     stringRedisTemplate...decrement(SECKILL_STOCK_KEY + voucherId) 重复扣减\n     → 一单扣 2 次 Redis 库存，而回滚只 INCR 一次\n   - MQ 发送失败时不回滚 Redis 预扣（VoucherOrderServiceImpl:118-124）\n     → 少卖\n   - 重试时 deductStock 没有订单级幂等 → DB 库存被重复扣减",
        },
        {
          t: "p",
          x: "**回答这个追问的核心是「按业务容忍度分级设计一致性强度」**：超卖、少卖是红线，所以要 Lua 原子 + 条件更新 + 回滚补偿三重防线；点赞数差几个无所谓，所以一道 UPDATE 就够。**用同一个标准要求所有数据，会导致该强的地方不够强、不该花成本的地方浪费成本。**",
        },
      ],
      sources: [
        "common/src/main/java/com/hmdp/entity/Blog.java:77,82（liked / comments 冗余计数）",
        "common/src/main/java/com/hmdp/entity/Shop.java:60,65,70（sold / comments / score）",
        "common/src/main/java/com/hmdp/entity/UserInfo.java:49,54（fans / followee，无写入路径）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:110,118,138（liked ±1）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogCommentsServiceImpl.java:22-29（saveComment 不更新计数）",
        "voucher-service/src/main/java/com/hmdp/voucher/service/impl/VoucherServiceImpl.java:63-77（条件更新式扣库存 + :75 重复 decrement）",
        "docs/SQL/start.sql:13-21（tb_seckill_voucher.stock）、:63-77（tb_shop.likes）",
        "全仓检索 appendonly / AOF 配置 → 无针对性持久化配置",
      ],
    },

    // ---------------- Q8 ----------------
    {
      cat: "持久层实践", level: "深入",
      title: "MyBatis-Plus 在项目里是怎么用的？分页配了吗？有没有自定义 SQL？",
      focus: "考察点：MyBatis-Plus 的配置完整性（分页插件是最常见的漏项）、Wrapper 与 XML 的取舍、`last()` 拼接的风险边界。",
      follows: [
        "如果没有注册分页拦截器，page() 会发生什么？",
        "empty mapper XML 目录说明什么？什么时候必须写 XML？",
        "last() 方法直接拼 SQL，有注入风险吗？",
      ],
      blocks: [
        { t: "h", x: "1．配置现状" },
        {
          t: "code",
          x: "各服务 application.yaml（shop/social/user/voucher/order 内容一致）：\n\n  mybatis-plus:\n    type-aliases-package: com.hmdp.entity      # 实体类别名扫描包\n    mapper-locations: classpath*:/mapper/**/*.xml   # XML 扫描路径\n\n实际使用统计：\n  自定义 XML 文件：0 个\n    order/shop/user/voucher 的 src/main/resources/mapper 目录**都是空的**\n    social-service 甚至没有 mapper 目录\n  Mapper 接口：全部是空接口\n    public interface ShopMapper extends BaseMapper<Shop> { }\n    public interface BlogMapper extends BaseMapper<Blog> { }\n    （BlogCommentsMapper / FollowMapper / ShopTypeMapper / UserMapper 等均如此）\n  → 100% 的查询由 ServiceImpl 的 Wrapper 或 setSql 完成",
        },
        {
          t: "p",
          x: "**「0 自定义 XML」说明这个项目的查询复杂度完全在 MyBatis-Plus 的能力范围内**：单表 CRUD、等值条件、IN 查询、分页、简单排序。这是 MyBatis-Plus 的甜点区，用 Wrapper 比写 XML 更快、更类型安全（Lambda 形式下字段名可编译期检查）。",
        },
        {
          t: "p",
          x: "**但要注意 Wrapper 的代价**：① 复杂查询（多表 join、子查询、聚合分组、union）用 Wrapper 表达会非常别扭，那时就该写 XML 或注解 SQL；② Wrapper 拼出的 SQL 在日志里很长，排查不便；③ **`type-aliases-package` 与 `mapper-locations` 这两个配置在「0 XML」的情况下其实是多余的**——配了不会出错，但也说明配置是从模板照搬过来的，没有按项目实际情况精简。",
        },
        { t: "h", x: "2．追问 1：分页插件（本项目最实质的持久层缺陷）" },
        {
          t: "code",
          x: "全仓检索结果：\n  MybatisPlusInterceptor       → 0 命中\n  PaginationInnerInterceptor   → 0 命中\n  任何 @Bean 形式的 MyBatis 配置类 → 0 命中\n\n但 main 代码里有 5 处 page() 调用，全部失效（精确清单）：\n\n  ShopController.queryShopByName:83-94          ← ⚠ 出现在 Controller 层\n      Page<Shop> page = shopService.query()\n              .like(StrUtil.isNotBlank(name), \"name\", name)\n              .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));\n      return Result.ok(page.getRecords());\n\n  ShopServiceImpl.queryShopByType:438-440\n      query().eq(\"type_id\", typeId).page(new Page<>(current, DEFAULT_PAGE_SIZE))\n\n  BlogServiceImpl.queryHotBlog:54-56\n      query().orderByDesc(\"liked\").page(new Page<>(current, MAX_PAGE_SIZE))\n\n  BlogServiceImpl.queryBlogByUserId:266-268\n      query().eq(\"user_id\", id).page(new Page<>(current, MAX_PAGE_SIZE))\n\n  BlogCommentsServiceImpl.queryCommentsByBlogId:34\n      query().eq(\"blog_id\", blogId).page(new Page<>(current, 10))\n\n★★ 而 order-service 里有一处**明确知道这个坑并手工绕过**的代码：\n\n  VoucherOrderServiceImpl.queryMyOrders:185-188\n      // 项目未配置 MP 分页插件，手动分页（count + limit/offset）\n      long total = count(wrapper.clone());\n      wrapper.last(\"LIMIT \" + s + \" OFFSET \" + (long) (p - 1) * s);\n      List<VoucherOrder> records = list(wrapper);\n\n  → 这段注释是「分页插件确实没配」的**代码内直接证据**，不是推测。\n  → 说明作者知道这个限制，但只在 order-service 一处做了手工分页，\n     另外 5 处 page() 调用仍然静默失效 —— 同一项目内两套分页实现并存。\n\n⚠ 影响面最需要警惕的是 ShopController.queryShopByName 与\n   ShopServiceImpl.queryShopByType 这两处「商户查询」：\n   商户列表是简历中「QPS 21K+」的核心查询接口，\n   如果分页不生效，这个接口在数据量上来后返回的是全量数据，\n   那么 21K 的 QPS 与「百万请求稳定」的说法就需要重新核对\n   （仓库中找不到商户查询的压测脚本或原始数据，见「系统性缓存设计」板块）",
        },
        {
          t: "p",
          x: "**机制解释**：MyBatis-Plus 的分页不是「自动」的，它通过 `PaginationInnerInterceptor` 拦截 `StatementHandler`，识别到参数是 `IPage` 类型时，**改写 SQL 追加 `LIMIT`，并额外执行一条 `COUNT` 语句**来填充 `total`。没有注册这个拦截器，`page()` 调用就退化为普通查询：",
        },
        {
          t: "code",
          x: "无拦截器时的实际行为：\n  - 生成的 SQL **没有 LIMIT** → 查询出所有匹配行\n  - Page.getRecords() = 全部行（数据「正确」但超量）\n  - Page.getTotal() = 0                      ← 分页组件拿不到总数\n  - Page.getPages() = 0\n  - Page.getCurrent() / getSize() 只是回显入参，不代表实际分页\n\n→ 危害特征：\n  ✓ 功能测试全过（小数据量下 records 内容恰好正确）\n  ✓ 单元测试全过（一般不校验 total）\n  ✗ 数据量上万后接口响应时间与内存线性恶化\n  ✗ 前端分页控件完全失效\n  → 典型的「能过所有测试的性能炸弹」\n\n修复（放在 common 模块，通过 AutoConfiguration.imports 自动生效，\n      与 SaTokenConfig 的注册方式一致）：\n\n  @Configuration\n  public class MybatisPlusConfig {\n      @Bean\n      public MybatisPlusInterceptor mybatisPlusInterceptor() {\n          MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();\n          interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));\n          return interceptor;\n      }\n  }\n\n  ⚠ 注意：如果按 common 的 imports 方式注册，需确认 common 被各服务依赖\n    （各服务 pom 均引入 hmdp-common / common 模块，已确认）",
        },
        {
          t: "p",
          x: "**顺带指出一处分层问题**：`ShopController.queryShopByName:83-94` 是**在 Controller 里直接调用 `shopService.query()` 并自己分页**。这在 MyBatis-Plus 里语法上可行（`ServiceImpl.query()` 是 public 的链式查询入口），但它把「查询条件构造 + 分页 + 结果裁剪」这三个持久层职责放到了 Controller。后果：① 该接口的分页逻辑无法被其他调用方复用（service 层没有对应方法）；② 一旦将来补上分页插件，这处仍会走 `Page` 对象，行为会与其他接口**不一致地**改变；③ 它同时也绕过了 service 层可能存在的缓存与业务校验。正确做法是把这段逻辑下沉到 `ShopServiceImpl` 的一个方法里，Controller 只负责参数绑定与返回。**这个点不难发现，但能主动提出来说明你对分层职责有实际的标准，而不只是「能跑就行」。**",
        },
        {
          t: "p",
          x: "**顺带说明为什么 common 里注册很危险但这里可以**：`MybatisPlusInterceptor` 的 Bean 只有在引入了 MyBatis-Plus 的服务里才有意义。如果 common 被一个不用 MyBatis 的服务（如纯 WebFlux 的网关）依赖，注册这个 Bean 会导致 `ClassNotFoundException` 或启动失败。**因此正确做法是加 `@ConditionalOnClass(MybatisPlusInterceptor.class)`**——这与已有的 `SaTokenConfig` 用 `@ConditionalOnWebApplication(type = SERVLET)` 排除网关是同一个思路。**能指出这一点，说明你理解自动配置的边界而不只是会写 Bean。**",
        },
        { t: "h", x: "3．追问 2：什么时候必须写 XML" },
        {
          t: "code",
          x: "MyBatis-Plus Wrapper 的舒适区（本项目全部落在这里）：\n  ✓ 单表 CRUD\n  ✓ 等值 / IN / 范围 / 模糊条件\n  ✓ 简单排序与分页\n  ✓ 批量插入（saveBatch，底层是逐条 + 批处理）\n\n必须写 XML / 注解 SQL 的场景：\n  ✗ 多表 join（本项目靠「Feign 跨服务调用 + 内存拼装」绕开了 join，\n    这正是微服务拆分带来的查询能力下降 —— 见社交板块 Q6 的 N+1）\n  ✗ 子查询 / exists / 窗口函数\n  ✗ GROUP BY + HAVING + 聚合（如「按分类统计销量」）\n  ✗ UNION / CASE WHEN\n  ✗ 需要精细控制索引提示（FORCE INDEX）或复杂 SQL 复用时\n\n⚠ 本项目的特殊之处：因为拆成了 5 个服务、「禁止跨服务 join」，\n  所有原本可以 join 的查询都退化成了「多次查询 + 内存拼装」。\n  这是微服务架构的固有代价，不是 MyBatis-Plus 的限制。\n  → 面试时把这个因果关系讲清楚，比说「我们没用 XML」有价值得多。",
        },
        { t: "h", x: "4．追问 3：last() 拼接的注入风险" },
        {
          t: "code",
          x: "项目里使用 last() 的两处（都是「保持 Redis 返回顺序」这个需求）：\n\n  BlogServiceImpl.java:221\n      query().in(\"id\", ids)\n             .last(\"ORDER BY FIELD(id,\" + StrUtil.join(\",\", ids) + \")\")\n\n  ShopServiceImpl.java:489\n      query().in(\"id\", ids).last(\"ORDER BY FIELD(id,\" + idStr + \")\")\n\n  SeckillOrderConsumer / 其他：无 last() 用于用户输入\n\n安全性评估：\n  ✓ ids 的来源是「Redis ZSet 返回的 blogId」或「Redis GEO 返回的 shopId」，\n    随后经过 Long.valueOf(...) 转换 → 已是 Long 类型\n  ✓ StrUtil.join 拼接的是 Long 的 toString() → 不可能包含引号或分号\n  → **在当前调用链上不存在注入风险**\n\n但风险在于「这个 API 本身没有防护」：\n  ✗ last() 是 MyBatis-Plus 明确标注为「只能拼接 SQL 片段、调用者自负安全」的方法\n  ✗ 一旦将来有新的调用点把「用户输入的字符串」拼进去，就会直接造成 SQL 注入，\n    而且不会有任何编译期或运行期提示\n  → 建议：在项目里约定「last() 只允许拼接常量或已验证为数值的变量」，\n    或封装一个 orderByField(ids) 的私有方法把校验收口到一处\n\n对比：其他所有条件都用 Wrapper 的 eq/in/gt 等方法（参数化绑定，安全）\n  → 整个项目的 SQL 安全性是好的，仅这两处是「约定安全」而非「机制安全」",
        },
        {
          t: "p",
          x: "**回答这道题的关键是把「当前是否安全」与「设计是否安全」分开**：当前调用链上确实安全（Long 类型转换保证了），但 API 本身没有防护，属于「依赖调用者的自律」。**能做出这个区分，比笼统地说「有注入风险/没注入风险」要准确得多。**",
        },
        {
          t: "p",
          x: "顺带补充一点 **MyBatis-Plus 的其他常用能力本项目未使用**，可作为「还知道什么」的储备：`@TableLogic` 逻辑删除（见 Q6）、`MetaObjectHandler` 自动填充 createTime/updateTime（当前依赖数据库的 `DEFAULT CURRENT_TIMESTAMP` 与 `ON UPDATE CURRENT_TIMESTAMP`，也是可行方案）、乐观锁插件 `OptimisticLockerInnerInterceptor` 配合 `@Version`（本项目秒杀用的是条件更新式乐观锁，见「高性能秒杀」板块）、`IdType.ASSIGN_ID` 雪花算法主键（本项目用自研 RedisIdWorker 替代）。**知道「有哪些现成能力没被用上」，是判断一个技术选型是否到位的重要角度。**",
        },
      ],
      sources: [
        "各服务 src/main/resources/application.yaml:31-33（mybatis-plus 配置，无分页插件）",
        "全仓检索 MybatisPlusInterceptor / PaginationInnerInterceptor → 0 命中",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:54-56,221,266-268",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogCommentsServiceImpl.java:34",
        "shop-service/src/main/java/com/hmdp/shop/service/impl/ShopServiceImpl.java:438-440,488",
        "order/shop/user/voucher 各 src/main/resources/mapper 目录为空；各 Mapper 接口均为空接口（如 ShopMapper.java:14）",
        "shop-service/src/main/java/com/hmdp/shop/controller/ShopController.java:83-94（Controller 层直接分页）",
        "order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java:185-188（注释直陈「项目未配置 MP 分页插件」，手工 count + LIMIT/OFFSET 绕过）",
        "common/src/main/java/com/hmdp/config/SaTokenConfig.java:11-13 + common/src/main/resources/META-INF/spring/...AutoConfiguration.imports（自动配置注册的可行方式）",
      ],
      gap: {
        claim:
          "简历 Live-Hub 总述：「二级缓存架构（Caffeine+Redis）支撑核心服务查询达 QPS 21K+(21027)，百万请求稳定。」——「核心服务查询」指向的正是商户查询。",
        fact:
          "源码实证：商户查询的两个入口都依赖**未注册**的分页插件——ShopController.queryShopByName:83-94 与 ShopServiceImpl.queryShopByType:438-440 都调用 page()，全仓无 MybatisPlusInterceptor / PaginationInnerInterceptor（0 命中），因此分页不生成 LIMIT、返回全量数据、total 恒为 0。order-service 在 VoucherOrderServiceImpl:185-188 用注释直陈「项目未配置 MP 分页插件」并手工 count + LIMIT 绕过。仓库中不存在商户查询的压测脚本或原始数据报告。",
        advice:
          "**先修复再复测**：在 common 注册 PaginationInnerInterceptor（加 @ConditionalOnClass），然后对商户查询重新压测。面试时的正确表述是把数字与三要素绑定：「在 8 核 16GB、N 万行商户数据下，用 XX 工具压 /shop/of/type 得到 21K QPS」；如果压测数据已经找不到了，就如实说「这个数字是早期压测的，原始报告没有留存，我现在能复现的方式是……」。**分页不生效这件事本身也值得主动说——它是加分项，证明你重新审视过自己的代码。**",
      },
    },
  ],
};
