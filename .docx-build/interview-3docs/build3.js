// 生成《Live-Hub 项目面试题与标准答案》三份分册 Word 文档
// 排版沿用 .docx-build/build.js 的既有风格：全文微软雅黑、A4、深蓝题号色块
// 每道题结构：题号色块 → 题干 → 考察点 → 追问链 → 标准答案 → 答案依据 → 简历表述 vs 源码实证
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Footer, AlignmentType,
  ShadingType, BorderStyle, PageNumber,
} = require("docx");

const FONT = { ascii: "Microsoft YaHei", hAnsi: "Microsoft YaHei", eastAsia: "微软雅黑", cs: "Microsoft YaHei" };
const DARK = "1F4E79";        // 主色（标题条）
const GRAY = "595959";        // 次要文字
const CODE_BG = "F2F2F2";     // 代码/路径引用块
const GAP_BG = "FFF4E5";      // 双轨标注底色
const GAP_BORDER = "E08A1E";  // 双轨标注左边框
const LEVEL_COLOR = { "基础": "2E7D32", "进阶": "C55A11", "深入": "C00000" };

// ---------- 基础构件 ----------
const p = (text, opts = {}) => new Paragraph({
  alignment: opts.align || AlignmentType.JUSTIFIED,
  spacing: { before: opts.before || 0, after: opts.after == null ? 60 : opts.after, line: opts.line || 300 },
  indent: opts.indent,
  border: opts.border,
  keepNext: !!opts.keepNext,
  children: [new TextRun({ text, size: opts.size || 20, bold: !!opts.bold, color: opts.color, font: FONT })],
});

const hh = (text) => new Paragraph({
  spacing: { before: 140, after: 40 },
  keepNext: true,
  children: [new TextRun({ text, bold: true, size: 20, color: DARK, font: FONT })],
});

// 代码 / 路径引用块（灰底、缩进、逐行）
const code = (text) => text.split("\n").map((ln, i, arr) => new Paragraph({
  spacing: { before: i === 0 ? 60 : 0, after: i === arr.length - 1 ? 80 : 0 },
  shading: { type: ShadingType.CLEAR, fill: CODE_BG },
  indent: { left: 240, right: 120 },
  keepLines: true,
  children: [new TextRun({ text: ln.length ? ln : " ", size: 18, color: "333333", font: FONT })],
}));

// 题号色块条
const qBar = (n, cat, level) => new Paragraph({
  spacing: { before: 400, after: 100 },
  shading: { type: ShadingType.CLEAR, fill: DARK },
  keepNext: true,
  children: [
    new TextRun({ text: `第 ${n} 题`, bold: true, size: 24, color: "FFFFFF", font: FONT }),
    new TextRun({ text: `　｜　${cat}`, bold: true, size: 22, color: "D9E2F3", font: FONT }),
    new TextRun({ text: `　｜　难度：${level}`, bold: true, size: 22, color: "FFD966", font: FONT }),
  ],
});

// 追问链条（左侧竖线框）
const followBox = (follows) => follows.map((f, i) => new Paragraph({
  spacing: { before: i === 0 ? 60 : 0, after: i === follows.length - 1 ? 160 : 40 },
  indent: { left: 240 },
  alignment: AlignmentType.JUSTIFIED,
  border: { left: { style: BorderStyle.SINGLE, size: 12, color: DARK, space: 8 } },
  children: [
    new TextRun({ text: `追问 ${i + 1}：`, bold: true, size: 20, color: DARK, font: FONT }),
    new TextRun({ text: f, size: 20, color: "404040", font: FONT }),
  ],
}));

// 答案依据
const sourceBlock = (sources) => {
  const out = [new Paragraph({
    spacing: { before: 140, after: 40 },
    keepNext: true,
    children: [new TextRun({ text: "答案依据（可在仓库中逐条查证）：", bold: true, size: 20, color: DARK, font: FONT })],
  })];
  sources.forEach((s) => out.push(new Paragraph({
    spacing: { after: 30 },
    indent: { left: 240, hanging: 180 },
    children: [new TextRun({ text: `▸ ${s}`, size: 18, color: GRAY, font: FONT })],
  })));
  return out;
};

