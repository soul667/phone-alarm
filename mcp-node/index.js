#!/usr/bin/env node
/**
 * phone-alarm-mcp —— 手机闹钟 MCP server（Node 版）
 *
 * 链路：agent ──stdio/MCP──> 本进程 ──HTTP──> 手机 App（com.axgu.phonealarm 内嵌 API :8787）
 *
 * TTS 语音播报（在工具 docstring 里也写明了）：
 *   - phone_say(text, engine="edge")：node 侧用内置轻量 TTS（msedge-tts，微软免费接口，
 *     中文音色，无需下载模型）合成 mp3 → POST /api/audio?play=1 → 手机立即播放；
 *     engine="phone" 走手机系统端侧 TextToSpeech（需手机装有可用 TTS 引擎，部分国产 ROM 没有）。
 *   - phone_tts(text, voice?, save_as?)：合成并上传，save_as 可存为自定义铃声
 *     （sound="custom:名字" 可用于任何闹钟）。
 *   - 离线场景备选：PC 上用 KittenTTS（~25MB 本地推理）等生成音频后 curl 上传 /api/audio。
 *
 * 配置（优先级：环境变量 > 同目录 config.json）：
 *   PHONE_ALARM_HOST / PHONE_ALARM_PORT / PHONE_ALARM_TOKEN
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import http from "node:http";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { MsEdgeTTS, OUTPUT_FORMAT } from "msedge-tts";

const HERE = dirname(fileURLToPath(import.meta.url));
const DAY_NAMES = { 1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六", 7: "周日" };
const SOUND_NAMES = {
  system: "系统闹钟铃声", nuke: "核爆", siren: "防空警报", klaxon: "舰船警报",
  pulse: "紧急脉冲", birds: "晨间鸟鸣", epic: "史诗鼓点", rock: "运动摇滚",
};

function cfg() {
  let f = {};
  try { f = JSON.parse(readFileSync(join(HERE, "config.json"), "utf-8")); } catch { }
  return {
    host: process.env.PHONE_ALARM_HOST || f.host || "",
    port: Number(process.env.PHONE_ALARM_PORT || f.port || 8787),
    token: process.env.PHONE_ALARM_TOKEN || f.token || "",
  };
}

function req(method, path, body = null, raw = null, timeoutMs = 8000) {
  const c = cfg();
  if (!c.host) {
    return Promise.reject(new Error(
      `手机 IP 未配置。打开手机「手机闹钟」App，把主页显示的 host/token 填入 ${join(HERE, "config.json")}，` +
      `或设 PHONE_ALARM_HOST/PORT/TOKEN 环境变量。`));
  }
  return new Promise((resolve, reject) => {
    const payload = raw ?? (body ? JSON.stringify(body) : null);
    const r = http.request({
      hostname: c.host, port: c.port, path, method,
      headers: {
        Authorization: `Bearer ${c.token}`,
        ...(payload ? {
          "Content-Type": raw ? "application/octet-stream" : "application/json",
          // 手机端 HttpServer 按 Content-Length 读 body，必须显式声明避免 chunked
          "Content-Length": Buffer.byteLength(payload),
        } : {}),
      },
      timeout: timeoutMs,
    }, (res) => {
      let data = "";
      res.on("data", (ch) => (data += ch));
      res.on("end", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try { resolve(JSON.parse(data)); } catch { resolve({ raw: data }); }
        } else {
          reject(new Error(`手机返回 ${res.statusCode}: ${data.slice(0, 300)}`));
        }
      });
    });
    r.on("timeout", () => { r.destroy(); reject(new Error(
      `连不上手机 ${c.host}:${c.port}。排查：①手机与电脑同一 WiFi；②App 打开过一次（服务常驻）；` +
      `③IP 变了没有（App 主页显示当前 IP）；④防火墙`)); });
    r.on("error", (e) => reject(new Error(
      `连不上手机 ${c.host}:${c.port}（${e.message}）。排查：①同一 WiFi ②App 打开过 ③IP 变更 ④防火墙`)));
    if (payload) r.write(payload);
    r.end();
  });
}

function fmtAlarm(a) {
  const days = a.days || [];
  const repeat = days.length === 0 ? "一次性"
    : days.length === 7 ? "每天"
      : "周" + days.slice().sort().map((d) => DAY_NAMES[d]?.[1] ?? "?").join("");
  return {
    id: a.id,
    time: `${String(a.hour).padStart(2, "0")}:${String(a.minute).padStart(2, "0")}`,
    label: a.label || "",
    repeat,
    sound: a.sound,
    sound_name: SOUND_NAMES[a.sound] ?? (a.sound?.startsWith("custom:") ? "🎵 " + a.sound.slice(7) : "系统闹钟铃声"),
    tts_speak: a.tts !== false,
    enabled: a.enabled,
    next_ring: a.next_ring > 0
      ? new Date(a.next_ring).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", weekday: "short" })
      : "已关闭",
  };
}

/** node 侧轻量 TTS：msedge-tts(2.x) 合成 mp3 Buffer。 */
async function edgeTts(text, voice = "zh-CN-YunxiNeural") {
  const tts = new MsEdgeTTS();
  await tts.setMetadata(voice, OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3);
  const { audioStream } = tts.toStream(text);
  const chunks = [];
  for await (const ch of audioStream) chunks.push(Buffer.isBuffer(ch) ? ch : Buffer.from(ch));
  return Buffer.concat(chunks);
}

