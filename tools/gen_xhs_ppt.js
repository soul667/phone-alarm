// 小红书宣传 PPT（竖版 9:16）：极光深色风 + Figma 设计图，供转视频与用户自行改文案。
// 运行：node tools/gen_xhs_ppt.js  （pptxgenjs 全局安装）
const pptxgen = require("pptxgenjs");

const W = 7.5, H = 13.33;                       // 9:16 竖版（英寸）
const BG = "0A0C14", PRIMARY = "7C6CFF", ACCENT = "5AD8E6";
const TEXT = "F2F4FF", MUTED = "8A92B2", CARD = "161927", STROKE = "1F2438";
const FONT = "Microsoft YaHei";
const IMG = __dirname + "/../docs/img/";

let p = new pptxgen();
p.defineLayout({ name: "V916", width: W, height: H });
p.layout = "V916";
p.author = "phone-alarm";
p.title = "phone-alarm 小红书宣传";

// 极光背景：深底 + 双色柔光椭圆（叠透明度模拟 blur）
function aurora(s) {
  s.background = { color: BG };
  s.addShape(p.shapes.OVAL, { x: -2.2, y: -1.8, w: 6.5, h: 6.5, fill: { color: PRIMARY, transparency: 72 } });
  s.addShape(p.shapes.OVAL, { x: 3.6, y: 5.2, w: 6.0, h: 6.0, fill: { color: ACCENT, transparency: 86 } });
  s.addShape(p.shapes.OVAL, { x: -1.8, y: 8.8, w: 6.2, h: 6.2, fill: { color: "4A3ACF", transparency: 78 } });
}
const T = (s, text, o) => s.addText(text, { fontFace: FONT, color: TEXT, margin: 0, ...o });
const chip = (s, text, x, y, w) => {
  s.addShape(p.shapes.ROUNDED_RECTANGLE, { x, y, w, h: 0.56, rectRadius: 0.28, fill: { color: CARD }, line: { color: STROKE, width: 1 } });
  T(s, text, { x, y, w, h: 0.56, fontSize: 13, align: "center", valign: "middle" });
};

// ---- S1 封面 ----
let s = p.addSlide(); aurora(s);
T(s, "让 AI 接管", { x: 0.6, y: 3.9, w: 6.3, h: 1.15, fontSize: 54, bold: true });
T(s, "你的手机闹钟", { x: 0.6, y: 5.0, w: 6.3, h: 1.15, fontSize: 54, bold: true });
T(s, "开源 · MCP · AI 语音叫早", { x: 0.6, y: 6.35, w: 6.3, h: 0.5, fontSize: 18, color: MUTED });
s.addNotes("让AI接管你的手机闹钟！");

// ---- S2 主界面（Figma 设计图） ----
s = p.addSlide(); aurora(s);
T(s, "极光深色 UI", { x: 0.6, y: 0.7, w: 6.3, h: 0.9, fontSize: 40, bold: true });
T(s, "一句话设好闹钟 · 单行卡片 · 状态一眼清", { x: 0.6, y: 1.65, w: 6.3, h: 0.45, fontSize: 15, color: MUTED });
s.addImage({ path: IMG + "figma_main.png", x: 1.95, y: 2.5, w: 3.6, h: 7.8, shadow: { type: "outer", color: "000000", blur: 24, offset: 6, angle: 90, opacity: 0.5 } });
chip(s, "深色自适应 · token 掩码显示", 1.55, 10.9, 4.4);
s.addNotes("极光深色界面，一句话设好闹钟");

// ---- S3 响铃界面 ----
s = p.addSlide(); aurora(s);
T(s, "AI 喊你起床", { x: 0.6, y: 0.7, w: 6.3, h: 0.9, fontSize: 40, bold: true });
T(s, "语音合成 · 大字消息 · 锁屏可响", { x: 0.6, y: 1.65, w: 6.3, h: 0.45, fontSize: 15, color: MUTED });
s.addImage({ path: IMG + "figma_ring.png", x: 1.95, y: 2.5, w: 3.6, h: 7.8, shadow: { type: "outer", color: "000000", blur: 24, offset: 6, angle: 90, opacity: 0.5 } });
chip(s, "AI 合成语音 · 想听什么都行", 1.55, 10.9, 4.4);
s.addNotes("到点全屏响铃，AI语音喊你起床");

// ---- S4 能力面 ----
s = p.addSlide(); aurora(s);
T(s, "12 个 MCP 工具", { x: 0.6, y: 1.1, w: 6.3, h: 0.9, fontSize: 40, bold: true });
const feats = [
  ["⏰", "闹钟全管理", "建/删/改/开关 · 重复与一次性"],
  ["🗣️", "AI 语音播报", "内置 TTS，让手机开口说话"],
  ["💥", "7+ 款铃声", "核爆/防空警报/史诗鼓点…"],
  ["🔎", "找手机模式", "立即响铃，最响 5 分钟"],
];
feats.forEach((f, i) => {
  const y = 2.6 + i * 1.55;
  s.addShape(p.shapes.ROUNDED_RECTANGLE, { x: 0.6, y, w: 6.3, h: 1.3, rectRadius: 0.14, fill: { color: CARD }, line: { color: STROKE, width: 1 } });
  T(s, f[0], { x: 0.85, y: y + 0.28, w: 0.8, h: 0.7, fontSize: 26 });
  T(s, f[1], { x: 1.8, y: y + 0.22, w: 4.9, h: 0.5, fontSize: 19, bold: true });
  T(s, f[2], { x: 1.8, y: y + 0.74, w: 4.9, h: 0.4, fontSize: 13, color: MUTED });
});
s.addNotes("十二个MCP工具，还能自己合成语音铃声");

// ---- S5 结尾 ----
s = p.addSlide(); aurora(s);
T(s, "GitHub 开源", { x: 0.6, y: 4.6, w: 6.3, h: 1.0, fontSize: 50, bold: true, align: "center" });
T(s, "soul667/phone-alarm", { x: 0.6, y: 5.75, w: 6.3, h: 0.6, fontSize: 26, color: ACCENT, align: "center" });
T(s, "零依赖 · 3MB · PPT 源文件可改文案", { x: 0.6, y: 6.5, w: 6.3, h: 0.5, fontSize: 16, color: MUTED, align: "center" });
s.addNotes("开源免费，GitHub搜soul667 phone alarm");

p.writeFile({ fileName: __dirname + "/../docs/video/phone_alarm_xhs.pptx" }).then(() => console.log("PPTX-OK"));
