// 生成分册并输出到桌面「简历」目录
// 第一批：高性能秒杀 / 系统性缓存设计 / 微服务设计（10/10/6）
// 第二批：社交Feed流与关系链 / 用户账号体系与登录会话 / 数据库设计与持久层（8/8/8）
const path = require("path");
const { buildDoc } = require("./build3.js");

const OUT_DIR = "C:/Users/86178/Desktop/简历";

const docs = [
  // —— 第一批（简历明确点名的三个板块）——
  require("./content-seckill.js"),
  require("./content-cache.js"),
  require("./content-micro.js"),
  // —— 第二批（简历其余项目描述 + 源码中独立成块的模块）——
  require("./content-social.js"),
  require("./content-user.js"),
  require("./content-db.js"),
];

(async () => {
  let total = 0;
  for (const d of docs) {
    const outFile = path.join(OUT_DIR, d.outFile);
    await buildDoc({
      title: d.title,
      subtitle: d.subtitle,
      overview: d.overview,
      overviewBullets: d.overviewBullets,
      questions: d.questions,
      outFile,
    });
    total += d.questions.length;
  }
  console.log(`\n全部完成：${docs.length} 份文档，合计 ${total} 道主问题`);
})();