const mcp = new McpServer({ name: "phone-alarm", version: "1.1.0" });

function tool(name, desc, schema, fn) {
  mcp.tool(name, desc, schema, async (args) => {
    try {
      const out = await fn(args);
      return { content: [{ type: "text", text: typeof out === "string" ? out : JSON.stringify(out, null, 1) }] };
    } catch (e) {
      return { content: [{ type: "text", text: `错误：${e.message}` }], isError: true };
    }
  });
}

tool("phone_ping", "测试与手机闹钟 App 的连通性（免 token 握手）。返回设备名、闹钟数、手机当前时间。",
  {}, async () => {
    const r = await req("GET", "/api/ping");
    r.phone_time = new Date(r.now).toLocaleString("zh-CN");
    return r;
  });

tool("phone_set_alarm",
  "在手机上新建闹钟。hour 0-23，minute 0-59，label 是响铃界面显示的大字消息（同时是端侧播报文本）。" +
  "days: 要重复的星期列表 1=周一…7=周日，如 [1,2,3,4,5]=工作日；不传=一次性。" +
  "sound: 铃声 key，可选 system/nuke(核爆)/siren(防空警报)/klaxon(舰船警报)/pulse(紧急脉冲)/birds(晨间鸟鸣)/" +
  "epic(史诗鼓点)/rock(运动摇滚)/custom:名字(AI上传的自定义语音)；不传用手机 App 里设置的默认铃声。" +
  "tts: 响铃时是否同时语音朗读 label（默认 true；手机需有可用系统 TTS 引擎）。",
  {
    hour: z.number().int().min(0).max(23),
    minute: z.number().int().min(0).max(59),
    label: z.string().default(""),
    days: z.array(z.number().int().min(1).max(7)).optional(),
    sound: z.string().optional(),
    tts: z.boolean().optional(),
  }, async ({ hour, minute, label, days, sound, tts }) => {
    const body = { hour, minute, label: label || "" };
    if (days) body.days = [...new Set(days)].sort();
    if (sound) body.sound = sound;
    if (tts !== undefined) body.tts = tts;
    const r = await req("POST", "/api/alarms", body);
    return { ok: true, alarm: fmtAlarm(r) };
  });

tool("phone_list_alarms", "列出手机上全部闹钟（时间/标签/重复/铃声/是否播报/开关/下次响铃时间）。",
  {}, async () => {
    const r = await req("GET", "/api/alarms");
    return { ok: true, count: r.alarms.length, alarms: r.alarms.map(fmtAlarm) };
  });

tool("phone_update_alarm", "按 id 修改手机上的闹钟（只传要改的字段）。days 语义同 phone_set_alarm，days=[] 表示改成一次性。",
  {
    alarm_id: z.number(),
    hour: z.number().int().min(0).max(23).optional(),
    minute: z.number().int().min(0).max(59).optional(),
    label: z.string().optional(),
    days: z.array(z.number().int().min(1).max(7)).optional(),
    enabled: z.boolean().optional(),
    sound: z.string().optional(),
    tts: z.boolean().optional(),
  }, async ({ alarm_id, ...patch }) => {
    if (Object.keys(patch).length === 0) throw new Error("没有要修改的字段");
    const r = await req("POST", `/api/alarms/${alarm_id}`, patch);
    return { ok: true, alarm: fmtAlarm(r) };
  });

