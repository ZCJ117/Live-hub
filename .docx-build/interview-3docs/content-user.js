// 用户账号体系与登录会话 · 8 道主问题
module.exports = {
  outFile: "用户账号体系与登录会话_面试题.docx",
  title: "用户账号体系与登录会话 · 面试题与标准答案",
  subtitle:
    "基于《左常健 · 后端研发简历》Live-Hub 项目经历（“覆盖用户、商户、秒杀、订单、社交 Feed 流”、“Sa-Token”）+ D:\\hm-dianping 源码实证整理。本板块代码集中在 user-service（8086）与 common/utils。含金量最高的三个追问是「Sa-Token 接管后遗留的死代码」「验证码无任何防爆破手段」「签到 BITFIELD 位运算为什么正确」，请重点准备。",
  overview:
    "用户域独立为 user-service（8086），提供验证码发送、验证码登录、登出、当前用户、用户信息与签到六类接口。登录流程是：校验手机号正则 → 生成 6 位随机码写入 Redis（login:code:{phone}，TTL 2 分钟）→ 校验通过后立即删除验证码（保证一次性）→ 按手机号查用户、不存在则自动注册 → 调 StpUtil.login(userId) 建立会话，把 UserDTO 放进 Sa-Token Session → 返回 token 字符串。登录态完全由 Sa-Token 接管，会话数据经 Redis 在网关与各业务服务间共享；UserHolder 作为统一取数入口，先查 ThreadLocal、再回落到 StpUtil 会话。签到用 Redis BitMap 实现，一个月一个 key，每天占 1 bit，统计连续签到靠 BITFIELD 取数 + 循环右移。注意：密码登录在当前代码中明确返回「暂未实现」。",
  overviewBullets: [
    "验证码：UserServiceImpl.sendCode:50-70，RandomUtil.randomNumbers(6) → Redis，TTL 2 分钟，仅打日志不真发短信",
    "登录：UserServiceImpl.login:75-121，验证码校验通过即删除(:94)，StpUtil.login(:113) + Session 存 UserDTO(:117)",
    "密码登录：UserServiceImpl:96-98 直接 return Result.fail(\"密码登录功能暂未实现\")",
    "会话：Sa-Token（token-name: Authorization，timeout 30 天，is-share/is-concurrent 均 true），配置在网关与 user-service 各一份",
    "取数：UserHolder.getUser:24-39，ThreadLocal → StpUtil.getSession().get(\"user\") 双级回退",
    "签到：UserServiceImpl.sign:139-153（SETBIT）、signCount:155-195（BITFIELD u{N} 0 + 循环右移）",
  ],
  questions: [
    // ---------------- Q1 ----------------
    {
      cat: "登录链路", level: "基础",
      title: "登录功能完整讲一下：验证码怎么发、怎么校验、登录成功后返回什么？",
      focus: "考察点：能否把一条完整链路讲清楚、关键设计点（一次性验证码）的意图、对「验证码实际没发出去」这种实现现状的诚实度。",
      follows: [
        "为什么校验通过后要立刻删除 Redis 里的验证码？不删会怎样？",
        "验证码为什么存 Redis 而不是存 Session？",
        "第一次登录的用户是怎么被创建出来的？手机号会重复吗？",
      ],
      blocks: [
        { t: "h", x: "1．发验证码" },
        {
          t: "code",
          x: "UserServiceImpl.sendCode:50-70\n\n  if (RegexUtils.isPhoneInvalid(phone)) return Result.fail(\"手机格式错误\");   // :53-56\n  String code = RandomUtil.randomNumbers(6);                                // :58\n  stringRedisTemplate.opsForValue().set(\n          LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);  // :63\n  log.debug(\"发送短信验证码成功，验证码:{}\", code);                              // :67\n\n常量：common/.../utils/RedisConstants.java:4-5\n  LOGIN_CODE_KEY = \"login:code:\"\n  LOGIN_CODE_TTL = 2L   （单位分钟 → 验证码有效期 2 分钟）",
        },
        {
          t: "p",
          x: "**注意 :67 是 `log.debug` 而不是真正的短信发送**——项目里并没有接短信服务商，验证码是通过日志输出的。这一点必须主动说明，否则面试官一旦追问「你用的哪家短信服务、怎么处理发送失败」，答不上来会很被动。**如实说明「当前是日志打印，接真实短信只需把这一行换成短信 SDK 调用，并增加发送失败的重试与补偿」是正确姿态。**",
        },
        { t: "h", x: "2．校验并登录" },
        {
          t: "code",
          x: "UserServiceImpl.login:75-121\n\n  1) 校验手机号正则                                          // :77-81\n  2) 取 code / password                                      // :83-84\n  3) 分支：\n     if (code 非空) {                                        // :85\n         cacheCode = GET login:code:{phone}                   // :87\n         if (不一致) return Result.fail(\"验证码错误\");          // :88-91\n         DELETE login:code:{phone}      ← 一次性               // :94\n     } else if (password 非空) {\n         return Result.fail(\"密码登录功能暂未实现\");             // :98\n     } else { return Result.fail(\"请输入验证码或密码\"); }\n  4) User user = query().eq(\"phone\", phone).one();            // :103\n  5) if (user == null) user = createUserWithPhone(phone);     // :106-109\n  6) StpUtil.login(user.getId());                             // :113\n     StpUtil.getSession().set(\"user\", userDTO);                // :117\n  7) return Result.ok(StpUtil.getTokenValue());               // :120",
        },
        { t: "h", x: "3．追问 1：为什么立即删除验证码" },
        {
          t: "p",
          x: "**验证码必须是一次性的，删除动作必须在「校验通过」之后、在「业务处理」之前。** 如果不删，2 分钟的有效期内同一个验证码可以被无限次使用：① 验证码在传输链路中被截获（如日志泄露、中间人），攻击者可以反复用它登录；② 用户主动分享验证码给他人，会形成「一次下发、多人登录」；③ 与「验证码即身份凭证」的安全模型冲突。",
        },
        {
          t: "p",
          x: "代码里删除的位置是**正确的**（`:94`，在 :88 校验通过之后、:103 查库之前）。但由于是「校验」和「删除」两次独立的 Redis 调用，**并发下仍存在竞态**：两个请求同时 GET 到同一个 code，都判定为「一致」，然后都执行登录。要彻底消除需要用 Lua 脚本把「GET + 比对 + DEL」做成一次原子操作（与秒杀模块用的是同一套思路），或用 `GETDEL` 命令（Redis 6.2+）一次性取走。**这是一个很好的举一反三点——同一个「检查-执行非原子」的问题在登录和点赞两个模块都出现了。**",
        },
        { t: "h", x: "4．追问 2：为什么用 Redis 不用 Session" },
        {
          t: "code",
          x: "UserServiceImpl:62 有一段被注释掉的原实现：\n//  session.setAttribute(\"code\",code);   NOTE 这里用redis代替session\n\nHttpSession 参数仍留在方法签名与 Controller 中，但从未被使用：\n  UserController.sendCode:43   public Result sendCode(@RequestParam(\"phone\") String phone, HttpSession session)\n  UserController.login:53      public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session)",
        },
        {
          t: "p",
          x: "在单体应用里，Session 存在应用服务器内存中，浏览器靠 `JSESSIONID` Cookie 找回自己的会话，简单够用。但**拆成微服务后这套机制会崩**：① 网关收到请求后转发给 user-service，Session 落在 user-service 的内存里，如果后面再请求 shop-service，它拿不到这份 Session；② 一旦 user-service 扩容成多实例，没有会话粘性时下一次请求可能落到另一个实例，Session 就丢了；③ 重启即丢失全部登录态。",
        },
        {
          t: "p",
          x: "把验证码与会话放到 Redis，就变成了**无状态应用 + 集中式会话存储**：任何一个服务实例、任何一次请求，都能通过同一个 key 拿到同一份数据。这也是 Sa-Token 会话能在网关与 5 个业务服务间共享的根本原因——它们连的是同一个 Redis（见 `gateway-service/application.yaml:58-68`，注释明确写着「Sa-Token 会话持久化」）。",
        },
        {
          t: "p",
          x: "顺带指出：`HttpSession` 这个参数已经是**遗留物**（`UserController:43,53`），既不使用也会让 Spring 创建一次 Session 对象（无谓开销），应当删掉。这属于「重构后没清理干净」的痕迹。",
        },
        { t: "h", x: "5．追问 3：首次登录自动注册" },
        {
          t: "code",
          x: "UserServiceImpl.createUserWithPhone:125-132\n\n  User user = new User();\n  user.setPhone(phone);\n  user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));  // \"user_\" + 10 位随机\n  save(user);\n  return user;\n\nSystemConstants.java:5   USER_NICK_NAME_PREFIX = \"user_\"",
        },
        {
          t: "p",
          x: "这是「**验证码即注册**」的常见产品设计：手机号首次登录即自动建号，省掉独立的注册流程。昵称是随机兜底值，用户后续可自行修改。**这个设计在电商/本地生活类产品里很常见（拼多多、美团都是如此）**，面试时可以说明这是有意为之而非图省事。",
        },
        {
          t: "p",
          x: "**但这里有一个必须主动说明的风险：代码层没有任何防重复注册的保护。** `query().eq(\"phone\", phone).one()`（`:103`）是「先查后插」，两个并发的首次登录请求会同时查到 null，然后各自 `save` 一次，生成两条同手机号的用户。唯一能兜住的是 `tb_user` 表上的 **phone 唯一索引**——而 `tb_user` 的建表语句**在仓库中没有提供**（`docs/SQL/start.sql` 只到 `tb_user_info`，没有 `tb_user`、`tb_blog`、`tb_blog_comments` 三张表），所以**这一层到底有没有保护，无法从代码确认**。",
        },
        {
          t: "p",
          x: "**这类「无法确认」的问题，正确答法是**：说明「如果 phone 上有唯一索引，`save` 会抛 `DuplicateKeyException`，当前代码没有捕获它，会直接冒泡成 500——虽然挡住了脏数据，但用户体验很差」；并给出改法：`save` 时捕获唯一键冲突后重新 `query` 一次返回已存在用户，让「并发首次登录」表现为幂等成功。**能说清「约束在哪一层、失效时表现如何」，比含糊地说『应该加了索引吧』强得多。**",
        },
      ],
      sources: [
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:50-132",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:42-56",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:4-5",
        "common/src/main/java/com/hmdp/utils/RegexUtils.java:14-16、RegexPatterns.java:10",
        "common/src/main/java/com/hmdp/utils/SystemConstants.java:5",
        "docs/SQL/start.sql（无 tb_user 建表语句，phone 唯一性无法从仓库确认）",
      ],
    },

    // ---------------- Q2 ----------------
    {
      cat: "会话管理", level: "基础",
      title: "登录态是怎么维持的？token 是怎么生成的？用户信息存在哪里？",
      focus: "考察点：有状态与传统 Session 的区别、Sa-Token 的会话模型、会话数据与 token 的绑定关系、跨服务共享会话的实现条件。",
      follows: [
        "token 是 UUID，会不会重复？为什么不用 JWT？",
        "用户改了昵称，已经登录的用户看到的还是旧昵称吗？",
        "会话的超时时间是怎么配的？30 天合理吗？",
      ],
      blocks: [
        { t: "h", x: "1．会话建立过程" },
        {
          t: "code",
          x: "UserServiceImpl.login:111-120\n\n  StpUtil.login(user.getId());                        // :113  ① 建立会话，写 token-xxx → userId 映射\n  UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);   // :116\n  StpUtil.getSession().set(\"user\", userDTO);          // :117  ② 会话属性写入\n  return Result.ok(StpUtil.getTokenValue());          // :120  ③ token 字符串返回给前端\n\nSa-Token 配置（user-service/application.yaml:41-48 与 gateway-service/application.yaml:71-78 完全一致）\n  sa-token:\n    token-name: Authorization      # 从 Authorization 请求头取 token\n    timeout: 2592000               # 30 天（秒）\n    active-timeout: -1             # 不做活跃超时\n    is-concurrent: true            # 允许同一账号并发登录\n    is-share: true                 # 同一账号多次登录共用一个 token\n    token-style: uuid              # token 形式为 UUID\n    is-log: false",
        },
        {
          t: "p",
          x: "Sa-Token 的模型可以概括为：**token 是钥匙，Session 是保险箱。** `StpUtil.login(userId)` 会在 Redis 里建立三类数据：① token → userId 的映射（`satoken:login:token:{token}`）；② userId → 该用户全部有效 token 的集合（用于踢人下线、查在线设备）；③ Session 对象（`satoken:login:session:{userId}`），也就是 `:117` 写入 UserDTO 的地方。",
        },
        {
          t: "p",
          x: "因此后续任意一个业务服务收到带 `Authorization` 头的请求时，`StpUtil.isLogin()` 会拿 token 去 Redis 查映射，命中即视为已登录，`StpUtil.getSession()` 就能取回那份 UserDTO。**这就是为什么网关校验过之后，业务服务不需要再查数据库就能拿到用户身份**——会话数据在 Redis 里，所有服务共享同一个 Redis 即可。",
        },
        { t: "h", x: "2．追问 1：UUID 会不会重复？为什么不用 JWT" },
        {
          t: "p",
          x: "`token-style: uuid` 生成的是标准 UUID v4，122 位随机量，碰撞概率约 2^-61 量级——**在工程尺度上可以认为不会重复**。比 UUID 更弱的是 `random-32` 之类，更强的是雪花算法变体。UUID 的问题是长（36 字符）、无序（无法按时间排序）、且不携带信息（每次校验都要查一次 Redis）。",
        },
        {
          t: "code",
          x: "两种会话模型的对比：\n\n  有状态（Sa-Token 当前方案）\n    token = 随机串，服务端存会话  →  可随时踢人下线 / 强制过期 / 改权限立即生效\n    代价：每次校验一次 Redis 查询（网关 + 业务服务各一次）\n\n  无状态（JWT）\n    token = 自包含的签名数据（payload 里含 userId、角色、过期时间）\n    →  不查库即可验签，省一次 Redis；适合跨系统/第三方开放平台\n    代价：签发后无法撤销（除非再维护黑名单，那就又变成有状态了）、\n          payload 变大、需要处理密钥轮换、无用信息随请求反复传输",
        },
        {
          t: "p",
          x: "**在本项目里选择有状态方案是合理的**：这是一个需要「登录态共享 + 可踢人下线 + 会话数据（UserDTO）随取随用」的电商类系统，Sa-Token 把这三件事一次解决，而 JWT 的撤销能力恰是它的短板。如果面试官问「为什么不用 JWT」，答「JWT 的优势是省一次存储查询，但我们需要即时踢人下线和会话存储，用 JWT 反而要在 Redis 里维护黑名单，等于把有状态又加回来了，得不偿失」——这是能体现选型判断的回答。",
        },
        { t: "h", x: "3．追问 2：用户改昵称后的会话陈旧问题" },
        {
          t: "code",
          x: "登录时一次性快照：\n  UserServiceImpl:116-117   UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);\n                            StpUtil.getSession().set(\"user\", userDTO);\n\n之后所有接口都从这里取：\n  common/.../utils/UserHolder.java:32-33\n      if (StpUtil.isLogin()) return (UserDTO) StpUtil.getSession().get(\"user\");\n\nUserDTO 只有三个字段（common/.../dto/UserDTO.java:6-10）：\n  id / nickName / icon",
        },
        {
          t: "p",
          x: "**是的，会陈旧。** 用户登录后修改昵称，他当前的会话里仍是旧昵称，直到重新登录（或主动刷新会话）才会更新。当前项目里并没有「修改昵称」的接口（`UserController` 全量映射中只有查询，没有 update），所以这个问题暂时不会暴露——**但如果被追问「你打算怎么实现改昵称」，必须能答出「改完 DB 后同步 `StpUtil.getSession().set(\"user\", newDTO)` 刷新会话，否则用户会看到自己的旧昵称」**。这是一个很典型的「会话快照 vs 实时读取」的取舍：快照省一次查询但会陈旧，实时读则每次都要查库。",
        },
        {
          t: "p",
          x: "**这也解释了 UserDTO 为什么只有三个字段**（`:6-10`）——会话里只放**读多写少、变化极慢、且到处都要用**的最小信息，把所有用户数据都塞进会话会导致：① Redis 内存暴涨（每个在线用户一份完整用户对象）；② 任何字段更新都要刷新会话，一致性维护成本高。**「会话只存最小必要信息」是正确取向**，值得主动说明。",
        },
        { t: "h", x: "4．追问 3：超时配置" },
        {
          t: "code",
          x: "timeout: 2592000        → 2592000 秒 = 30 天（绝对有效期，从登录时刻算起）\n  active-timeout: -1      → 关闭活跃超时（不做「闲置 N 分钟自动登出」）\n\n含义：\n  - 一个 token 从签发起，无论用户是否活跃，30 天后必然失效\n  - 但只要在 30 天内，即使用户 29 天没打开过应用，token 依然有效\n  - 且 is-share: true，同一账号反复登录拿到的是同一个 token，\n    timeout 不会因为再次登录而重置（token 本身没变）",
        },
        {
          t: "p",
          x: "30 天是「用户体验」与「安全」的一个折中：电商类 App 希望用户尽量少登录（一键下单体验），所以选择长有效期 + 无活跃超时。**但安全上这并不理想**：token 一旦泄露（日志、抓包、共享设备），攻击者可以无感使用长达 30 天，而系统没有任何手段感知异常。",
        },
        {
          t: "p",
          x: "改进方向（都是可讨论的加分点）：① 开启 `active-timeout`（如 30 分钟），配合前端静默续期；② 缩短 `timeout` 到 7 天并实现「refresh token」机制；③ 结合设备指纹/登录 IP 变化做风控，异常时强制下线（Sa-Token 提供 `StpUtil.kickout(userId)` 与 `StpUtil.logout(tokenValue)`）；④ `is-concurrent: true` 允许同账号多端同时在线，若产品上不允许，可设为 false 实现「后登录踢掉先登录」。",
        },
        {
          t: "p",
          x: "**注意 `is-share: true` 的语义容易被忽略**：它表示「同一账号多次登录共用同一个 token」。好处是同一用户在多端登录只占一份会话存储；坏处是**在一端登出会同时登出所有端**（`UserController.logout:67` 调用 `StpUtil.logout()` 是登出当前 token），且无法精细区分设备。如果要实现「查看我的登录设备并单独下线某台」，需要把 `is-share` 关掉。",
        },
      ],
      sources: [
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:111-120",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:62-69（logout）",
        "common/src/main/java/com/hmdp/utils/UserHolder.java:24-39",
        "common/src/main/java/com/hmdp/dto/UserDTO.java:6-10",
        "user-service/src/main/resources/application.yaml:41-48 与 gateway-service/src/main/resources/application.yaml:71-78（sa-token 配置一致）",
        "gateway-service/src/main/resources/application.yaml:58-68（Redis 配置，注释「Sa-Token 会话持久化」）",
      ],
    },

    // ---------------- Q3 ----------------
    {
      cat: "会话管理", level: "进阶",
      title: "UserHolder 为什么要先从 ThreadLocal 取、再回落到 StpUtil？SaTokenConfig 这个类里是空的，它有什么用？",
      focus: "考察点：迁移遗留代码的识别能力、ThreadLocal 在 Web 容器下的正确用法、对「看起来有配置其实什么都没配」的敏感度。",
      follows: [
        "ThreadLocal 在 Tomcat 线程池下不清理会有什么后果？",
        "SaTokenConfig 是空类，为什么还要它？",
        "UserHolder.getUser 里为什么要 try-catch 吞掉异常？",
      ],
      blocks: [
        { t: "h", x: "1．UserHolder 的双级取值" },
        {
          t: "code",
          x: "common/src/main/java/com/hmdp/utils/UserHolder.java（全量 46 行）\n\n  private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();\n\n  public static void saveUser(UserDTO user) { tl.set(user); }        // :17-19\n\n  public static UserDTO getUser() {\n      UserDTO user = tl.get();                                       // :26  第一级：ThreadLocal\n      if (user != null) return user;                                 // :27-29\n      try {                                                          // :31  第二级：Sa-Token\n          if (StpUtil.isLogin()) return (UserDTO) StpUtil.getSession().get(\"user\");  // :32-33\n      } catch (Exception e) { /* 忽略 */ }                            // :35-37\n      return null;                                                   // :38\n  }\n\n  public static void removeUser() { tl.remove(); }                   // :44-46",
        },
        {
          t: "p",
          x: "这是**版本迁移留下的双轨结构**。原单体版本里，登录信息由 `LoginInterceptor` 在 `preHandle` 中解析 token、查出 UserDTO、调 `UserHolder.saveUser` 写进 ThreadLocal，请求结束时在 `afterCompletion` 调 `removeUser` 清理。改造成 Sa-Token 之后，拦截器被删除（全仓检索 `implements HandlerInterceptor` / `WebMvcConfigurer` / `addInterceptors` → **0 命中**），改由 Sa-Token 的会话承载，但 UserHolder 这个统一取数入口被保留下来，于是形成了「ThreadLocal 优先、StpUtil 兜底」的兼容写法。",
        },
        {
          t: "p",
          x: "**关键事实：在 Live-Hub 的运行时里，ThreadLocal 这一级是永远为 null 的。** 全仓检索 `saveUser(` 的调用点，只有 `agent-service/src/test/.../TransferControllerTest.java:35`（测试代码），5 个业务服务没有任何地方写入。所以 `:26` 的 `tl.get()` 每次都返回 null，`:32` 的 StpUtil 分支才是真正生效的路径。",
        },
        {
          t: "p",
          x: "这个结构本身**没有 bug**（多一次 null 判断的开销可以忽略），但它是一个**认知陷阱**：读代码的人会以为「有人在写 ThreadLocal」，从而误判用户身份的来源。更值得警惕的是它潜在的**线程复用污染**：Tomcat 用线程池处理请求，如果一个请求写了 ThreadLocal 却没有 `removeUser`，这个线程归还池子后被下一个请求复用时，`tl.get()` 会返回**上一个用户的身份**——这是最严重级别的鉴权漏洞（用户 A 看到用户 B 的数据）。当前之所以没出事，纯粹是因为根本没人写它。",
        },
        {
          t: "p",
          x: "**建议的答法是主动指出这个隐患并给出方案**：要么彻底删掉 ThreadLocal 这一级，只保留 StpUtil 单一数据源（最干净，符合「一个事实只有一个来源」）；要么保留但在 Sa-Token 的拦截器/过滤器中用 `try { ... } finally { UserHolder.removeUser(); }` 保证清理。**「没有被使用」不等于「不会出问题」——这类遗留代码的正确处理方式是删除而不是保留兼容。**",
        },
        { t: "h", x: "2．追问 1：SaTokenConfig 是空类" },
        {
          t: "code",
          x: "common/src/main/java/com/hmdp/config/SaTokenConfig.java（全量 14 行）\n\n  /**\n   * Sa-Token 自动配置，仅在 Servlet 容器中生效（排除 WebFlux 网关）\n   * 通过 AutoConfiguration.imports 注册，确保引用 common 的所有业务服务自动加载\n   */\n  @Configuration\n  @ConditionalOnWebApplication(type = Type.SERVLET)\n  public class SaTokenConfig {\n      //  ← 类体是空的，没有任何 @Bean、没有任何拦截器注册\n  }\n\n注册文件 common/src/main/resources/META-INF/spring/\n        org.springframework.boot.autoconfigure.AutoConfiguration.imports\n  →  内容只有一行：com.hmdp.config.SaTokenConfig",
        },
        {
          t: "p",
          x: "**它确实什么都没做。** 一个空的 `@Configuration` 被自动配置机制加载后，唯一效果是「创建了一个空 Bean」。注释里说的「确保引用 common 的所有业务服务自动加载」是**不成立的**——Sa-Token 能工作的真正原因，是各服务 pom 里引入了 `sa-token-spring-boot3-starter`（或类似 starter），由 Sa-Token 自己的自动配置类完成 `StpUtil`、拦截器、Redis 存储的装配。",
        },
        {
          t: "p",
          x: "**那它为什么存在？** 最可能的解释是：它曾是**其他配置的落脚点**（比如早先放过 `SaInterceptor` 的注册），后来在改造中把内容搬走或删掉了，只留下空壳与注释。结论是——**这是一个可以安全删除的死类**（连同 imports 文件里的那一行）。",
        },
        {
          t: "p",
          x: "面试时如实说明即可：「这个类是重构后的残留，实际不起作用。它的存在反而是有害的——会让人误以为业务服务里注册了什么鉴权拦截器，实际上业务服务**完全没有任何二次鉴权**」。后者正是下一个问题要讲的。",
        },
        { t: "h", x: "3．追问 2：为什么要 try-catch 吞异常" },
        {
          t: "code",
          x: "UserHolder.java:31-37\n\n  try {\n      if (StpUtil.isLogin()) return (UserDTO) StpUtil.getSession().get(\"user\");\n  } catch (Exception e) {\n      // StpUtil 尚未初始化或不在 Web 上下文中，忽略\n  }\n  return null;",
        },
        {
          t: "p",
          x: "这段 catch 的意图是让 `UserHolder.getUser()` 成为**一个在任何上下文都能安全调用**的方法：在 Web 请求线程里正常返回用户；在启动初始化、定时任务、MQ 消费线程、单元测试等**没有请求上下文**的场景下，Sa-Token 可能因取不到当前请求而抛出 `SaTokenContextException`，此时返回 null 而不是让调用方崩溃。",
        },
        {
          t: "p",
          x: "**这是一个双刃剑式的设计。** 好处是健壮；坏处是**在 Web 请求里如果因为 bug 导致取不到用户，也会静默返回 null**，调用方若没有判空就会在下一行 NPE，而堆栈只会指向 NPE 的位置，真正的根因（context 异常）被吞掉了，排查成本变高。对比看 `BlogServiceImpl.likeBlog:102` 直接 `UserHolder.getUser().getId()` 不判空，`NotificationController.my:23-25` 却手动判空——**项目内部对「getUser 可能返回 null」的处理并不一致**，说明这个约定本身没有被明确下来。",
        },
        {
          t: "p",
          x: "更规范的做法：捕获明确的具体异常类型（而不是 `Exception`），并至少 `log.debug` 一行，让「什么情况下返回 null」可被观测。**吞掉异常时必须留下痕迹**，这是通用准则。",
        },
      ],
      sources: [
        "common/src/main/java/com/hmdp/utils/UserHolder.java:11-46（全量）",
        "common/src/main/java/com/hmdp/config/SaTokenConfig.java:11-13（空类）",
        "common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports（只注册 SaTokenConfig）",
        "全仓检索 implements HandlerInterceptor / WebMvcConfigurer / addInterceptors → 0 命中",
        "全仓检索 UserHolder.saveUser( 调用点 → 仅 agent-service 测试代码",
        "social-service/src/main/java/com/hmdp/social/controller/NotificationController.java:22-27（判空的对照写法）",
      ],
    },

    // ---------------- Q4 ----------------
    {
      cat: "安全", level: "进阶",
      title: "验证码登录有什么安全问题？如果有人暴力破解验证码怎么办？",
      focus: "考察点：验证码环节的完整威胁模型（爆破、短信轰炸、并发重复使用）、限流与锁定策略、能否定位到「整个登录链路没有任何限流」这一事实。",
      follows: [
        "6 位数字验证码，2 分钟有效期，攻击者能不能穷举？",
        "sendCode 接口可以无限调用吗？有什么风险？",
        "怎么防？说具体的方案，不要只说「加限流」。",
      ],
      blocks: [
        { t: "h", x: "1．当前实现的防护清单（很薄）" },
        {
          t: "code",
          x: "已做的：\n  ✓ 手机号格式正则校验                      UserServiceImpl:53（RegexUtils.isPhoneInvalid）\n  ✓ 验证码 6 位随机数字                      UserServiceImpl:58（RandomUtil.randomNumbers(6)）\n  ✓ 验证码有效期 2 分钟                      UserServiceImpl:63（LOGIN_CODE_TTL = 2L 分钟）\n  ✓ 校验通过后立即删除，保证一次性              UserServiceImpl:94\n\n未做的（全仓检索均为 0 命中）：\n  ✗ sendCode 的发送频率限制（同一手机号 / 同一 IP）\n  ✗ 验证码校验失败次数限制\n  ✗ 账号锁定 / 人机验证（图形验证码、滑块）\n  ✗ 验证码错误次数计数（Redis 里没有 attempts 之类的 key）\n  ✗ 登录接口的网关限流\n\n网关侧的限流只覆盖 /agent/**（AgentRateLimitFilter.java:38-40），\n/user/login 与 /user/code 都在白名单里，且不经过任何限流过滤器。",
        },
        { t: "h", x: "2．追问 1：能不能穷举" },
        {
          t: "code",
          x: "验证码空间：6 位数字 = 10^6 = 1,000,000 种\n有效期：120 秒\n\n成功率 100% 所需速率：1,000,000 / 120 ≈ 8,333 次/秒（针对同一个手机号）\n成功率 50% 所需速率：≈ 4,166 次/秒\n成功率 1%（也就是每 100 次尝试成功 1 次，配合大量账号则总量可观）：≈ 83 次/秒\n\n⚠ 当前没有任何失败计数与锁定，可以无限次尝试。\n⚠ 且每次尝试都是「读 Redis + 字符串比较」，服务端成本极低，攻击者收益远大于成本。",
        },
        {
          t: "p",
          x: "单机 8 千 QPS 对同一个手机号是有点难度，但**分布式攻击（僵尸网络 / 代理池）完全可以达到**；而且攻击者往往是「广撒网」——对一万个手机号各尝试几百次，只要命中率不为零就能批量登录他人账号。**所以「6 位数字 + 无尝试次数限制」在安全上是不成立的组合。**",
        },
        { t: "h", x: "3．追问 2：短信轰炸" },
        {
          t: "p",
          x: "`sendCode` 接口可以无限调用，意味着：① 攻击者可以**用别人的手机号疯狂请求验证码**，造成短信轰炸骚扰（虽然本项目只打日志，但接了真实短信通道后就是真金白银的损失——每一条短信都有成本）；② 可以作为**移动端短信炸弹工具**被滥用，甚至导致短信通道被运营商封禁；③ 大量无效请求会占用服务端与 Redis 资源。",
        },
        {
          t: "p",
          x: "**这就是为什么行业里 sendCode 接口的保护强度往往比 login 更高。** 注意：本项目 sendCode 是白名单接口（`SaTokenGatewayConfig.java` 的 `addExclude`），**无需登录即可调用**，防护完全靠接口自身的限流，而接口自身没有限流。",
        },
        { t: "h", x: "4．追问 3：具体方案（分层作答，这是加分的关键）" },
        {
          t: "code",
          x: "第一层：发送频率限制（防短信轰炸）\n  - 同一手机号 60 秒内只能发 1 次\n      SET login:code:limit:{phone} 1 EX 60 NX   失败即拒绝\n  - 同一手机号每天上限（如 10 次）：INCR login:code:daily:{phone} + EXPIRE 到当天 24 点\n  - 同一 IP 每小时上限（防代理批量刷）\n  - 同手机号连续 3 次发送且都未使用 → 要求人机验证（滑块/图形码）\n\n第二层：校验失败限制（防爆破）——最关键的一层\n  - 每次校验失败：INCR login:code:fail:{phone}，首次时 EXPIRE 到与验证码对齐或独立的 10 分钟\n  - 失败次数 ≥ 5 → 直接作废当前验证码 + 拒绝该手机号登录 15 分钟\n  - 更严格的做法：验证码错误 3 次即 DELETE 验证码，强制重新获取\n    → 攻击者每 3 次尝试就要重发一次，成本提高数个数量级\n\n第三层：验证码本身\n  - 使用密码学安全随机数（Java 的 SecureRandom / ThreadLocalRandom 的加密变体），\n    而非 RandomUtil 的默认实现（伪随机，理论上可预测序列）\n  - 或者退一步：把「GET + 比对 + DEL」合并为 Lua 原子脚本 / GETDEL，\n    杜绝并发重复使用（与 Q1 提到的竞态同一个问题）\n\n第四层：兜底\n  - 网关对 /user/login 与 /user/code 加限流（项目里已有现成的 Lua 令牌桶实现\n    AgentTokenBucketLimiter，可直接复用，把 key 从 loginId 换成 IP/手机号维度）\n  - 风控：同一 IP 短时间命中多个不同手机号 → 直接封禁\n  - 监控告警：验证码错误率突增、单手机号请求量突增 → 触发告警",
        },
        {
          t: "p",
          x: "**回答这类问题时，按「发送侧 → 校验侧 → 验证码本身 → 基础设施」分层，比零散地说「加个限流、加个验证码」要有说服力得多。** 另外要主动说明本项目的现实情况：限流件（`AgentTokenBucketLimiter` + `limiter/token-bucket.lua`）**代码已经在仓库里了**，只是作用域仅限 `/agent/`，把 key 维度换成手机号/IP 并扩大匹配路径即可复用——**「我的项目里已有可复用的轮子，只是没用在这条链路上」是很有力的答案。**",
        },
        {
          t: "p",
          x: "还要补充一点**日志泄露风险**：`UserServiceImpl:67` 用 `log.debug` 打印了验证码明文。虽然 debug 级别在生产通常关闭，但一旦有人为了排查问题把日志级别临时调到 debug 并忘了改回来，验证码就会持续写入日志文件——**在真实的短信验证码系统里，日志中绝不能出现验证码明文**（应打码，如只打印前两位）。这是很多团队踩过的坑，主动提出来是加分项。",
        },
      ],
      sources: [
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:50-101（发码与校验全流程）",
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:67（验证码写入日志）",
        "gateway-service/src/main/java/com/hmdp/gateway/config/SaTokenGatewayConfig.java:21-41（白名单含 /user/login、/user/code）",
        "gateway-service/src/main/java/com/hmdp/gateway/filter/AgentRateLimitFilter.java:38-40（限流仅覆盖 /agent/）",
        "gateway-service/src/main/java/com/hmdp/gateway/limit/AgentTokenBucketLimiter.java + limiter/token-bucket.lua（可复用的限流轮子）",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:4-5",
      ],
    },

    // ---------------- Q5 ----------------
    {
      cat: "安全", level: "进阶",
      title: "密码登录实现了吗？如果让你实现，密码应该怎么存？项目里的 PasswordEncoder 能用吗？",
      focus: "考察点：对自身代码现状的诚实度、密码哈希的选型依据（为什么不能用 MD5）、能否发现工具类「写了但没接上」。",
      follows: [
        "为什么不能用 MD5 存密码？加盐能不能解决？",
        "加盐 MD5 和 BCrypt 的本质区别是什么？",
        "PasswordEncoder 这个类现在有人用吗？",
      ],
      blocks: [
        { t: "h", x: "1．现状：密码登录没有实现，但预留了全部痕迹" },
        {
          t: "code",
          x: "① DTO 里有字段     common/.../dto/LoginFormDTO.java:6-9\n     private String phone;  private String code;  private String password;\n\n② Service 里有分支   UserServiceImpl:96-98\n     } else if (password != null && !password.isEmpty()) {\n         // 密码登录（待实现）\n         return Result.fail(\"密码登录功能暂未实现\");\n     }\n\n③ 工具类写了但没人用\n     common/.../utils/PasswordEncoder.java（全量 33 行，含 encode / matches）\n     → 全仓检索 PasswordEncoder 的引用：只有它自己的定义，0 个调用点\n\n④ 实体里有密码字段   common/.../entity/User.java:43\n     private String password;   // 密码，加密存储",
        },
        {
          t: "p",
          x: "**面试时这里必须诚实：密码登录功能尚未实现，接口明确返回失败提示。** 不要虚构。正确的开场是「当前版本只支持验证码登录，密码登录的 DTO 字段与工具类已预留但未接线，下面我讲一下如果要实现我会怎么做」——**把「未实现」转化为「展示设计能力」的机会**。",
        },
        { t: "h", x: "2．现有 PasswordEncoder 的实现与问题" },
        {
          t: "code",
          x: "common/src/main/java/com/hmdp/utils/PasswordEncoder.java\n\n  public static String encode(String password) {\n      String salt = RandomUtil.randomString(20);                      // :13  随机 20 位盐\n      return encode(password, salt);\n  }\n  private static String encode(String password, String salt) {\n      return salt + \"@\" + DigestUtils.md5DigestAsHex(\n              (password + salt).getBytes(StandardCharsets.UTF_8));   // :19  MD5(password + salt)\n  }\n  public static Boolean matches(String encodedPassword, String rawPassword) {\n      String[] arr = encodedPassword.split(\"@\");                      // :28\n      String salt = arr[0];                                            // :30\n      return encodedPassword.equals(encode(rawPassword, salt));       // :32  重新计算并比较\n  }",
        },
        {
          t: "p",
          x: "逻辑上是正确的加盐哈希：每次加密生成随机盐，盐与摘要一起存储（`salt@hash` 格式），校验时用同一个盐重新计算。**这比「明文存密码」和「无盐 MD5」都要好，但依然不达标。**",
        },
        {
          t: "p",
          x: "**核心问题：「快」就是原罪。** MD5 是设计给「快速计算摘要」的算法，单张现代 GPU 每秒可以计算**数十亿次** MD5。这意味着：① 拿到 `salt@hash` 后，攻击者可以用 GPU 对常见弱密码字典（千万级）做穷举，加盐只阻止了「彩虹表跨用户复用」，挡不住「针对单个用户的定向爆破」；② 因为快，攻击者可以对每个用户独立跑字典，成本并不高。",
        },
        {
          t: "code",
          x: "各算法在密码场景下的定位：\n\n  MD5 / SHA-1 / SHA-256     ——  通用摘要算法，为速度设计\n                                  ❌ 绝不可用于密码（无论是否加盐）\n                                  单 GPU 每秒数十亿次\n\n  加盐 MD5                   ——  本项目现状\n                                  △  挡住彩虹表，挡不住定向爆破\n                                  单 GPU 每秒数亿次仍可行\n\n  PBKDF2 / bcrypt / scrypt\n  / Argon2                   ——  专为密码设计的慢哈希\n                                  ✓  正确选择\n                                  bcrypt 单次约 100ms（cost=10，可调）\n                                  Argon2 还额外抗 GPU/ASIC（内存硬）\n\nSpring Security 生态的标准做法：\n  new BCryptPasswordEncoder().encode(rawPassword)     // 自带随机盐，无需手工管理\n  new BCryptPasswordEncoder().matches(raw, encoded)",
        },
        {
          t: "p",
          x: "**加盐 MD5 与 BCrypt 的本质区别**：不是「加盐 / 不加盐」，而是**计算成本**。BCrypt 有一个可配置的 cost 参数（工作因子），cost 每 +1 计算量翻倍。服务端单次登录多花 100ms 完全可以接受（用户感知不到），但攻击者要做 10 亿次穷举就变成 10 亿 × 100ms ≈ 3 年——**用「合法的服务端成本」换取「攻击者的成本爆炸」，这才是密码哈希设计的核心思想。**",
        },
        {
          t: "p",
          x: "**另外两个实现细节问题**：① `RandomUtil.randomString(20)` 用的是 Hutool 的普通随机（非 `SecureRandom`），盐的随机性理论上可预测；② `matches` 用 `split(\"@\")` 解析，**依赖「盐里不含 @」这个隐含约定**——当前 `randomString` 只产生字母数字所以安全，但这是一种脆弱的耦合，若哪天有人把盐的字符集改宽就会静默出错。用 BCrypt 这些细节都由库处理掉了。",
        },
        { t: "h", x: "3．追问 3：如果让你实现密码登录" },
        {
          t: "code",
          x: "① 存储：改用 BCrypt（Spring Security Crypto 已在生态内，加一个依赖即可）\n     User.password 字段存 bcrypt 哈希（60 字符，varchar(100) 足够）\n     PasswordEncoder 工具类替换或删除\n\n② 注册/设置密码时：\n     user.setPassword(bcryptEncoder.encode(rawPassword));\n     密码强度校验用已有的 RegexPatterns.PASSWORD_REGEX（^\\w{4,32}$）\n     ⚠ 注意：当前正则允许 4 位纯数字，作为密码策略太弱，建议提高到\n       至少 8 位且要求字符类型混合（同一份 RegexPatterns.java:18）\n\n③ 登录时：\n     User user = query().eq(\"phone\", phone).one();\n     if (user == null || !bcryptEncoder.matches(password, user.getPassword())) {\n         return Result.fail(\"手机号或密码错误\");   // ⚠ 统一话术，不区分「用户不存在」和「密码错误」\n     }\n     后续与验证码登录一致：StpUtil.login + Session.set + 返回 token\n\n④ 安全配套：\n     - 密码错误次数限制（复用 Q4 的 fail 计数器，与验证码共用或独立）\n     - 传输层强制 HTTPS（否则密码明文过网）\n     - 查找用户走 phone 索引（需确认 tb_user 有索引，见 Q1）\n\n⑤ 风险提示：\n     - ⚠ 「统一话术」很重要：如果提示「用户不存在」，攻击者可以用它\n       枚举出平台上有哪些手机号（用户枚举漏洞）\n     - ⚠ User 实体含 password 字段且**没有 @JsonIgnore**（User.java:43），\n       当前没有任何接口直接返回 User（都转成了 UserDTO），所以暂时安全；\n       但这是隐患——建议加 @JsonIgnore，或统一用 VO 出参",
        },
      ],
      sources: [
        "common/src/main/java/com/hmdp/utils/PasswordEncoder.java:9-33（全量）",
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:96-98（密码登录未实现）",
        "common/src/main/java/com/hmdp/dto/LoginFormDTO.java:6-9（password 字段已预留）",
        "common/src/main/java/com/hmdp/entity/User.java:43（password 字段，无 @JsonIgnore）",
        "common/src/main/java/com/hmdp/utils/RegexPatterns.java:18（PASSWORD_REGEX = ^\\w{4,32}$）",
        "全仓检索 PasswordEncoder 引用 → 仅类定义，0 调用点",
      ],
    },

    // ---------------- Q6 ----------------
    {
      cat: "接口设计", level: "深入",
      title: "用户信息接口返回了哪些字段？为什么用 UserDTO 而不是直接返回 User？有没有越权问题？",
      focus: "考察点：数据脱敏的落地方式、DTO 与实体分离的价值、水平越权（IDOR）的识别、对「登录了但没校验归属」这类漏洞的敏感度。",
      follows: [
        "GET /user/{id} 和 GET /user/info/{id} 有什么区别？分别适合什么场景？",
        "登录用户可以查任意其他人的信息吗？这算漏洞吗？",
        "/user/list 这个接口存在吗？（承接社交模块的调用）",
      ],
      blocks: [
        { t: "h", x: "1．两个查询接口的差异" },
        {
          t: "code",
          x: "UserController.java\n\n  @GetMapping(\"/{id}\")            queryUserById:92-102      → 返回 UserDTO（id/nickName/icon）\n      User user = userService.getById(userId);\n      UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);\n\n  @GetMapping(\"/info/{id}\")       info:78-90               → 返回 UserInfo（城市/介绍/粉丝数/积分…）\n      UserInfo info = userInfoService.getById(userId);\n      info.setCreateTime(null);\n      info.setUpdateTime(null);                               // :86-87 手工置空时间字段\n      return Result.ok(info);\n\n  @GetMapping(\"/me\")              me:71-76                → 从会话取当前登录用户\n      UserDTO userDTO = UserHolder.getUser();\n\n  UserDTO（common/.../dto/UserDTO.java:6-10）\n      private Long id;  private String nickName;  private String icon;   ← 只有 3 个字段",
        },
        {
          t: "p",
          x: "**分工是清晰的**：`/user/{id}` 是「对外最小身份信息」，用于任何需要展示「是谁发的」的场景（社交模块的博客作者、点赞头像墙）；`/user/info/{id}` 是「扩展资料」，用于个人主页；`/me` 是「我自己」，数据从会话直接取，**不查库**（这也是 UserDTO 存进会话的价值所在——高频接口零 DB 开销）。",
        },
        { t: "h", x: "2．追问 1：为什么用 UserDTO" },
        {
          t: "code",
          x: "User 实体（common/.../entity/User.java:32-58）包含：\n  id / phone / password / nickName / icon / createTime / updateTime\n                      ↑ 敏感        ↑ 敏感\n\n如果直接返回 User，phone 与 password 会一起进入响应体 ——\n其中 password 按设计应存的是哈希值，但即便哈希泄露也为离线爆破提供了素材。",
        },
        {
          t: "p",
          x: "**UserDTO 只保留 `id / nickName / icon` 三个字段，从类型层面切断了敏感字段外泄的可能**——这是「白名单式出参」而不是「黑名单式排除」（`@JsonIgnore` 属于黑名单：将来给 User 加了新的敏感字段，忘了加注解就会泄露；而 DTO 是白名单：新字段默认不可见，必须显式添加）。**在对外接口上，白名单永远优于黑名单。** 这是一个可以主动强调的设计原则。",
        },
        {
          t: "p",
          x: "**同一份 UserDTO 还被复用为会话数据**（`UserServiceImpl:116-117`），所以它必须「足够小」（见 Q2 的分析）。一个 DTO 同时承担「接口出参」和「会话快照」两个职责，在这里是合理的复用——因为两者的需求恰好一致（只要最小身份信息）。**但要意识到这是巧合而非必然**：如果哪天「接口出参要加积分、会话不想加」，就应该拆成两个 DTO，而不是为了迁就现状让会话膨胀。",
        },
        {
          t: "p",
          x: "**`/user/info/{id}` 的手工置空（:86-87）则是黑名单式做法的典型**：靠 `info.setCreateTime(null)` 一行行排除。同样的问题——将来 `UserInfo` 加了敏感字段（比如身份证号），很容易忘记置空。此处更规范的做法同样是定义 `UserInfoVO`。",
        },
        { t: "h", x: "3．追问 2：越权（IDOR）" },
        {
          t: "code",
          x: "两个接口都是「按路径参数 id 查询」，没有做任何归属校验：\n\n  GET /user/123        → 返回 id=123 的用户的昵称与头像\n  GET /user/info/123   → 返回 id=123 的用户的城市、简介、粉丝数、积分、等级、生日、性别\n\n网关侧只做了「是否登录」的检查：\n  gateway-service/.../config/SaTokenGatewayConfig.java:21-41\n      .setAuth(obj -> SaRouter.match(\"/**\", r -> StpUtil.checkLogin()))\n  → 只校验「你是登录用户」，不校验「你有没有权限访问这条数据」\n\n业务服务侧：没有任何 @SaCheckLogin / @SaCheckPermission / 手动归属判断\n  （全仓检索 @SaCheckPermission → 0 命中）",
        },
        {
          t: "p",
          x: "**这是典型的「水平越权」（IDOR，Insecure Direct Object Reference）——只要登录，就能遍历 id 查看任意用户的信息。** 判断它算不算漏洞，取决于这些字段是否「本来就对所有人可见」：",
        },
        {
          t: "p",
          x: "`/user/{id}` 返回昵称与头像 —— 在社交产品里本来就是公开信息，**不算漏洞**（别人发博客时本来就要展示你的昵称头像）。`/user/info/{id}` 返回城市、简介、粉丝数、积分、会员等级、生日、性别 —— **其中「生日」是典型的个人隐私，会员等级与积分属于账号资产信息，这些应当仅本人可见**。当前实现把它们暴露给了任何登录用户，这是真实的信息泄露。",
        },
        {
          t: "p",
          x: "修法有两种：① **字段级脱敏**——把 `UserInfo` 拆成「公开资料 VO」（城市、简介、粉丝数、关注数）与「私有资料 VO」（积分、等级、生日、性别），`/user/info/{id}` 只返回公开部分，`/me/info` 返回完整部分；② **归属校验**——如果产品上个人资料本就只对本人开放，则在方法上加 `if (!userId.equals(UserHolder.getUser().getId())) return Result.fail(...)`。",
        },
        {
          t: "p",
          x: "**顺带指出一个更普遍的问题**：整个项目只有网关的「登录校验」，没有任何「授权」（Authorization）能力——没有角色、没有权限点、没有资源归属判断。Sa-Token 是支持 `@SaCheckRole` / `@SaCheckPermission` 的（`SaTokenExceptionHandler.java:25-35` 甚至已经写好了这两个异常的处理逻辑），但**全仓没有任何一处使用**。这说明异常处理器是「提前预留的能力」，而授权体系尚未接入。对学习型项目这可以接受，但要能清楚说明「认证（Authentication）与授权（Authorization）的区别，以及当前只做了前者」。",
        },
        { t: "h", x: "4．追问 3：/user/list 存在吗" },
        {
          t: "code",
          x: "user-service/.../controller/UserController.java 的全部 8 个映射：\n  POST /user/code         POST /user/login       POST /user/logout\n  GET  /user/me           GET  /user/info/{id}   GET  /user/{id}\n  POST /user/sign         GET  /user/sign/count\n\n→ ⚠ 没有 /user/list。\n\n但 social-service/.../feign/UserFeignClient.java:28-29 声明了：\n  @GetMapping(\"/user/list\")\n  Result getUserByIds(@RequestBody List<Long> ids);\n并被 BlogServiceImpl:158、FollowServiceImpl:90 调用。\n\n更微妙的是：@GetMapping(\"/{id}\") 是路径变量，请求 /user/list 时\n会尝试把字符串 \"list\" 绑定到 Long userId → 类型转换失败（400 / MethodArgumentTypeMismatchException）。",
        },
        {
          t: "p",
          x: "**结论：这个被两处业务依赖的批量查询接口根本不存在**，点赞排行榜与共同关注功能会失败。详见「社交 Feed 流与关系链」板块 Q6 的完整分析。这里放在用户板块问，是为了验证你是否清楚**自己服务的对外契约**——一个服务提供方说不出自己有哪些接口，是很致命的表现。",
        },
        {
          t: "p",
          x: "修法：在 `UserController` 补上批量查询接口。注意要用 `@PostMapping` 承接 `@RequestBody`（同步修改 Feign 侧），查询用 `query().in(\"id\", ids).list()` 一次搞定，再按需转成 UserDTO 列表返回。**补上它同时解决了社交模块的 N+1 问题**——一个接口的缺失，导致了「功能不可用」和「性能退化」两个后果，这是微服务里接口契约重要性的绝佳案例。",
        },
      ],
      sources: [
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:71-112（全量映射）",
        "common/src/main/java/com/hmdp/dto/UserDTO.java:6-10（仅 3 字段）",
        "common/src/main/java/com/hmdp/entity/User.java:32-58（含 phone / password）",
        "common/src/main/java/com/hmdp/entity/UserInfo.java:26-84（含 birthday / credits / level）",
        "common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java:25-35（NotRoleException / NotPermissionException 处理器，全仓无对应用法）",
        "social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java:28-29（调用了不存在的 /user/list）",
        "docs/SQL/start.sql:114-127（tb_user_info 含 credits / level / birthday 等字段）",
      ],
    },

    // ---------------- Q7 ----------------
    {
      cat: "签到", level: "深入",
      title: "签到功能怎么做的？为什么要用 BitMap？signCount 是怎么统计连续签到天数的？",
      focus: "考察点：Redis 位运算的实际运用、BITFIELD 的位序语义、能否讲清楚「为什么从低位开始数」这个非平凡的正确性推理。",
      follows: [
        "一个用户一个月的签到数据占多少内存？如果不用 BitMap 呢？",
        "BITFIELD 取出来的是一个数字，怎么从这个数字里数出连续的 1？",
        "跨月的连续签到怎么处理？现在这样写有问题吗？",
      ],
      blocks: [
        { t: "h", x: "1．签到：一个 bit 代表一天" },
        {
          t: "code",
          x: "UserServiceImpl.sign:139-153\n\n  Long userId = UserHolder.getUser().getId();                      // :142\n  LocalDateTime now = LocalDateTime.now();                          // :144\n  String keySuffix = now.format(DateTimeFormatter.ofPattern(\":yyyyMM\"));  // :146\n  String key = USER_SIGN_KEY + userId + keySuffix;                  // :147  sign:{userId}:{yyyyMM}\n  int dayOfMonth = now.getDayOfMonth();                             // :149\n  stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);// :151\n\nRedisConstants.java:21   USER_SIGN_KEY = \"sign:\"\n\n→ key 形如  sign:5:202610\n→ 10 月 6 日签到 = SETBIT sign:5:202610 5 1   （offset 从 0 开始）",
        },
        {
          t: "p",
          x: "**为什么用 BitMap**：一个用户一个月的签到状态只需要 **31 个 bit ≈ 4 字节**，而如果用「一天一条记录」（`tb_sign` 表就是这么设计的——`docs/SQL/start.sql:2-10` 有 `user_id/year/month/date/is_backup` 字段）则需要 31 行、每行至少几十字节，加上索引开销是几百倍。**BitMap 把「布尔状态 + 按位置索引」这两件事用最省的存储表达了，这正是它最适合的场景。**",
        },
        {
          t: "p",
          x: "换算一下：100 万用户各存一年签到 = 100 万 × 12 个月 × 4 字节 ≈ 48 MB，完全可以常驻内存；换成数据库行则是 100 万 × 365 行 = 3.65 亿行，无论存储还是查询都不是一个量级。Redis 还提供 `BITCOUNT` 直接统计总天数、`BITOP` 做多 key 的与/或运算（例如「求两个用户都签到的日期」）。",
        },
        {
          t: "p",
          x: "**注意 `tb_sign` 表虽然存在，但代码里从未写入过它**（全仓检索 `tb_sign` / `Sign` entity 的引用：只有 `UserServiceImpl.sign` 走 Redis）。也就是说「签到」这个功能的数据**只存在于 Redis 中**，没有持久化到 MySQL——一旦 Redis 数据丢失（重启未持久化、误删 key），全部签到记录不可恢复。这是一个需要主动说明的风险点，也解释了为什么 `tb_sign` 表会被保留——大概是「计划中要做 DB 落库但没做」。",
        },
        { t: "h", x: "2．统计连续签到：BITFIELD + 位运算" },
        {
          t: "code",
          x: "UserServiceImpl.signCount:155-195\n\n  int dayOfMonth = now.getDayOfMonth();                             // :165\n  List<Long> result = stringRedisTemplate.opsForValue().bitField(   // :167-171\n          key,\n          BitFieldSubCommands.create()\n              .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));\n\n  if (result == null || result.isEmpty()) return Result.ok(0);      // :172-175\n  Long num = result.get(0);\n  if (num == null || num == 0) return Result.ok(0);                 // :177-179\n\n  int count = 0;                                                    // :181\n  while (true) {\n      if ((num & 1) == 0) break;                                    // :184-186  最低位是 0 → 断了\n      else count++;                                                 // :188-189\n      num >>>= 1;                                                   // :192      无符号右移，看前一天\n  }\n  return Result.ok(count);",
        },
        {
          t: "p",
          x: "这段代码的正确性依赖于**两个不太平凡的位序约定**，讲不清就说明是抄的：",
        },
        {
          t: "code",
          x: "约定一：BITFIELD ... GET uN 0 读取的是 offset 0 开始的连续 N 个 bit，\n        其中 offset 0 是这个 N 位无符号数的**最高位**。\n\n举例：key 的 bit0..bit5 = 1,1,1,0,1,1  （今天是 6 号，offset 5 = 今天）\n      GET u6 0  →  二进制 111011  →  十进制 59\n\n      二进制 1 1 1 0 1 1\n      bit位  5 4 3 2 1 0     （按 u6 内的位序，左高右低）\n             ↑         ↑\n           offset0   offset5 = 今天 = 最低位\n\n结论：**最低位(bit 0 of value) 恰好对应 offset(dayOfMonth-1)，也就是「今天」。**\n\n约定二：于是从最低位开始 num & 1 就是「今天签没签」，\n        num >>>= 1 之后最低位变成「昨天」，循环直到遇到 0。\n\n  num = 59 = 111011\n  num & 1 = 1 → count=1, num >>>= 1 → 11101 (29)\n  num & 1 = 1 → count=2, num >>>= 1 → 1110  (14)\n  num & 1 = 0 → break\n  → 连续签到 2 天（10/6、10/5 签了，10/4 没签）✔",
        },
        {
          t: "p",
          x: "**为什么 NOT 从最高位开始？** 如果用 `num & (1 << (dayOfMonth-1))` 从高位取，也能写对，但代码会啰嗦且容易算错索引；从低位开始配合 `>>>=` 是更自然的写法。**关键是要意识到这个位序是反的——如果面试官追问「如果今天没签到会怎样」，答案是 `num & 1 == 0` 直接 break，返回 0，这也是正确的**（今天没签，连续天数为 0）。",
        },
        {
          t: "p",
          x: "另外两个细节值得指出：① 用 `>>>` 而不是 `>>` 是**必要的**——`Long` 是有符号的，虽然 `u{dayOfMonth}` 最大 31 位不会触碰到符号位，但用无符号右移语义上更准确、更不易出错；② **BITFIELD 返回的是 `List<Long>`，取 `get(0)` 之前必须判空**——代码里做了两层判空（`:172`、`:177`），这点是周全的。",
        },
        { t: "h", x: "3．追问 1：跨月问题（真实缺陷）" },
        {
          t: "code",
          x: "key = \"sign:\" + userId + \":202610\"     ← key 里含月份\nsignCount 只读**当前月**的 key\n\n场景：用户 9 月 29、30 日签到，10 月 1、2 日也签到\n  10/2 查 signCount → 读 sign:5:202610，只有 bit0、bit1 是 1\n                    → 循环两次就遇到 0（bit2 是 10/3，未到）\n                    → 返回 2\n  但用户实际是**连续 4 天**签到。\n\n更严重的：10/1 当天查 → 本月只有 bit0 是 1 → 返回 1\n             但若 9/30 也签了，实际应为 2。\n\n→ **每月 1 号，所有用户的连续签到天数都会被清零。**",
        },
        {
          t: "p",
          x: "这是按月分 key 的必然代价。修法有三：① **跨月拼接查询**——如果本月已经全部签满（连续天数 == dayOfMonth），再取上个月的 key 继续数；② **把 offset 改为「年内第几天」**——用一个年度 key（`sign:{userId}:2026`），offset = 该日期在一年中的天数，这样跨月天然连续，但 key 会随年份增长（一年 365 bit ≈ 46 字节，依然极小，其实**这个方案更简单也更优**）；③ **用 BitMap 存「连续天数」而不只是状态**——但这会破坏 BitMap 的紧凑性，不建议。",
        },
        {
          t: "p",
          x: "**方案 ② 是更推荐的**：用一个用户一个年的 key，offset 用 `now.getDayOfYear() - 1`，代码改动极小（`sign` 与 `signCount` 各改一行），却彻底解决了跨月问题，而且年度 key 的总内存开销比月度 key 还小（12 个 key 变 1 个 key）。**能主动提出「按月分 key 是我这个实现的一个缺陷，改成按年更好」是很有说服力的回答。**",
        },
        { t: "h", x: "4．追问 2：时区问题" },
        {
          t: "code",
          x: "UserServiceImpl:144   LocalDateTime now = LocalDateTime.now();\n     → 使用 JVM 默认时区（本机为中国标准时间 UTC+8）\n\n各服务 JDBC URL（如 content-micro 板块所述，5 个服务一致）：\n     jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC\n     → 明确指定 serverTimezone=UTC\n\nDDL 默认值：\n     create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP\n     → 用的是 MySQL 服务器的时间（容器通常为 UTC）",
        },
        {
          t: "p",
          x: "**存在「应用用 UTC+8、数据库用 UTC」的时区不一致。** 对签到功能的直接影响是：如果在 **23:00–24:00（北京时间）** 之间签到，`LocalDateTime.now()` 算作当天，而写入 `create_time` 的 `CURRENT_TIMESTAMP` 记的是前一天的 UTC 时间——两套时间在跨天边界上会打架。查询「某天的签到记录」时如果混用两套时间源，就会出现「明明签到了却查不到」的诡异现象。",
        },
        {
          t: "p",
          x: "**统一时区是分布式系统的基本功**，正确做法是：全链路统一用 UTC 存储、展示时再转本地时区；或全链路统一用同一个固定时区（如 Asia/Shanghai），并在 JVM 启动参数（`-Duser.timezone=Asia/Shanghai`）、数据库连接串（`serverTimezone=Asia/Shanghai`）、容器（`TZ` 环境变量）三处保持一致。当前项目这三处并未对齐，是一个可以主动提出的改进项。",
        },
      ],
      sources: [
        "user-service/src/main/java/com/hmdp/user/service/impl/UserServiceImpl.java:139-195（sign / signCount 全量）",
        "user-service/src/main/java/com/hmdp/user/controller/UserController.java:104-112",
        "common/src/main/java/com/hmdp/utils/RedisConstants.java:21（USER_SIGN_KEY = \"sign:\"）",
        "docs/SQL/start.sql:2-10（tb_sign 表结构，代码中从未写入）",
        "docs/SQL/start.sql:16-19 及 tb_* 各表的 create_time/update_time 默认值（CURRENT_TIMESTAMP）",
        "各服务 src/main/resources/application.yaml（JDBC URL 含 serverTimezone=UTC）",
      ],
    },

    // ---------------- Q8 ----------------
    {
      cat: "安全边界", level: "深入",
      title: "网关做了鉴权，业务服务还需要再校验吗？Feign 服务间调用是怎么带身份的？",
      focus: "考察点：微服务信任边界的认知、Feign 调用绕过网关的后果、能否把「认证只做了一半」讲清楚。",
      follows: [
        "social-service 通过 Feign 调 user-service，这个请求带 token 吗？会被网关拦截吗？",
        "如果有人直接访问 8086 端口绕过网关，会怎样？",
        "业务服务里 @SaCheckLogin 用了吗？为什么一个都没有？",
      ],
      blocks: [
        { t: "h", x: "1．当前的信任模型" },
        {
          t: "code",
          x: "唯一的一道鉴权闸门在网关：\n\n  gateway-service/.../config/SaTokenGatewayConfig.java:21-41\n      .addInclude(\"/**\")\n      .addExclude(\"/user/login\", \"/user/code\", \"/actuator/**\")\n      .setAuth(obj -> SaRouter.match(\"/**\", r -> StpUtil.checkLogin()));\n\n业务服务侧：\n  - 无 HandlerInterceptor / WebMvcConfigurer（全仓 0 命中）\n  - 无 @SaCheckLogin / @SaCheckRole / @SaCheckPermission（全仓 0 命中）\n  - 唯一的 SaTokenConfig 是空类（见 Q3）\n  - 仅有的防护是 UserHolder 返回 null 时业务代码自己判空\n\n→ 信任模型是：**只要请求到了业务服务，就认为它已经过网关校验。**",
        },
        {
          t: "p",
          x: "这是微服务里常见的「**边缘认证 + 内部信任**」模型，本身是一种合理的设计取向（避免每个服务重复校验、减少一次 Redis 查询），但它的成立有**两个前提**，本项目两条都不满足：",
        },
        { t: "h", x: "2．前提一：网络层必须保证业务服务不可被外部直接访问" },
        {
          t: "code",
          x: "各业务服务都是 Spring Boot Web 应用，监听 0.0.0.0:8082~8086\n（各服务 application.yaml: server.port，未配 server.address 限制绑定网卡）\n\nCLAUDE.md 中的项目约束写着：\n  「所有请求通过网关入口，网关校验 Sa-Token 后转发」\n  「禁止绕过网关直接访问业务服务」\n\n但这条约束在代码与部署配置里**没有任何强制手段**：\n  - 没有网络隔离（同一台机器上的 8086 端口直接可达）\n  - 没有服务间调用鉴权（无内部 token、无 mTLS）\n  - 没有校验请求来源",
        },
        {
          t: "p",
          x: "**后果**：`curl http://localhost:8086/user/123` 可以绕过网关直接拿到用户信息；`curl -X PUT http://localhost:8084/voucher-order/seckill/1` 甚至可以**绕过登录直接下单**（因为 `VoucherOrderController` 只有 `UserHolder.getUser().getId()` 这一行会失败，但如果某个接口不依赖登录态，就完全放行了）。**约束写在文档里、没有落在代码或基础设施上，等于没有约束。**",
        },
        {
          t: "p",
          x: "生产环境的正确做法是**网络层隔离**：业务服务只监听内网网卡（`server.address` 绑定内网 IP）、K8s 里用 NetworkPolicy 只允许网关的 Pod 访问、或走 Service Mesh 的 mTLS。**「不要在应用层重复造安全边界，而要在网络层建立它」** 是比「在业务服务里也加一遍 @SaCheckLogin」更专业的答案——虽然后者也常被用作纵深防御的第二道防线。",
        },
        { t: "h", x: "3．前提二：Feign 调用要能传递身份（当前做不到）" },
        {
          t: "code",
          x: "social-service/.../feign/UserFeignClient.java:16\n  @FeignClient(name = \"user-service\")        ← 按服务名直达，不经过网关\n\nFeign 的请求会带上什么头？\n  - Spring Cloud OpenFeign 默认**不会**自动透传原始请求的 Authorization 头\n  - 除非显式配置 RequestInterceptor 手动转发\n  - 全仓检索 RequestInterceptor / apply(RequestTemplate → 0 命中\n\n→ 所以 Feign 调用 user-service 时：请求里没有 token\n→ user-service 的 UserHolder.getUser() 会走到 StpUtil.isLogin() == false\n→ 返回 null\n\n这也解释了为什么 social-service 必须通过\nGET /user/{id} 拿用户信息，而不是「从会话里取」——\n**服务间调用本来就没有登录上下文。**",
        },
        {
          t: "p",
          x: "**这带来两个连锁问题**：",
        },
        {
          t: "p",
          x: "**① 有些接口本就拿不到用户身份，却硬依赖它。** `FollowServiceImpl.follow:42` 用 `UserHolder.getUser().getId()` 取当前用户——这个调用来自浏览器经网关的请求，所以有 token，没问题。但如果有别的服务通过 Feign 调用 `/follow/...`，就会 NPE。**当前之所以没出事，是因为跨服务调用恰好只走「无身份依赖」的接口（`/user/{id}`、`/user/list`）**，这是一个脆弱的巧合，而不是设计。",
        },
        {
          t: "p",
          x: "**② 如果将来要做「Feign 调用也需要身份」，必须补 RequestInterceptor。** 标准做法是注册一个 `RequestInterceptor`，从当前请求上下文把 `Authorization` 头拷贝到 Feign 的 `RequestTemplate` 上（需要在 `RequestContextHolder` 里取原始请求，并注意 Feign 默认在独立线程池执行、ThreadLocal 会丢失，需要配置 `feign.hystrix.enabled=false` 或 Hystrix/Sentinel 的线程隔离策略、或改用信号量隔离）。**能讲清「ThreadLocal 在跨线程时丢失，所以 Feign 拦截器取不到当前请求」这个坑，是很强的加分点。**",
        },
        { t: "h", x: "4．纵深防御：业务服务该不该再校验一次" },
        {
          t: "p",
          x: "**应该，但目的不是「替代网关」，而是「纵深防御 + 服务自治」。** 理由：① 网关配置会变（新增路由、误改白名单），业务服务不应依赖上游配置的正确性；② 服务可能被其他服务以内部方式调用，调用方的身份与权限可能不同于终端用户；③ 一个服务被单独部署/测试时（如直连调试）不该安全裸奔。",
        },
        {
          t: "p",
          x: "具体落地很轻量：在需要登录的 Controller 方法或类上加 `@SaCheckLogin`（Sa-Token 注解，零成本），在需要权限的地方用 `@SaCheckPermission`。**注意必须配置 Sa-Token 的注解拦截器（`SaInterceptor`）才会生效**——而项目里恰好没有注册任何拦截器（`SaTokenConfig` 是空类，见 Q3），所以即便现在加上注解也不会起作用。**这是一个很容易被忽略、但一被追问就露馅的细节：注解需要拦截器才能生效。**",
        },
        {
          t: "p",
          x: "值得顺带一提的是，`SaTokenExceptionHandler.java:19-42` 已经为 `NotLoginException` / `NotRoleException` / `NotPermissionException` 写好了统一异常处理——**这说明作者当初确实规划了完整的注解式鉴权，只是没接线**。把这条线讲出来（「异常处理器都写好了，缺的是拦截器注册和注解使用」），既诚实又能体现对代码的掌握。",
        },
      ],
      sources: [
        "gateway-service/src/main/java/com/hmdp/gateway/config/SaTokenGatewayConfig.java:21-41（唯一的鉴权闸门）",
        "social-service/src/main/java/com/hmdp/social/feign/UserFeignClient.java:16（Feign 按服务名直达）",
        "common/src/main/java/com/hmdp/config/SaTokenConfig.java:11-13（空类，未注册拦截器）",
        "common/src/main/java/com/hmdp/config/SaTokenExceptionHandler.java:19-42（已备好鉴权异常处理）",
        "各服务 src/main/resources/application.yaml（server.port，未限制 server.address）",
        "CLAUDE.md「关键约束」：禁止绕过网关直接访问业务服务",
        "全仓检索 @SaCheckLogin / @SaCheckPermission / RequestInterceptor → 0 命中",
      ],
    },
  ],
};