// 双轨标注：简历表述 vs 源码实证
const gapBlock = (gap) => {
  const out = [new Paragraph({
    spacing: { before: 160, after: 40 },
    shading: { type: ShadingType.CLEAR, fill: GAP_BG },
    keepNext: true,
    children: [new TextRun({ text: "⚠ 简历表述 vs 源码实证", bold: true, size: 20, color: "8A4B00", font: FONT })],
  })];
  const line = (label, text, color) => out.push(new Paragraph({
    spacing: { after: 40 },
    shading: { type: ShadingType.CLEAR, fill: GAP_BG },
    alignment: AlignmentType.JUSTIFIED,
    children: [
      new TextRun({ text: `${label}`, bold: true, size: 19, color, font: FONT }),
      new TextRun({ text, size: 19, color: "333333", font: FONT }),
    ],
  }));
  line("简历原文：", gap.claim, "B45309");
  line("源码实证：", gap.fact, "C00000");
  line("答辩建议：", gap.advice, "1F6F3F");
  out.push(new Paragraph({ spacing: { after: 120 }, shading: { type: ShadingType.CLEAR, fill: GAP_BG }, children: [new TextRun({ text: " ", size: 12, font: FONT })] }));
  return out;
};

// ---------- 文档装配 ----------
function buildDoc({ title, subtitle, overview, overviewBullets, questions, outFile }) {
  const children = [];

  // 文档头
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { before: 120, after: 40 },
    children: [new TextRun({ text: "Live-Hub（仿大众点评微服务系统）", bold: true, size: 30, font: FONT })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 160 },
    children: [new TextRun({ text: title, bold: true, size: 32, color: DARK, font: FONT })],
  }));
  children.push(new Paragraph({
    alignment: AlignmentType.JUSTIFIED,
    spacing: { after: 120, line: 300 },
    children: [new TextRun({ text: subtitle, size: 19, color: GRAY, font: FONT })],
  }));

  // 一、模块技术实现概述
  children.push(new Paragraph({
    spacing: { before: 200, after: 100 },
    shading: { type: ShadingType.CLEAR, fill: "EAF1F8" },
    keepNext: true,
    children: [new TextRun({ text: "一、模块技术实现概述", bold: true, size: 24, color: DARK, font: FONT })],
  }));
  children.push(p(overview));
  if (overviewBullets) overviewBullets.forEach((b) => children.push(new Paragraph({
    spacing: { after: 30 },
    indent: { left: 240, hanging: 180 },
    children: [new TextRun({ text: `· ${b}`, size: 19, color: GRAY, font: FONT })],
  })));

  // 二、主问题
  children.push(new Paragraph({
    spacing: { before: 240, after: 100 },
    shading: { type: ShadingType.CLEAR, fill: "EAF1F8" },
    keepNext: true,
    children: [new TextRun({ text: `二、主问题与追问链（共 ${questions.length} 道）`, bold: true, size: 24, color: DARK, font: FONT })],
  }));

  questions.forEach((q, i) => {
    children.push(qBar(i + 1, q.cat, q.level));
    children.push(new Paragraph({
      spacing: { after: 60, line: 300 },
      keepNext: true,
      alignment: AlignmentType.JUSTIFIED,
      children: [new TextRun({ text: `题目：${q.title}`, bold: true, size: 22, font: FONT })],
    }));
    children.push(p(q.focus, { color: GRAY, after: 100 }));

    children.push(new Paragraph({
      spacing: { before: 60, after: 40 },
      keepNext: true,
      children: [new TextRun({ text: "▎针对性追问链（逐层递进）", bold: true, size: 20, color: DARK, font: FONT })],
    }));
    followBox(q.follows).forEach((x) => children.push(x));

    children.push(new Paragraph({
      spacing: { before: 60, after: 40 },
      keepNext: true,
      children: [new TextRun({ text: "▎标准答案", bold: true, size: 20, color: DARK, font: FONT })],
    }));
    q.blocks.forEach((b) => {
      if (b.t === "h") children.push(hh(b.x));
      else if (b.t === "code") code(b.x).forEach((x) => children.push(x));
      else children.push(p(b.x));
    });

    sourceBlock(q.sources).forEach((x) => children.push(x));
    if (q.gap) gapBlock(q.gap).forEach((x) => children.push(x));
  });

  const doc = new Document({
    styles: { default: { document: { run: { font: FONT, size: 20 } } } },
    sections: [{
      properties: {
        page: { size: { width: 11906, height: 16838 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } },
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

  return Packer.toBuffer(doc).then((buffer) => {
    fs.writeFileSync(outFile, buffer);
    console.log(`OK: ${outFile}  (${questions.length} 题, ${buffer.length} bytes)`);
  });
}

module.exports = { buildDoc, FONT, DARK, GRAY };
