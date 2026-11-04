// 小红书宣传 PPT（竖版 9:16）：极光深色 + Figma 设计图。node tools/gen_xhs_ppt.js
const pptxgen = require("pptxgenjs");

const W = 7.5, H = 13.33;
const BG = "0A0C14", PRIMARY = "7C6CFF", ACCENT = "5AD8E6";
const TEXT = "F2F4FF", MUTED = "8A92B2", CARD = "161927", STROKE = "1F2438";
const FONT = "Microsoft YaHei";
const DIR = __dirname + "/../docs/video/";
const IMG = __dirname + "/../docs/img/";

let p = new pptxgen();
p.defineLayout({ name: "V916", width: W, height: H });
p.layout = "V916";
p.author = "phone-alarm";
p.title = "phone-alarm 小红书宣传";

const T = (s, text, o) => s.addText(text, { fontFace: FONT, color: TEXT, margin: 0, ...o });
const chip = (s, text, x, y, w) => {
  s.addShape(p.shapes.ROUNDED_RECTANGLE, { x, y, w, h: 0.56, rectRadius: 0.28, fill: { color: CARD, transparency: 18 }, line: { color: STROKE, width: 1 } });
  T(s, text, { x, y, w, h: 0.56, fontSize: 13, align: "center", valign: "middle", color: MUTED });
};

// ---- S1 封面 ----
let s = p.addSlide();
s.background = { path: DIR + "bg_cover.png" };
T(s, "跑完或卡住", { x: 0.7, y: 3.7, w: 6.1, h: 1.25, fontSize: 58, bold: true });
T(s, "都立刻叫你", { x: 0.7, y: 4.9, w: 6.1, h: 1.25, fontSize: 58, bold: true });
T(s, "长任务 · MCP · 防止白干一夜", { x: 0.7, y: 6.4, w: 6.1, h: 0.5, fontSize: 17, color: MUTED, charSpacing: 2 });
s.addNotes("");

// ---- S2 主界面 ----
s = p.addSlide();
s.background = { path: DIR + "bg_content.png" };
T(s, "极光深色 UI", { x: 0.6, y: 0.75, w: 6.3, h: 0.95, fontSize: 42, bold: true });
T(s, "挂上长任务，放心去休息", { x: 0.6, y: 1.78, w: 6.3, h: 0.45, fontSize: 16, color: ACCENT });
s.addImage({ path: IMG + "shot_main.png", x: 2.0, y: 2.55, w: 3.5, h: 7.59, shadow: { type: "outer", color: "000000", blur: 28, offset: 8, angle: 90, opacity: 0.55 } });
chip(s, "任务完成闹钟 · 重复 / 一次性", 1.65, 10.75, 4.2);
s.addNotes("挂上长任务，放心去休息");

// ---- S3 响铃界面 ----
s = p.addSlide();
s.background = { path: DIR + "bg_content.png" };
T(s, "AI 喊你回来", { x: 0.6, y: 0.75, w: 6.3, h: 0.95, fontSize: 42, bold: true });
T(s, "报错 / 中断 / 跑完 · 全屏 + 语音叫醒", { x: 0.6, y: 1.78, w: 6.3, h: 0.45, fontSize: 16, color: ACCENT });
s.addImage({ path: IMG + "shot_ring.png", x: 2.0, y: 2.55, w: 3.5, h: 7.59, shadow: { type: "outer", color: "000000", blur: 28, offset: 8, angle: 90, opacity: 0.55 } });
chip(s, "失败止损 · 不醒不行 · 5 分钟自动停", 1.55, 10.75, 4.4);
s.addNotes("训练跑完，AI立刻叫你回来");

// ---- S4 能力面 ----
s = p.addSlide();
s.background = { path: DIR + "bg_content.png" };
T(s, "12 个 MCP 工具", { x: 0.6, y: 1.05, w: 6.3, h: 0.95, fontSize: 42, bold: true });
T(s, "挂机训练、批处理、大下载——跑完或翻车都叫你", { x: 0.6, y: 2.08, w: 6.3, h: 0.45, fontSize: 16, color: MUTED });
const feats = [
  ["任务完成叫醒", "长命令跑完 → 立刻叫你收结果"],
  ["受阻止损叫醒", "报错 / OOM / 卡死 → 第一时间响铃，防止白干"],
  ["AI 语音播报", "node 内置 TTS，让手机开口说话"],
  ["7+ 款内置铃声", "核爆 / 防空警报 / 史诗鼓点 / 鸟鸣"],
];
feats.forEach((f, i) => {
  const y = 2.95 + i * 1.62;
  s.addShape(p.shapes.ROUNDED_RECTANGLE, { x: 0.6, y, w: 6.3, h: 1.38, rectRadius: 0.14, fill: { color: CARD, transparency: 12 }, line: { color: STROKE, width: 1 } });
  s.addShape(p.shapes.OVAL, { x: 0.95, y: y + 0.55, w: 0.26, h: 0.26, fill: { color: i % 2 ? ACCENT : PRIMARY } });
  T(s, f[0], { x: 1.5, y: y + 0.24, w: 5.2, h: 0.5, fontSize: 20, bold: true });
  T(s, f[1], { x: 1.5, y: y + 0.78, w: 5.2, h: 0.42, fontSize: 13.5, color: MUTED });
});
s.addNotes("十二个MCP工具，还能自己合成语音铃声");

// ---- S5 结尾 ----
s = p.addSlide();
s.background = { path: DIR + "bg_cover.png" };
T(s, "GitHub 开源", { x: 0.6, y: 4.5, w: 6.3, h: 1.05, fontSize: 52, bold: true, align: "center" });
T(s, "soul667/phone-alarm", { x: 0.6, y: 5.7, w: 6.3, h: 0.6, fontSize: 26, color: ACCENT, align: "center" });
T(s, "零依赖 · 3MB · 本 PPT 可改文案后一键重渲", { x: 0.6, y: 6.45, w: 6.3, h: 0.5, fontSize: 15, color: MUTED, align: "center" });
s.addNotes("开源免费，GitHub搜soul667 phone alarm");

p.writeFile({ fileName: DIR + "phone_alarm_xhs.pptx" }).then(() => console.log("PPTX-OK"));
