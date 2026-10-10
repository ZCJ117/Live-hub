// 社交 Feed 流与关系链 · 8 道主问题
module.exports = {
  outFile: "社交Feed流与关系链_面试题.docx",
  title: "社交 Feed 流与关系链 · 面试题与标准答案",
  subtitle:
    "基于《左常健 · 后端研发简历》Live-Hub 项目经历（“覆盖用户、商户、秒杀、订单、社交 Feed 流”）+ D:\\hm-dianping 源码实证整理。本板块的代码集中在 social-service（8085），是本项目里 Redis 数据结构用得最丰富的一块：Feed 用 ZSet 写扩散、点赞用 ZSet 做排行榜、关注用 Set 做交集。含金量最高的三个追问是「写扩散的规模上限」「滚动分页的 offset 去重」「Feign 接口不存在」，请重点准备。",
  overview:
    "社交域独立为 social-service（8085），承担探店笔记（Blog）、评论（BlogComments）、关注关系（Follow）与站内信（Notification）。发布笔记时采用写扩散：查作者全部粉丝，逐个往 feed:{粉丝id} 这个 ZSet 里加 blogId，score 用当前毫秒时间戳；Feed 拉取用 reverseRangeByScoreWithScores 做滚动分页，靠返回的 minTime + offset 去重。点赞用 blog:liked:{blogId} 这个 ZSet 存 userId（score 为点赞时间），既能判重又能出 Top5 排行榜；关注用 follows:{userId} 这个 Set，共同关注靠 SINTER 求交集。用户昵称与头像不自持，统一通过 OpenFeign 调 user-service 补齐（无 fallback）。评论是最薄的一层，仅一次 insert 与一次分页查询。",
  overviewBullets: [
    "Feed 写扩散：BlogServiceImpl.saveBlog:168-190，粉丝数决定写入次数，无异步、无上限、无裁剪",
    "滚动分页：BlogServiceImpl.queryBlogOfFollow:193-237，reverseRangeByScoreWithScores(key,0,max,offset,2)，每页固定 2 条",
    "点赞：blog:liked:{id} ZSet（member=userId，score=时间戳），DB 侧 liked = liked ± 1（BlogServiceImpl:110,118,138）",
    "关注：follows:{userId} Set 做交集求共同关注（FollowServiceImpl:76-96）；tb_follow 无唯一索引",
    "用户信息：Feign 逐个补齐 → N+1；/user/list 批量接口在 user-service 中不存在",
  ],
  questions: [
    // ---------------- Q1 ----------------
    {
      cat: "模块总览", level: "基础",
      title: "社交这块你实现了哪些功能？分别用了什么 Redis 数据结构，为什么这么选？",
      focus: "考察点：对模块全貌的掌握、Redis 数据结构选型的判断依据、能否说清「为什么不用另一个结构」。",
      follows: [
        "点赞为什么用 ZSet 而不是 Set？score 存什么？",
        "关注关系为什么用 Set？和 ZSet 比各适合什么场景？",
        "除了 Redis，这些数据在 MySQL 里还存了一份吗？两份数据谁为准？",
      ],
      blocks: [
        { t: "h", x: "1．功能与数据结构对照" },
        {
          t: "code",
          x: "功能        接口                              数据结构与 Key                   代码位置\n----------------------------------------------------------------------------------------------------------------\n发布笔记    POST /blog                        DB 写入 + 写扩散到 ZSet          BlogServiceImpl.saveBlog:168-190\n关注流      GET  /blog/of/follow              ZSet  feed:{userId}              BlogServiceImpl.queryBlogOfFollow:193-237\n点赞/取赞   PUT  /blog/like|dislike/{id}      ZSet  blog:liked:{blogId}        BlogServiceImpl.likeBlog:100-125\n点赞排行    GET  /blog/likes/{id}             ZSet  取 range 0..4              BlogServiceImpl.queryBlogLikes:148-165\n热门笔记    GET  /blog/hot                    DB  order by liked desc          BlogServiceImpl.queryHotBlog:52-65\n关注/取关   PUT  /follow/{id}/{isFollow}      Set   follows:{userId}             FollowServiceImpl.follow:40-64\n共同关注    GET  /follow/common/{id}          Set   SINTER 求交集                FollowServiceImpl.followCommons:76-96\n评论        POST /blog/comments               DB 写入                          BlogCommentsServiceImpl.saveComment:22-29\n\nKey 常量定义：common/.../utils/RedisConstants.java:18-19\n  BLOG_LIKED_KEY = \"blog:liked:\"    FEED_KEY = \"feed:\"    USER_SIGN_KEY = \"sign:\"",
        },
        { t: "h", x: "2．选型理由" },
        {
          t: "p",
          x: "**点赞用 ZSet（BlogServiceImpl:104-121）**，不是因为去重（Set 也能去重），而是因为 score 有双重价值：① 记录点赞时间，后续可以做「按时间排序的点赞列表」；② `queryBlogLikes` 直接 `range(key, 0, 4)` 就能取到**最早点赞的前 5 人**（`BlogServiceImpl:151`）——换成 Set 就得把成员全捞回内存再排序。用 member=userId、score=时间戳这一组编码，把「判重」和「排行榜」两个需求一次性满足了，这是这个模块里最漂亮的一处设计。",
        },
        {
          t: "p",
          x: "**关注用 Set（FollowServiceImpl:52,82）**，因为关注关系只有「有/无」两态，不需要 score；而 Set 有一个 ZSet 做不到的能力——`SINTER` 直接求两个集合的交集（`FollowServiceImpl:82`），共同关注只需一次 Redis 命令，不用把两边成员都拉到应用层做比对。",
        },
        {
          t: "p",
          x: "**Feed 用 ZSet（BlogServiceImpl:185）**，因为 Feed 天然是「按时间倒序的时间线」，需要按 score 做范围查询与分页，这正是 ZSet 的核心能力。",
        },
        {
          t: "p",
          x: "反过来也成立：**如果点赞要显示「谁赞过」的完整列表**，Set 就够了；**如果关注要按关注时间排序**，就该用 ZSet。选型的分界线是「需不需要按序检索」和「需不需要集合运算」。",
        },
        { t: "h", x: "3．追问 3：MySQL 与 Redis 的双份数据" },
        {
          t: "code",
          x: "点赞：DB 侧 update().setSql(\"liked = liked + 1\").eq(\"id\", id)     BlogServiceImpl:110\n      Redis 侧 opsForZSet().add(key, userId, now)                     BlogServiceImpl:113\n关注：DB 侧 save(follow) / remove(wrapper)                            FollowServiceImpl:49,56\n      Redis 侧 opsForSet().add(key, followUserId)                      FollowServiceImpl:52\n\n两者的执行顺序都是「先 DB，后 Redis」，且没有任何事务或补偿包裹。",
        },
        {
          t: "p",
          x: "**当前的数据归属是：MySQL 存事实（谁赞过、谁关注谁），Redis 存状态与索引（用于快速判重、排序、求交）。** 但代码里并没有区分「谁为准」——两个存储是各自独立写的，中间没有事务、没有对账、没有重建机制。所以一旦 Redis 数据丢失（重启没开持久化、误删 key、类型冲突被 delete），就无法从 DB 恢复 Redis 索引，因为 DB 的 `tb_follow` 能重建关注 Set，但 `tb_blog.liked` 是个**计数器**、`tb_blog_comments` 里才有评论明细——点赞到底有哪些用户赞过，DB 里**根本没有存明细**。",
        },
        {
          t: "p",
          x: "这是一个值得主动指出的设计缺口，且面试官很可能追问。改进方向有二：① 补一张 `tb_blog_like(blog_id, user_id, create_time)` 明细表并加唯一索引，Redis 仅作为加速层，任何时刻可重建；② 或者干脆接受「点赞是弱一致数据」，但要在 Redis 侧开启 AOF 持久化并接受精度损失。**不能既不做明细表、又不做持久化，却声称点赞数据可靠。**",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:52-190",
        "social-service/src/main/java/com/hmdp/social/service/impl/FollowServiceImpl.java:40-96",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogCommentsServiceImpl.java:22-35",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:18-19",
        "docs/SQL/start.sql:35-43（tb_follow 表结构）、:97-111（tb_voucher_order）",
      ],
    },

    // ---------------- Q2 ----------------
    {
      cat: "Feed 流", level: "基础",
      title: "发布笔记后粉丝怎么看到？你的 Feed 用的是推模式还是拉模式？为什么？",
      focus: "考察点：推拉模式（写扩散 / 读扩散）的本质区别与适用边界、对自身实现规模的清醒认知。",
      follows: [
        "一个粉丝量 100 万的博主发一条笔记，你这个接口会发生什么？",
        "为什么不用拉模式？拉模式在你的场景下是不是更合适？",
        "Feed 的 ZSet 会无限增长吗？要不要做裁剪？",
      ],
      blocks: [
        { t: "h", x: "1．实现：同步写扩散" },
        {
          t: "code",
          x: "BlogServiceImpl.saveBlog:168-190\n\n  boolean isSuccess = save(blog);                                  // :173 写 tb_blog\n  List<Follow> follows = followService.query()\n          .eq(\"follow_user_id\", user.getId()).list();              // :178 查作者的全部粉丝\n  for (Follow follow : follows) {                                  // :180 逐个推送\n      Long userId = follow.getUserId();\n      String key = FEED_KEY + userId;                              // :184  feed:{粉丝id}\n      stringRedisTemplate.opsForZSet()\n              .add(key, blog.getId().toString(),\n                   System.currentTimeMillis());                    // :185  member=blogId, score=当前毫秒\n  }\n  return Result.ok(blog.getId());                                  // :189",
        },
        {
          t: "p",
          x: "这是标准的**推模式（写扩散，fan-out on write）**：发布时由作者一次性把消息「推」进每个粉丝的收件箱（`feed:{粉丝id}`）。粉丝读 Feed 时只需读自己那一个 ZSet，所以**读非常快**，这是 Feed 场景选择推模式的核心动机。",
        },
        { t: "h", x: "2．追问 1：百万粉丝会怎样（本模块最容易被问穿的一点）" },
        {
          t: "code",
          x: "循环体 :180-186 是同步的、串行的、无批量的：\n  - 100 万粉丝  →  100 万次 Redis 命令  →  未使用 pipeline，等于 100 万次网络往返\n  - 按单次往返 0.1ms 估算，仅 Redis 耗时就 ≥ 100 秒\n  - 而调用方是 HTTP 请求线程，接口 RT 会直接变成分钟级甚至超时\n  - 且 saveBlog :168 上没有 @Transactional，写到一半失败会造成\n    「部分粉丝收到、部分粉丝没收到」的不一致状态\n  - 缺少 @Async / MQ，整个扩散过程阻塞发布接口",
        },
        {
          t: "p",
          x: "正确回答要诚实承认这是**当前实现的规模上限**，然后给出工业界的分层解法：",
        },
        {
          t: "p",
          x: "① **异步化**：`saveBlog` 只写 DB + 发一条 MQ 消息就返回，扩散由消费者去做（这也正是项目里 order-service 已经在用的模式，可以直接类比）。",
        },
        {
          t: "p",
          x: "② **批量 + pipeline**：把粉丝分批（如每批 1000），用 `pipeline` 或 `ZADD` 的变参形式批量写，100 万次往返压缩到 1000 次。",
        },
        {
          t: "p",
          x: "③ **推拉结合（最重要的答案）**：对普通用户继续写扩散；对**大 V**（粉丝数超过阈值）改为**拉模式**——发布时不推，粉丝读 Feed 时实时把大 V 的最新笔记合并进时间线。这就是微博/Twitter 的做法：写扩散保证绝大多数用户的读性能，读扩散兜住少数超级节点的写风暴。",
        },
        {
          t: "p",
          x: "④ **分级限流**：给扩散任务设优先级队列，大 V 的扩散走低优先级、可延迟，甚至只推给「活跃粉丝」（近 30 天登录过的），冷粉丝下次登录时再补拉。",
        },
        { t: "h", x: "3．追问 2：为什么不用拉模式" },
        {
          t: "p",
          x: "拉模式（读扩散）发布时只写 1 条，读时把「我关注的所有人」的最新笔记归并排序。优点是写成本 O(1)、天然没有大 V 问题；缺点是**读成本与关注数成正比**——如果关注了 500 个人，每次刷 Feed 都要查 500 个作者的时间线再做多路归并，再叠加排序、分页、去重，RT 与实现复杂度都很高。",
        },
        {
          t: "p",
          x: "Feed 是**读多写少**的典型场景（一次发布、多次刷），所以推模式用「写放大」换「读极快」是划算的。本项目的实现选择推模式方向正确，问题只出在**没有处理写放大的上限**。",
        },
        { t: "h", x: "4．追问 3：Feed 的 ZSet 要裁剪吗" },
        {
          t: "p",
          x: "**要。** 当前代码只做 `ZADD`（`BlogServiceImpl:185`），没有任何裁剪逻辑（全仓检索 `ZREMRANGEBYRANK` / `ZREMRANGEBYSCORE` → 0 命中）。这意味着一个活跃粉丝的 `feed:{userId}` 会随关注对象发布量的增长而无限膨胀：① 内存持续增长且不可回收；② 用户实际只会翻前几页，历史数据纯属浪费。",
        },
        {
          t: "p",
          x: "标准做法是保留固定窗口：写入后执行 `ZREMRANGEBYRANK feed:{uid} 0 -1001`，只保留最新 1000 条。对绝大多数产品来说，用户翻不到第 1000 条以外的内容，这样既锁定了内存上限，也不损失体验。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:168-190（saveBlog 全量）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:178-186（粉丝循环与 ZADD）",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:19（FEED_KEY = \"feed:\"）",
        "全仓检索 ZREMRANGEBYRANK / ZREMRANGEBYSCORE / pipeline → 0 命中（未做裁剪与批量）",
        "order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java:75-100（项目内已有的异步化先例，可类比）",
      ],
      gap: {
        claim:
          "简历 Live-Hub 总述：「在 8 核 16GB 内存环境下，二级缓存架构（Caffeine+Redis）支撑核心服务查询达 QPS 21K+(21027)，百万请求稳定。」",
        fact:
          "源码实证：social-service 的 Feed 写扩散是同步串行、无批量、无异步、无上限（BlogServiceImpl:180-186），接口耗时随粉丝数线性增长；feed 的 ZSet 无任何裁剪（全仓检索 ZREMRANGEBYRANK → 0 命中）。仓库中不存在社交模块的压测脚本或原始数据（唯一的压测脚本是 docs/perf-plan/k6-seckill.js，针对秒杀接口），且该文档自身声明「所有容量数字均为测算值，以压测校准为准」。",
        advice:
          "面试时务必把「百万请求稳定」限定到**具体接口 + 具体数据量 + 压测工具**三要素上说明，否则一旦被追问「哪个接口、多少数据量、用什么压的」，会非常被动。若被问到 Feed，主动说明当前实现存在写扩散上限，并给出异步化 / pipeline 批量 / 大 V 走拉模式的分层改进——**主动暴露局限比被问穿要好得多**。",
      },
    },

    // ---------------- Q3 ----------------
    {
      cat: "Feed 流", level: "进阶",
      title: "Feed 的滚动分页是怎么实现的？minTime 和 offset 这两个返回参数是干什么用的？",
      focus: "考察点：ZSet 范围查询的边界语义、基于游标分页的去重设计、能否讲清「为什么 offset 不能简单 +N」。",
      follows: [
        "为什么不用 limit offset 分页？两者在这个场景下有什么本质区别？",
        "offset 的值为什么是「最后一个时间戳出现的次数」而不是固定 2？",
        "如果有人在你翻页的过程中发了一条新笔记，你的分页会重复或漏数据吗？",
      ],
      blocks: [
        { t: "h", x: "1．实现" },
        {
          t: "code",
          x: "BlogServiceImpl.queryBlogOfFollow:193-237\n\n  String key = FEED_KEY + userId;                                        // :197\n  Set<TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()\n          .reverseRangeByScoreWithScores(key, 0, max, offset, 2);        // :198-199\n\n  // 遍历本页结果，统计「与最后一个时间戳相同的元素个数」\n  long minTime = 0;  int os = 1;                                          // :206-207\n  for (TypedTuple<String> tuple : typedTuples) {\n      ids.add(Long.valueOf(tuple.getValue()));\n      long time = tuple.getScore().longValue();\n      if (time == minTime) { os++; }                                     // :213-214\n      else { minTime = time; os = 1; }                                   // :215-216\n  }\n\n  ScrollResult r = new ScrollResult();                                   // :231-234\n  r.setList(blogs);  r.setOffset(os);  r.setMinTime(minTime);\n\n参数来源（BlogController:104-109）：GET /blog/of/follow?lastId={max}&offset={offset}\n",
        },
        {
          t: "p",
          x: "`reverseRangeByScoreWithScores(key, min, max, offset, count)` 的语义是：按 score **从大到小**，取 score 落在 `[min, max]` 区间内的元素，跳过前 `offset` 个，取 `count` 个。这里固定 `count=2`（`:199` 的硬编码，建议改为常量或配置）。",
        },
        { t: "h", x: "2．追问 1：为什么不用 limit offset 分页" },
        {
          t: "p",
          x: "`LIMIT offset, count` 是**基于位置**的分页，它要求「第 N 页」这个位置在两次查询之间是稳定的。但 Feed 是**头部不断插入新数据**的时间线——你翻到第 2 页的瞬间别人发了新笔记，整个列表下标就整体后移了，`LIMIT 10,10` 会把第 1 页看过的内容再返回一次（数据重复）；反之删除会造成漏读。而且大 offset 时数据库要扫描并丢弃前 offset 行，越翻越慢。",
        },
        {
          t: "p",
          x: "滚动分页（游标分页）是**基于内容**的：不用「第几页」定位，而用「上一页最后一条的时间戳」定位。因为时间戳是数据自带的、不随插入变化的锚点，所以无论期间新增多少条，翻页都不会重复或跳过已看过的旧数据。**代价是不支持跳页**（不能直接跳到第 5 页），但这恰好符合 Feed「只能往下刷」的产品形态。",
        },
        { t: "h", x: "3．追问 2：offset 为什么是「末尾时间戳的出现次数」（核心考点）" },
        {
          t: "code",
          x: "假设 feed 里的数据（score 为毫秒时间戳，从新到旧）：\n\n  blogId=105  score=1735689600500\n  blogId=104  score=1735689600300   ┐ 同一毫秒发布的两条\n  blogId=103  score=1735689600300   ┘\n  blogId=102  score=1735689600100\n  blogId=101  score=1735689600000\n\n第 1 页 count=2 → 返回 [105(t=500), 104(t=300)]\n  遍历后：minTime=300, os=1\n  → 返回 minTime=1735689600300, offset=1\n\n第 2 页 请求 ?lastId=1735689600300&offset=1\n  reverseRangeByScoreWithScores(key, 0, 1735689600300, offset=1, 2)\n  → 先取区间内 (103,102) 两条，跳过第 1 个(103) → 返回 [102]\n\n  ⚠ 如果没有 offset 这个参数：区间 [0, t=300] 是**闭区间**，\n     第 2 页会先返回 104 和 103，其中 104 已经看过 → 重复。\n\n  注意第 1 页返回的 offset=1 而不是 2：\n     因为 t=300 这个时间戳在**全表**里可能有更多条（不是只有 104），\n     下一页必须跳过「所有 score == 300 且已返回过」的数量。\n     这里 os 统计的是本页末尾时间戳的出现次数，即已消费的数量。",
        },
        {
          t: "p",
          x: "所以 `offset` 的语义是「上一页返回结果中，与末尾时间戳相同的那几条，已经消费了几个」。它的存在是为了处理**同一毫秒内多条数据**这种边界：时间戳的精度是毫秒，同一毫秒内可能有多条笔记，单纯用 `minTime` 做游标会把边界那一组数据重复返回。**这是本模块最值得讲的一个细节，说明作者确实理解闭区间游标分页的边界问题。**",
        },
        {
          t: "p",
          x: "`ScrollResult` 这个 DTO 也是为此专门设计的（`common/.../dto/ScrollResult.java`，字段 list / minTime / offset），与普通分页的 `Page` 区分开，前端拿到后把 `minTime` 当作下次的 `lastId`、`offset` 原样回传即可，形成闭环绕圈。",
        },
        { t: "h", x: "4．追问 3：翻页期间的读写" },
        {
          t: "p",
          x: "**新增不会造成问题**（这是游标分页优于 limit 分页的地方）：新笔记的 score 更大，落在下一次查询区间的上界 `max` 之上，不会被重复返回，只是这次翻页看不到它，需要下拉刷新才能看到——这是可以接受的产品行为。",
        },
        {
          t: "p",
          x: "**删除会造成问题**：如果边界时间戳上的那几条数据在两次请求之间被删除（比如取关导致 Feed 重算、或笔记被删），那么下一页传回来的 `offset` 就会**跳过本该返回的数据**（本来要跳过 1 个，但那个已经没了，于是又跳过了下一个），造成漏读。要彻底解决需要把游标从「时间戳 + 偏移」改成「时间戳 + 全局唯一 ID」（blogId 由 RedisIdWorker 生成，单调递增），用 `(score, id)` 组合做严格小于的游标。本项目未做这层防护，属于可接受范围内的精度损失，但应当在面试中主动说明。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:193-237（queryBlogOfFollow 全量）",
        "social-service/src/main/java/com/hmdp/social/controller/BlogController.java:98-109（lastId / offset 入参）",
        "common/src/main/java/com/hmdp/dto/ScrollResult.java（list / minTime / offset 三字段）",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:19（FEED_KEY）",
      ],
    },

    // ---------------- Q4 ----------------
    {
      cat: "点赞", level: "进阶",
      title: "点赞是怎么做的？为什么用 ZSet？数据库和 Redis 两边都写，一致性怎么保证？",
      focus: "考察点：双写一致性意识、并发下的重复计数、热点 key 的读写放大、能否定位「判断依据与写入依据不一致」这类隐蔽缺陷。",
      follows: [
        "用户连点两次点赞会怎样？会不会出现点赞数 +2 但只有 1 个人赞过？",
        "likeBlog 里的 ZSet 和 RedisConstants 里叫 BLOG_LIKED_KEY，为什么不用 Set？",
        "列表接口里每个 blog 都要查一次「我赞没赞过」，这样合适吗？",
      ],
      blocks: [
        { t: "h", x: "1．实现" },
        {
          t: "code",
          x: "BlogServiceImpl.likeBlog:100-125\n\n  Long userId = UserHolder.getUser().getId();                              // :102\n  String key = BLOG_LIKED_KEY + id;                                        // :104  blog:liked:{id}\n  Double score = stringRedisTemplate.opsForZSet()\n          .score(key, userId.toString());                                  // :105\n\n  if (score == null) {                                                     // :107 判重依据：ZSet\n      boolean isSuccess = update().setSql(\"liked = liked + 1\")\n                                 .eq(\"id\", id).update();               // :110 先 DB\n      if (isSuccess) {\n          stringRedisTemplate.opsForZSet()\n                  .add(key, userId.toString(), System.currentTimeMillis()); // :113 后 Redis\n      }\n  } else {\n      boolean isSuccess = update().setSql(\"liked = liked - 1\")\n                                 .eq(\"id\", id).update();               // :118\n      if (isSuccess) {\n          stringRedisTemplate.opsForZSet().remove(key, userId.toString());// :121\n      }\n  }",
        },
        { t: "h", x: "2．为什么用 ZSet" },
        {
          t: "p",
          x: "见 Q1 的选型分析：ZSet 用 member=userId、score=点赞时间戳，一次写入同时满足两个需求——① 用 `ZSCORE` 判重（`BlogServiceImpl:105`）；② 用 `ZRANGE 0 4` 取**最早点赞的前 5 位用户**做点赞头像墙（`BlogServiceImpl:151`）。用 Set 就只能满足判重，排行榜还得全量取回内存排序。",
        },
        { t: "h", x: "3．追问 1：并发重复点赞（真实缺陷）" },
        {
          t: "code",
          x: "两个并发请求（用户快速双击 / 前端重复提交）时间线：\n\n  T1: ZSCORE blog:liked:100 → null      认为「没赞过」\n  T2: ZSCORE blog:liked:100 → null      同样认为「没赞过」   ← 检查与执行之间存在竞态窗口\n  T1: UPDATE tb_blog SET liked = liked + 1 WHERE id = 100      → liked = 5\n  T2: UPDATE tb_blog SET liked = liked + 1 WHERE id = 100      → liked = 6\n  T1: ZADD → 1 个成员\n  T2: ZADD → 仍是 1 个成员（member 相同，只是覆盖 score）\n\n结果：Redis 里只有 1 个人赞过，DB 里 liked 却加了 2。\n反向操作（取消点赞）同理会造成 liked 少减。",
        },
        {
          t: "p",
          x: "根因有两层：① **「先读后写」的检查-执行不是原子的**——`ZSCORE` 判断与 `ZADD` 写入之间没有锁，也没有用 Redis 返回的写入结果做判断（`ZADD` 返回 1 表示新增、0 表示仅更新 score，这个返回值被忽略了）；② **判断依据和写入依据不一致**——用 Redis 判断「是否已赞」，却用 DB 的 `liked` 计数器做累加，两边没有绑定关系。",
        },
        {
          t: "p",
          x: "修法有三个层次，答出任意一个都比不说强：",
        },
        {
          t: "code",
          x: "方案 A（最小改动，推荐）：用 ZADD 的返回值做幂等判断\n  Long added = redis.opsForZSet().add(key, userId, now);   // 返回 Long，1=新增 0=已存在\n  if (added != null && added == 1L) {  // 只有真正新增才动 DB\n      update().setSql(\"liked = liked + 1\").eq(\"id\", id).update();\n  }\n  → 把「读-判断-写」压缩成「原子写-看结果」，单条命令天然原子\n\n方案 B：Lua 脚本，把 ZADD 与计数判断放在一次原子执行里\n方案 C：DB 层加唯一索引 + 明细表，让数据库兜住重复（见 Q6 与数据库板块）",
        },
        { t: "h", x: "4．追问 2：为什么判断「是否点赞」要在列表里逐条查" },
        {
          t: "code",
          x: "BlogServiceImpl.isBlogLiked:67-97（在列表接口里对每条 blog 调用一次）\n\n  DataType keyType = stringRedisTemplate.type(key);          // :84  ← 第 1 次往返\n  if (keyType != null && keyType != DataType.ZSET) {\n      log.warn(\"Redis key {} has wrong type: {} ...\");        // :87-88\n      stringRedisTemplate.delete(key);                       // :89  ← 写操作！\n      blog.setIsLike(Boolean.FALSE);\n      return;\n  }\n  Double score = stringRedisTemplate.opsForZSet().score(key, userId);  // :95 ← 第 2 次往返",
        },
        {
          t: "p",
          x: "两个问题：",
        },
        {
          t: "p",
          x: "**① 性能：热路径上每个 blog 要 2 次 Redis 往返。** `queryHotBlog` 一页 10 条（`SystemConstants.MAX_PAGE_SIZE = 10`）就是 20 次往返，且是串行 for 循环（`BlogServiceImpl:60-63`），RT 直接乘以 10。而且 `queryHotBlog` 还同时做了 N+1 次 Feign 调用（见 Q6），一个列表接口的实际开销远超预期。优化方向：用 pipeline 批量取、或用一次 `ZSCORE` 替代 `TYPE`+`ZSCORE`（ZSCORE 对错误类型同样报错，可在异常里兜底），或把「我赞过的 blogId 集合」缓存在会话里。",
        },
        {
          t: "p",
          x: "**② 正确性：类型不符时直接 `DELETE` 是一次危险的数据破坏（`BlogServiceImpl:89`）。** 设想有人（或历史代码）用同一个 key 名存了 String 类型，这个分支会在**读接口**里静默删掉整个 key，把全部点赞记录清空，且只留一行 warn 日志。这种「读路径写数据」的设计应该避免——正确做法是返回兜底值并告警，由人工或后台任务决定是否清理，而不是让一个查询接口承担破坏性写入。",
        },
        {
          t: "p",
          x: "另外这段检查在生产上基本不会触发（key 由本服务自己写入，类型必然正确），却付出了每次查询多一次 `TYPE` 往返的代价——**用一个几乎不会发生的情况，拖慢了每一条正常请求**。这是很典型的「防御性代码反而成为性能负担」的例子。",
        },
        { t: "h", x: "5．追问 3：双写一致性" },
        {
          t: "p",
          x: "当前是「先 DB 后 Redis，两边都成功才算数，失败不做补偿」（`BlogServiceImpl:110-122`）。风险：① DB 更新成功但 Redis 写入抛异常（网络抖动）→ DB 计数已变、Redis 无记录，下次该用户再点赞会被判为「未赞过」而再次 +1，计数继续漂移；② Redis 更新成功但 DB 失败（代码里用 `if (isSuccess)` 挡住了，这点处理是对的）；③ 并发下如上的重复计数。",
        },
        {
          t: "p",
          x: "对这个场景，务实的回答是：**点赞是弱一致的业务数据，不必追求强一致**，但要保证「不会无限漂移」。可行方案：把 DB 计数改为**由明细表实时聚合**（`SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?`，加索引后很快），或定时任务用明细表校正 `liked` 计数器，这样 Redis 只是加速层，任何不一致都可在下一轮校正中收敛。当前项目两者都没做。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:67-97（isBlogLiked）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:100-145（likeBlog / dislikeBlog 双份重复逻辑）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:148-165（queryBlogLikes 取 range 0..4）",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:18（BLOG_LIKED_KEY）",
        "common/src/main/java/com/hmdp/utils/SystemConstants.java:7（MAX_PAGE_SIZE = 10）",
        "docs/SQL/start.sql:63-77（tb_shop 有 likes/comments 计数器字段；tb_blog 的 DDL 未在仓库中提供）",
      ],
    },

    // ---------------- Q5 ----------------
    {
      cat: "关注关系", level: "进阶",
      title: "关注和共同关注怎么实现的？tb_follow 表有唯一约束吗？重复关注会发生什么？",
      focus: "考察点：关系型数据的建模与约束设计、Redis 集合运算的复杂度、能否发现「应用层去重掩盖数据层脏数据」。",
      follows: [
        "SINTER 求交集的复杂度是多少？关注列表很大时会有问题吗？",
        "Redis 里的 follows: 集合和 MySQL 的 tb_follow 谁为准？Redis 丢了怎么办？",
        "isFollow 每次都查数据库 count(*)，有必要吗？",
      ],
      blocks: [
        { t: "h", x: "1．实现" },
        {
          t: "code",
          x: "FollowServiceImpl.follow:40-64\n  String key = \"follows:\" + userId;                                    // :43 硬编码，未抽常量\n  if (isFollow) {\n      Follow follow = new Follow();\n      follow.setUserId(userId).setFollowUserId(followUserId);\n      boolean isSuccess = save(follow);                                // :49 写 tb_follow\n      if (isSuccess) stringRedisTemplate.opsForSet()\n                        .add(key, followUserId.toString());           // :52 写 Set\n  } else {\n      boolean isSuccess = remove(new QueryWrapper<Follow>()\n              .eq(\"user_id\", userId).eq(\"follow_user_id\", followUserId));// :56-57\n      if (isSuccess) stringRedisTemplate.opsForSet()\n                        .remove(key, followUserId.toString());        // :60\n  }\n\nFollowServiceImpl.followCommons:76-96\n  Set<String> intersect = stringRedisTemplate.opsForSet()\n          .intersect(key, key2);                                       // :82  SINTER\n  → 空则返回空列表；否则解析成 userId 列表，Feign 批量查用户",
        },
        { t: "h", x: "2．追问 1：SINTER 的复杂度" },
        {
          t: "p",
          x: "Redis 的 `SINTER` 时间复杂度是 **O(N×M)**，N 是最小集合的基数，M 是集合个数。这里是两个集合求交，即 O(min(|A|,|B|))——Redis 会先遍历较小的集合，逐个到其他集合里做 `SISMEMBER` 判断。对普通用户（关注数几百到几千）完全够用；但如果双方都是关注上万人的重度用户，单次命令就要遍历上万次，且因为 Redis 是**单线程执行命令**，这条慢命令会阻塞后面所有请求。",
        },
        {
          t: "p",
          x: "对比一下：如果放在 MySQL 里做，是 `SELECT follow_user_id FROM tb_follow WHERE user_id=A AND follow_user_id IN (SELECT follow_user_id FROM tb_follow WHERE user_id=B)`，需要两次索引扫描 + 内存求交；而 Redis 的 Set 是紧凑的哈希结构，求交在内存里做，通常更快，且不会占用数据库连接。**在「两个小集合求交」这个具体场景下，Redis 是更优选择**——这是一个可以主动给出的对比论证。",
        },
        { t: "h", x: "3．追问 2：唯一约束缺失（重点缺陷）" },
        {
          t: "code",
          x: "docs/SQL/start.sql:35-43  ← tb_follow 的完整 DDL\n\n  CREATE TABLE `tb_follow` (\n      `id` bigint NOT NULL COMMENT '主键',\n      `user_id` bigint unsigned NOT NULL COMMENT '用户id',\n      `follow_user_id` bigint unsigned NOT NULL COMMENT '关联的用户id',\n      `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n      PRIMARY KEY (`id`),\n      KEY `idx_user_id` (`user_id`),                 ← 普通索引\n      KEY `idx_follow_user_id` (`follow_user_id`)    ← 普通索引\n  );\n\n⚠ 没有 UNIQUE KEY (user_id, follow_user_id)",
        },
        {
          t: "p",
          x: "后果链条：**重复关注会插入重复行 → 但 Redis 的 Set 会自动去重 → 于是应用层看到的「关注列表」是正确的，DB 里却已经积累了脏数据。** 这种「上层去重掩盖底层脏数据」是最危险的一类缺陷，因为它在正常使用中完全不可见，只会在做数据统计、分页、或者切换回 DB 为准时才爆发。",
        },
        {
          t: "p",
          x: "要讲清楚的另一点是：**Redis 的 Set 去重能力在这里救了场，但也正因如此，团队失去了发现问题的信号。** 更严重的是，一旦 Redis 数据丢失（`follows:` 集合全仓没有任何重建逻辑），用 DB 重建时会把重复行一起喂回去，问题才暴露。",
        },
        {
          t: "p",
          x: "正确做法：`ALTER TABLE tb_follow ADD UNIQUE KEY uk_user_follow (user_id, follow_user_id);`，应用层用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先查后插（配合唯一索引兜底），把「唯一性」这个不变量下沉到数据库——**数据库是最后的防线，应用层并发控制总会漏。** 代价是删除时要用 `remove(wrapper)`（已有，`:56-57`），全表删除重复行需要一次性清理脚本。",
        },
        { t: "h", x: "4．追问 3：isFollow 查库" },
        {
          t: "code",
          x: "FollowServiceImpl.isFollow:67-73\n  Long count = query().eq(\"user_id\", userId)\n                     .eq(\"follow_user_id\", followUserId).count();     // :71  SELECT COUNT(*)\n  return Result.ok(count > 0);\n\n→ 每次「是否关注」都打一次 DB，而 Redis 里已经有 follows:{userId} 这个 Set 了。",
        },
        {
          t: "p",
          x: "这里存在一处**明显的数据源选择不一致**：`follow` 写入时维护了 Redis Set，但 `isFollow` 判断时却不用它（应该用 `SISMEMBER`，O(1) 且是内存操作）。这既是性能浪费（页面里每个作者都要判一次），也让 Redis 里那份 Set 的价值打了折扣——它只服务了 `followCommons` 一个接口。优化：`isFollow` 改走 `SISMEMBER`，DB 仅作为 Redis miss 时的兜底重建来源。",
        },
        {
          t: "p",
          x: "另外 `query().eq(...).count()` 在 MyBatis-Plus 中默认生成 `SELECT COUNT(*)`，走 `idx_user_id` 或 `idx_follow_user_id` 都能命中（两列都有独立索引），代价可接受，但没必要——**这个接口是典型的「判断存在性」，应该用 `exists` 语义或 `LIMIT 1`**，而不是精确计数。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/FollowServiceImpl.java:40-96（全量）",
        "social-service/src/main/java/com/hmdp/social/controller/FollowController.java:29-52",
        "docs/SQL/start.sql:35-43（tb_follow DDL，无唯一约束，仅 idx_user_id / idx_follow_user_id）",
        "common/src/main/java/com/hmdp/entity/Follow.java:24-48（@TableName(\"tb_follow\")）",
      ],
    },

    // ---------------- Q6 ----------------
    {
      cat: "服务调用", level: "深入",
      title: "社交服务怎么拿到用户的昵称和头像？这个 Feign 调用设计得怎么样？",
      focus: "考察点：跨服务聚合的 N+1 问题、REST 接口语义规范、能否发现「被调用方根本没有这个接口」这种致命不一致。",
      follows: [
        "getUserByIds 用的是 @GetMapping + @RequestBody，这样写有问题吗？",
        "如果 user-service 挂了，你的博客列表会怎样？",
        "批量查用户应该怎么设计接口？",
      ],
      blocks: [
        { t: "h", x: "1．实现" },
        {
          t: "code",
          x: "social-service/.../feign/UserFeignClient.java\n  @FeignClient(name = \"user-service\")                        // :16  无 fallback\n  public interface UserFeignClient {\n      @GetMapping(\"/user/{id}\")\n      Result getUserById(@PathVariable(\"id\") Long id);        // :22-23\n\n      @GetMapping(\"/user/list\")\n      Result getUserByIds(@RequestBody List<Long> ids);       // :28-29\n  }\n\n调用点：\n  BlogServiceImpl.queryBlogUser:253-261   逐条调用 getUserById      ← 列表接口里按条循环\n  BlogServiceImpl.queryBlogLikes:158      调用 getUserByIds（Top5 点赞用户）\n  FollowServiceImpl.followCommons:90      调用 getUserByIds（共同关注）",
        },
        { t: "h", x: "2．追问 1：@GetMapping + @RequestBody 的问题" },
        {
          t: "p",
          x: "**HTTP 语义上，GET 请求不应携带请求体。** RFC 9110 明确说明 GET 的语义是「获取资源」，其语义不依赖 body；实践中大量基础设施会直接丢掉 GET 的 body：浏览器 `fetch` 会报错、Nginx 默认转发但部分模块会剥离、Spring Cloud Gateway 可以正常转发、而部分 HTTP 客户端（如 `HttpURLConnection`）在 GET + body 时行为未定义。",
        },
        {
          t: "p",
          x: "Spring Cloud OpenFeign 默认使用 `feign.Client.Default`（基于 `HttpURLConnection`），对 GET + body 的处理**没有保证**——可能被静默丢弃、可能抛异常，且不同 Feign 客户端实现（Apache HttpClient / OkHttp）行为还不一致。这意味着这段代码**换一个依赖就会坏**，是典型的「依赖未定义行为」。",
        },
        {
          t: "p",
          x: "规范做法两选一：① `@PostMapping(\"/user/list\")` + `@RequestBody`（查询语义上略不 RESTful，但工程上最稳、无 URL 长度限制）；② `@GetMapping(\"/user/list\")` + `@RequestParam(\"ids\") List<Long> ids`，前端用 `ids=1,2,3` 逗号分隔（Spring 支持这种绑定）。**多数团队选 ①，因为参数多时不用担心 URL 长度限制。**",
        },
        { t: "h", x: "3．追问 2：致命不一致——/user/list 这个接口不存在" },
        {
          t: "code",
          x: "user-service/.../controller/UserController.java 的全部映射（全量列出）：\n\n  @PostMapping(\"code\")              → /user/code\n  @PostMapping(\"/login\")            → /user/login\n  @PostMapping(\"/logout\")           → /user/logout\n  @GetMapping(\"/me\")                → /user/me\n  @GetMapping(\"/info/{id}\")         → /user/info/{id}\n  @GetMapping(\"/{id}\")              → /user/{id}          ← 只有单个查询\n  @PostMapping(\"/sign\")             → /user/sign\n  @GetMapping(\"/sign/count\")        → /user/sign/count\n\n⚠ 没有 /user/list！\n\n而 social-service 在 BlogServiceImpl:158 与 FollowServiceImpl:90 两处调用它。\n\n更微妙的是：@GetMapping(\"/{id}\") 是**路径变量匹配**，请求 /user/list 时\n会尝试把字符串 \"list\" 绑定到 Long id → 类型转换失败 → 400 / 参数错误，\n而不是干净的 404。",
        },
        {
          t: "p",
          x: "**这意味着「点赞排行榜」和「共同关注」这两个接口在当前代码下必然失败**（返回 `Result.fail(\"获取用户信息失败\")` 或者抛异常），除非线上跑的 user-service 版本与仓库代码不一致。这是一个必须如实说明、并且要能讲出排查路径的缺陷：Feign 调用失败会走 fallback（这里没有配）或直接抛 `FeignException`，而 `BlogServiceImpl:159` 的 `if (!result.getSuccess())` 只处理了「响应成功但业务失败」的情况，**抛异常时根本走不到这行**，会直接冒泡成 500。",
        },
        {
          t: "p",
          x: "面试前务必本地实测一次 `/blog/likes/{id}` 与 `/follow/common/{id}`。如果确实不通，修法是：**在 user-service 补一个 `@PostMapping(\"/user/list\")` 批量查询接口**（用 `query().in(\"id\", ids).list()` 一次查完），同时把 Feign 侧改成 `@PostMapping`。**这类「调用方与被调用方接口契约不一致」的问题在微服务里非常常见，能提前发现并说清楚，是加分项而不是减分项。**",
        },
        { t: "h", x: "4．追问 3：N+1 与降级" },
        {
          t: "code",
          x: "BlogServiceImpl.queryBlogUser:253-261（在 forEach 里被逐条调用）\n\n  queryHotBlog:60-63       records.forEach(blog -> { queryBlogUser(blog); isBlogLiked(blog); });\n  queryBlogOfFollow:223-228 for (Blog blog : blogs) { queryBlogUser(blog); isBlogLiked(blog); }\n  queryBlogByUserId:272-275 records.forEach(...)\n\n→ 热门列表 10 条 = 10 次 Feign 调用 + 20 次 Redis 往返（isBlogLiked 每次 2 次）\n→ 全部串行执行，接口 RT ≈ 10 × (Feign RT + 2 × Redis RT)",
        },
        {
          t: "p",
          x: "这就是典型的 **N+1 查询问题**：本该是 1 次批量查询，退化成了 N 次单条查询，且放大系数是「分页大小」。修法正是 UserFeignClient 里那个（不存在的）`getUserByIds`——把当前页所有 blogId 对应的 userId 去重后一次批量查回，在内存里做 map 回填，N 次调用降为 1 次。这也是为什么「补上 /user/list」不只是修 bug，更是解决 N+1 的前提。",
        },
        {
          t: "p",
          x: "**降级方面**：`UserFeignClient` 没有配 `fallback`（对比 `order-service` 的 `VoucherFeignClient` 是配了 `VoucherFeignClientFallback` 的，`VoucherFeignClient.java:10`）。后果是 user-service 不可用时，社交服务的博客列表会直接**不可用**——而这本是一个**非核心依赖**：昵称头像缺失不应该让整个 Feed 挂掉。合理设计是给 Feign 配 fallback，用户信息拿不到时返回 userId 占位（或返回缓存中的最近值），保证 Feed 主体可用。注意 order-service 侧还额外开启了 `feign.circuitbreaker.enabled: true`（`order-service/application.yaml:119-127`）才让 fallback 生效，**social-service 的配置里没有这一项**，也就是说即便现在给 UserFeignClient 补上 fallback，也还需要同步打开 circuitbreaker 开关才会被调用——这是面试中很能体现细节掌握度的一点。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java:16-29",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:158,253-261（N+1 调用点）",
        "social-service/src/main/java/com/hmdp/social/service/impl/FollowServiceImpl.java:90",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:42-112（全量映射，无 /user/list）",
        "order-service/src/main/java/com/hmdp/order/feign/VoucherFeignClient.java:10-18（有 fallback 的对照实现）",
        "order-service/src/main/resources/application.yaml:119-127（feign.circuitbreaker.enabled: true）",
      ],
    },

    // ---------------- Q7 ----------------
    {
      cat: "接口设计", level: "深入",
      title: "博客列表接口 POST /blog、GET /blog/{id}、GET /blog/hot 的实现有什么共性问题？",
      focus: "考察点：列表接口的性能模型、代码复用与重复实现、能否把「三层放大」串起来讲成一个完整的性能故事。",
      follows: [
        "GET /blog/hot 的排序字段有索引吗？数据量大时会怎样？",
        "queryBlogOfFollow 里的 ORDER BY FIELD 是什么？有性能问题吗？",
        "这三个接口哪些能加缓存？为什么现在没加？",
      ],
      blocks: [
        { t: "h", x: "1．三个接口的实现要点" },
        {
          t: "code",
          x: "BlogServiceImpl.queryHotBlog:52-65\n  query().orderByDesc(\"liked\").page(new Page<>(current, MAX_PAGE_SIZE));   // :54-56\n  records.forEach(blog -> { queryBlogUser(blog); isBlogLiked(blog); });   // :60-63\n\nBlogServiceImpl.queryBlogById:240-251\n  Blog blog = getById(id);                       // :242  单条，OK\n  queryBlogUser(blog);                           // :247  1 次 Feign\n  isBlogLiked(blog);                             // :249  2 次 Redis\n\nBlogServiceImpl.queryBlogOfFollow:193-237\n  List<Blog> blogs = query().in(\"id\", ids)\n          .last(\"ORDER BY FIELD(id,\" + StrUtil.join(\",\", ids) + \")\").list();  // :221",
        },
        { t: "h", x: "2．问题一：三层性能放大叠加在同一批接口上" },
        {
          t: "p",
          x: "把一次「热门列表」请求的开销完整拆开，这是最能体现系统思维的一段：",
        },
        {
          t: "code",
          x: "GET /blog/hot?current=1   （一页 10 条）\n\n  第 1 层  DB     : SELECT * FROM tb_blog ORDER BY liked DESC\n                   → tb_blog 的 DDL 未在仓库提供，无法确认 liked 列是否有索引；\n                     若没有，当笔记量上来就是 filesort + 全表扫描\n  第 2 层  Feign  : 10 次（逐条补齐昵称头像）        ← N+1\n  第 3 层  Redis  : 20 次（每条 isBlogLiked 走 TYPE + ZSCORE）← N+1 且每次 2 个往返\n\n  合计约 31 次串行 IO，全部在同一个 HTTP 请求线程内完成。\n  若各项 RT 分别为 DB 5ms / Feign 10ms / Redis 1ms：\n     5 + 10×10 + 1×20 ≈ 125ms  —— 远超单接口预算。",
        },
        {
          t: "p",
          x: "优化优先级应当是：**先消除放大（批量替代循环，收益 10 倍）→ 再减少往返（pipeline / 一次 ZSCORE 替代两次，收益 2 倍）→ 最后才考虑加缓存**。很多人一上来就说「加 Redis 缓存」，但在这个接口里，缓存解决的是第 1 层 DB 的问题，而第 2、3 层的 N+1 放大才是主要开销——**盲目加缓存会把 N+1 一起缓存进去，问题只是被掩盖而非解决**。这个判断顺序是很好的加分点。",
        },
        { t: "h", x: "3．问题二：ORDER BY FIELD 的取舍" },
        {
          t: "code",
          x: "BlogServiceImpl:221\n  query().in(\"id\", ids)\n         .last(\"ORDER BY FIELD(id,\" + StrUtil.join(\",\", ids) + \")\")\n         .list();\n\n生成的 SQL 形如：\n  SELECT ... FROM tb_blog WHERE id IN (105,104)\n  ORDER BY FIELD(id,105,104);",
        },
        {
          t: "p",
          x: "这段代码的目的是**保持 Redis 返回的顺序**——`IN` 查询本身不保证顺序（实际通常按主键升序返回），而 Feed 必须按时间倒序展示，所以用 `FIELD()` 在 SQL 里显式排序。这个需求本身是合理的。",
        },
        {
          t: "p",
          x: "缺点有三：① `ORDER BY FIELD(...)` 是 **MySQL 专有语法**，不可移植；② 排序在**数据库侧**完成，无法利用索引（等价于 filesort）；③ 需要拼接 id 列表生成 SQL 字符串，虽然这里 id 来自 Redis 返回的数值、**不存在注入风险**，但仍是「SQL 拼接」的坏味道，容易被代码审查挑出来。",
        },
        {
          t: "p",
          x: "值得说明的是**这里的影响其实很小**：因为 `queryBlogOfFollow` 每页只取 2 条（`BlogServiceImpl:199` 的 count=2），`IN` 列表最多 2 个 id，filesort 的开销几乎可以忽略。**把「这个写法不优雅」和「这个写法在这里造成实际性能问题」区分开，是比单纯骂一句「不要拼接 SQL」更成熟的回答。** 更干净的做法是在 Java 侧按 Redis 返回的 id 顺序重排（用 `Map<Long, Blog>` 做映射），彻底不依赖数据库排序。",
        },
        { t: "h", x: "4．问题三：为什么这些接口都没有缓存" },
        {
          t: "code",
          x: "全仓检索 social-service 内 @Cacheable / @CacheEvict / RedisCacheManager：\n  - SocialApplication.java:17 有 @EnableCaching（开启了缓存能力）\n  - 但没有任何一个方法使用缓存注解，也没有 CacheConfig\n  - 唯一的信息缓存是 Redis 里的点赞/关注/Feed 三类结构（状态类，非对象缓存）\n\n对比 shop-service：有 CacheConfig.java:44-57（RedisCacheManager，TTL 30 分钟）\n                与 ShopServiceImpl.java:359-360 的 @Cacheable(value=\"shopCache\", key=\"#id\")",
        },
        {
          t: "p",
          x: "**社交内容（尤其是热门笔记）天然适合缓存**：读多写少、允许秒级延迟、数据量大。`GET /blog/hot` 的排序结果甚至可以直接缓存成一段 JSON（TTL 几秒到几十秒），因为热门榜单的变化频率远低于访问频率。而 `queryBlogById` 更是标准的对象缓存场景（与 shop 的 `queryById` 同构）。",
        },
        {
          t: "p",
          x: "需要注意的是**点赞数这类计数器不适合缓存**——它变化频繁且用户对「我刚点的赞没显示」很敏感。可行的折中是：缓存博客正文与作者信息（变化慢），点赞数实时从 Redis 取（已经存在 `blog:liked:{id}` 的 `ZCARD`）。**这种「按字段分而治之」的思路，比「整个对象缓存/不缓存」的二选一更贴合实际。**",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:52-65（queryHotBlog）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:240-251（queryBlogById）",
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogServiceImpl.java:193-237（queryBlogOfFollow，含 :221 ORDER BY FIELD）",
        "social-service/src/main/java/com/hmdp/social/SocialApplication.java:17（@EnableCaching）",
        "shop-service/src/main/java/com/hmdp/shop/config/CacheConfig.java:44-57（有缓存的对照实现）",
      ],
    },

    // ---------------- Q8 ----------------
    {
      cat: "评论模块", level: "深入",
      title: "评论功能是怎么实现的？分页真的生效了吗？博客的评论数会同步更新吗？",
      focus: "考察点：对 MyBatis-Plus 分页机制的掌握（最容易踩的坑）、读写两侧的数据一致性、对「代码看起来对但实际不对」的敏感度。",
      follows: [
        "MyBatis-Plus 的分页插件你配了吗？不配会怎样？",
        "为什么这里写死 10 条，而别的地方用 SystemConstants？",
        "评论数 comments 字段谁维护？",
      ],
      blocks: [
        { t: "h", x: "1．实现（全部代码只有 14 行）" },
        {
          t: "code",
          x: "BlogCommentsServiceImpl.java（全量 36 行，业务逻辑在 :22-35）\n\n  @Override\n  public Result saveComment(BlogComments comment) {           // :22\n      boolean isSuccess = save(comment);                     // :24  直接落库\n      if (!isSuccess) return Result.fail(\"保存评论失败\");\n      return Result.ok();\n  }\n\n  @Override\n  public Result queryCommentsByBlogId(Long blogId, Integer current) {   // :32\n      return Result.ok(query().eq(\"blog_id\", blogId)\n              .page(new Page<>(current, 10)));                // :34  写死 10\n  }",
        },
        { t: "h", x: "2．追问 1：分页插件没配，分页不生效（重点）" },
        {
          t: "code",
          x: "全仓检索（排除 agent-service / rag-service / target）：\n  MybatisPlusInterceptor        → 0 命中\n  PaginationInnerInterceptor    → 0 命中\n  implements WebMvcConfigurer   → 0 命中\n  @Bean 形式的 MyBatis 配置类    → 0 命中\n\n各服务 application.yaml:31-33 只配了：\n  mybatis-plus:\n    type-aliases-package: com.hmdp.entity\n    mapper-locations: classpath*:/mapper/**/*.xml\n  → 没有任何分页相关配置\n\n（顺带一提：order/shop/user/voucher 的 resources/mapper 目录都是空的，\n  说明项目 0 自定义 XML，全部基于 BaseMapper + Wrapper）",
        },
        {
          t: "p",
          x: "**MyBatis-Plus 的分页是「插件式」的：`Page` 对象本身只承载参数，真正的 `LIMIT` 语句是由 `PaginationInnerInterceptor` 拦截 SQL 后改写生成的。没有注册这个拦截器，`page()` 调用会退化成一次普通查询——`records` 里装的是**符合条件的所有行**，`total` 恒为 0，`pages` 恒为 0。**",
        },
        {
          t: "p",
          x: "所以 `queryCommentsByBlogId` 的实际行为是「**把这篇博客的全部评论一次性查出来返回**」。数据量小的时候看不出任何异常（返回的数据就是对的，只是多了），一旦有条爆款笔记有几千条评论，这个接口就会：① 一次查全表匹配行，内存与网络开销剧增；② 返回体巨大，前端渲染卡顿；③ 而且因为 `total` 为 0，前端的分页组件也拿不到总页数，**分页控件根本转不起来**。",
        },
        {
          t: "p",
          x: "修法很简单——注册拦截器，一行 Bean：",
        },
        {
          t: "code",
          x: "@Configuration\npublic class MybatisPlusConfig {\n    @Bean\n    public MybatisPlusInterceptor mybatisPlusInterceptor() {\n        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();\n        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));\n        return interceptor;\n    }\n}\n→ 放在 common 模块，5 个业务服务自动生效（与 SaTokenConfig 的注册方式一致，\n   通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports）",
        },
        {
          t: "p",
          x: "**同一个问题还影响 `queryHotBlog`（BlogServiceImpl:54-56）与 `queryBlogByUserId`（:266-268）**——它们同样用了 `page(new Page<>(current, MAX_PAGE_SIZE))`。也就是说社交服务里**三个分页接口全部不生效**。这是本板块最值得主动抛出来的缺陷：它不影响功能正确性、只在数据量上来后才暴露，属于典型的「能通过所有功能测试的性能炸弹」。",
        },
        { t: "h", x: "3．追问 2：写死 10 的问题" },
        {
          t: "code",
          x: "BlogCommentsServiceImpl:34    new Page<>(current, 10)                    ← 硬编码 10\nBlogServiceImpl:56            new Page<>(current, SystemConstants.MAX_PAGE_SIZE)  ← 常量（=10）\n\ncommon/.../utils/SystemConstants.java:6-7\n  DEFAULT_PAGE_SIZE = 5\n  MAX_PAGE_SIZE     = 10\n\n→ 两处语义相同（都是每页 10 条）却写法不一致；且 DEFAULT_PAGE_SIZE 全仓 0 引用（死常量）。",
        },
        {
          t: "p",
          x: "影响不大，但反映出一个真实问题：**页大小这个参数分散在代码里，没有统一收口。** 更值得讨论的是「页大小该不该由客户端传」。当前社交服务的所有列表接口都是**服务端固定页大小**（10 或 2），客户端只能传页码——好处是防止恶意客户端传 `size=100000` 打垮数据库；坏处是无法适配不同屏幕。**安全优先、服务端固定页大小是正确取向**，如果面试官问「为什么不把 size 暴露给前端」，这就是标准答案。",
        },
        { t: "h", x: "4．追问 3：评论数不同步（真实缺陷）" },
        {
          t: "code",
          x: "saveComment 只做了一件事：保存评论（BlogCommentsServiceImpl:24）\n对 tb_blog 的 comments 计数字段没有任何更新。\n\n对比 Blog 实体：common/.../entity/Blog.java:82\n  private Integer comments;   // 评论数量\n\n对比点赞：likeBlog 会因为一次点赞去 update tb_blog.liked（BlogServiceImpl:110）\n→ 点赞维护了计数，评论却完全没有维护。",
        },
        {
          t: "p",
          x: "结果是 `tb_blog.comments` 会**永远停留在初始值**（0 或后台刷的假数据），前端若用这个字段展示「xx 条评论」，就会与实际评论列表长度不符。这是一处**功能不完整**而非性能问题。",
        },
        {
          t: "p",
          x: "顺带指出一个更深的建模问题：**冗余计数只要存在，就必然面临一致性挑战。** 当前项目里点赞用「DB 计数器 + Redis ZSet 判重」维护，评论用「完全不维护」。两种做法都不理想。工业界的标准解法是：要么**彻底不存冗余计数**，展示时用 `SELECT COUNT(*)`（有 `idx_blog_id` 索引，几十万行以内都很快，见 `docs/SQL/start.sql:80-94` 中 `tb_shop_comments` 的 `idx_blog_id`）；要么**异步维护**（评论落库后发消息，由消费者更新计数，允许秒级延迟）。当前项目既没有唯一索引保护、也没有对账任务，属于「有两种手段，但一种都没用全」的状态。",
        },
        {
          t: "p",
          x: "另外一个数据模型上的注意点：评论支持两级结构（`BlogComments.parentId` 与 `answerId`，`BlogComments.java:48,53`），但查询接口**只有平铺的分页列表**（`BlogCommentsServiceImpl:34`），没有按 `parentId` 组装层级树的逻辑。也就是说「回复某条评论」这个数据能存进去，但**查不出来**——前端拿到的是一个没有层级的平铺列表。如果有时间，这是可以主动补上的一块。",
        },
      ],
      sources: [
        "social-service/src/main/java/com/hmdp/social/service/impl/BlogCommentsServiceImpl.java:22-35（全量业务逻辑）",
        "social-service/src/main/java/com/hmdp/social/controller/BlogCommentsController.java:30-46",
        "common/src/main/java/com/hmdp/entity/BlogComments.java:48,53,66-68（parentId / answerId / status）",
        "common/src/main/java/com/hmdp/entity/Blog.java:82（comments 冗余计数）",
        "各服务 src/main/resources/application.yaml:31-33（mybatis-plus 配置，无分页插件）",
        "全仓检索 MybatisPlusInterceptor / PaginationInnerInterceptor → 0 命中",
        "docs/SQL/start.sql:80-94（tb_shop_comments 的 idx_blog_id 索引）",
      ],
    },
  ],
};