tool("phone_delete_alarm", "按 id 删除手机上的闹钟。",
  { alarm_id: z.number() }, async ({ alarm_id }) => req("DELETE", `/api/alarms/${alarm_id}`));

tool("phone_toggle_alarm", "按 id 开/关切换手机上的闹钟。",
  { alarm_id: z.number() }, async ({ alarm_id }) => {
    const r = await req("POST", `/api/alarms/${alarm_id}/toggle`);
    return { ok: true, alarm: fmtAlarm(r) };
  });

tool("phone_ring_now",
  "让手机立即响铃（找手机/立即提醒）。message 显示在响铃界面大字区。sound 同 phone_set_alarm，默认 nuke 核爆。" +
  "speak: 是否同时 TTS 朗读 message（默认 true）。",
  {
    message: z.string().default(""),
    sound: z.string().default("nuke"),
    speak: z.boolean().optional(),
  }, async ({ message, sound, speak }) => {
    const body = { message: message || "", sound };
    if (speak !== undefined) body.speak = speak;
    const r = await req("POST", "/api/ring", body);
    return { ...r, hint: "手机正在响铃；结束后用 phone_stop_ring 停止" };
  });

tool("phone_stop_ring", "停止手机上正在响的铃声。", {},
  async () => req("POST", "/api/stop"));

tool("phone_set_default_sound",
  "设置手机 App 的默认铃声（App 内新建、phone_set_alarm 不传 sound 时都用它）。sound 可选值同 phone_set_alarm。",
  { sound: z.string() }, async ({ sound }) => {
    const r = await req("POST", "/api/settings", { default_sound: sound });
    return { ok: r.ok, default_sound: r.default_sound, name: SOUND_NAMES[r.default_sound] ?? r.default_sound };
  });

tool("phone_say",
  "让手机「说话」。两条 TTS 路径：" +
  "① engine='edge'（默认，推荐）：node 侧内置轻量 TTS（msedge-tts 微软神经音色，中文自然，无需下载模型）" +
  "合成语音 → 推送到手机立即播放，走闹钟音量；" +
  "② engine='phone'：手机系统端侧 TextToSpeech 离线播报（OnePlus/部分国产 ROM 无可用引擎时会失败，" +
  "失败就换 edge）。voice 可换音色，如 zh-CN-XiaoxiaoNeural / zh-CN-YunyangNeural / zh-CN-YunxiNeural。",
  {
    text: z.string().min(1).max(500),
    engine: z.enum(["edge", "phone"]).default("edge"),
    voice: z.string().optional(),
  }, async ({ text, engine, voice }) => {
    if (engine === "phone") {
      const r = await req("POST", "/api/say", { text });
      return { ...r, engine };
    }
    const mp3 = await edgeTts(text, voice || "zh-CN-YunxiNeural");
    const r = await req("POST", "/api/audio?play=1&t=" + Date.now(), null, mp3);
    return { ok: r.ok, played: r.played, bytes: r.bytes, engine: "edge(云合成,手机本地播放)" };
  });

tool("phone_tts",
  "AI 自己合成一段语音存为手机的自定义铃声（之后任何闹钟可用 sound='custom:名字' 引用）。" +
  "合成在 node 侧完成（msedge-tts 轻量 TTS）。save_as 只能含中英文/数字/_-，1-32 字。",
  {
    text: z.string().min(1).max(500),
    save_as: z.string().min(1).max(32),
    voice: z.string().optional(),
    play: z.boolean().default(false),
  }, async ({ text, save_as, voice, play }) => {
    const mp3 = await edgeTts(text, voice || "zh-CN-YunxiNeural");
    const r = await req("POST",
      `/api/audio?name=${encodeURIComponent(save_as)}&save=1${play ? "&play=1" : ""}`,
      null, mp3);
    return { ...r, sound_key: r.saved, hint: `已存为铃声，建闹钟时传 sound="${r.saved}"` };
  });

tool("phone_status", "查询手机 App 运行状态：API 在线、响铃中、TTS 引擎就绪、下次响铃时间。",
  {}, async () => {
    const r = await req("GET", "/api/status");
    if (r.next_ring > 0) {
      r.next_ring_human = new Date(r.next_ring).toLocaleString("zh-CN",
        { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
    }
    return r;
  });

const transport = new StdioServerTransport();
await mcp.connect(transport);
console.error("[phone-alarm-mcp] stdio server started");
