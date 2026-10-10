// 生成《Live-Hub 项目面试题与标准答案（20题）》Word 文档
// 排版要求：全文微软雅黑；标准答案 10 号字；题目标题 12 号加粗色块；代码引用灰底
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Footer, AlignmentType,
  ShadingType, BorderStyle, PageNumber,
} = require("docx");

const FONT = { ascii: "Microsoft YaHei", hAnsi: "Microsoft YaHei", eastAsia: "微软雅黑", cs: "Microsoft YaHei" };
const DARK = "1F4E79";
const GRAY = "595959";

const questions = [...require("./content1.js"), ...require("./content2.js")];

// ---------- 文档头 ----------
const children = [];
children.push(new Paragraph({
  alignment: AlignmentType.CENTER,
  spacing: { before: 120, after: 40 },
  children: [new TextRun({ text: "Live-Hub（仿大众点评）微服务项目", bold: true, size: 32, font: FONT })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.CENTER,
  spacing: { after: 200 },
  children: [new TextRun({ text: "高频面试题与标准答案（20 题）", bold: true, size: 32, font: FONT })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.JUSTIFIED,
  spacing: { after: 80, line: 300 },
  children: [new TextRun({
    text: "技术栈：Java 21 · Spring Boot 3.1 · Spring Cloud Alibaba（Nacos / Gateway / OpenFeign / Seata / Sentinel）· Sa-Token · Redis（Redisson）· RocketMQ · MySQL（MyBatis-Plus）· Caffeine · Docker。答案依据 D:\\hm-dianping 项目源码整理，文中标注的类名、方法名、配置与代码路径均可在仓库中对应查证。",
    size: 20, color: GRAY, font: FONT,
  })],
}));
children.push(new Paragraph({
  alignment: AlignmentType.JUSTIFIED,
  spacing: { after: 240, line: 300 },
  children: [new TextRun({
    text: "题目分布：缓存穿透 / 击穿 / 雪崩 / 一致性 / 二级缓存（5 题）、分布式锁（2 题）、全局 ID（1 题）、秒杀高并发链路（2 题）、幂等性（1 题）、数据库优化（1 题）、消息队列可靠性（1 题）、分布式事务（1 题）、网关鉴权（1 题）、限流（1 题）、熔断降级（1 题）、Feed 流与 GEO（1 题）、智能客服 Agent（2 题）。",
    size: 20, color: GRAY, font: FONT,
  })],
}));

// ---------- 逐题渲染 ----------
questions.forEach((q, i) => {
  // 题号色块标题条
  children.push(new Paragraph({
    spacing: { before: 360, after: 100 },
    shading: { type: ShadingType.CLEAR, fill: DARK },
    keepNext: true,
    outlineLevel: 0,
    children: [
      new TextRun({ text: `第 ${i + 1} 题`, bold: true, size: 24, color: "FFFFFF", font: FONT }),
      new TextRun({ text: `　｜　${q.cat}`, bold: true, size: 24, color: "D9E2F3", font: FONT }),
    ],
  }));
  // 题干
  children.push(new Paragraph({
    spacing: { after: 60, line: 300 },
    keepNext: true,
    children: [new TextRun({ text: `题目：${q.title}`, bold: true, size: 22, font: FONT })],
  }));
  // 考察点
  children.push(new Paragraph({
    spacing: { after: 120 },
    children: [new TextRun({ text: q.focus, size: 20, color: GRAY, font: FONT })],
  }));
  // 标准答案块
  q.blocks.forEach((b) => {
    if (b.t === "h") {
      children.push(new Paragraph({
        spacing: { before: 120, after: 40 },
        keepNext: true,
        children: [new TextRun({ text: b.x, bold: true, size: 20, color: DARK, font: FONT })],
      }));
    } else if (b.t === "p") {
      children.push(new Paragraph({
        alignment: AlignmentType.JUSTIFIED,
        spacing: { after: 60, line: 300 },
        children: [new TextRun({ text: b.x, size: 20, font: FONT })],
      }));
    } else {
      // 代码 / 路径引用块（灰底、缩进；\n 拆分为多个段落）
      const lines = b.x.split("\n");
      lines.forEach((ln, idx) => {
        children.push(new Paragraph({
          spacing: { before: idx === 0 ? 60 : 0, after: idx === lines.length - 1 ? 80 : 0 },
          shading: { type: ShadingType.CLEAR, fill: "F2F2F2" },
          indent: { left: 240, right: 120 },
          keepLines: true,
          children: [new TextRun({ text: ln.length ? ln : " ", size: 20, color: "333333", font: FONT })],
        }));
      });
    }
  });
  // 追问与加分项
  if (q.follow) {
    children.push(new Paragraph({
      spacing: { before: 100, after: 160 },
      indent: { left: 240 },
      alignment: AlignmentType.JUSTIFIED,
      border: { left: { style: BorderStyle.SINGLE, size: 12, color: DARK, space: 8 } },
      children: [
        new TextRun({ text: "追问与加分项：", bold: true, size: 20, font: FONT }),
        new TextRun({ text: q.follow.replace(/^追问：/, ""), size: 20, color: "404040", font: FONT }),
      ],
    }));
  }
});

// ---------- 组装文档 ----------
const doc = new Document({
  styles: {
    default: {
      document: { run: { font: FONT, size: 20 } },
    },
  },
  sections: [{
    properties: {
      page: {
        size: { width: 11906, height: 16838 }, // A4
        margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 },
      },
    },
    footers: {
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [new TextRun({ children: ["第 ", PageNumber.CURRENT, " 页"], size: 18, color: GRAY, font: FONT })],
        })],
      }),
    },
    children,
  }],
});

Packer.toBuffer(doc).then((buffer) => {
  fs.writeFileSync(__dirname + "/interview-qa.docx", buffer);
  console.log("OK: interview-qa.docx generated,", buffer.length, "bytes,", questions.length, "questions");
});
