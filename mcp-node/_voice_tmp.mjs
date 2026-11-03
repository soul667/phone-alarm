import path from "node:path";
import fs from "node:fs";
import { MsEdgeTTS, OUTPUT_FORMAT } from "msedge-tts";
const outDir = process.argv[2];
const lines = [
  "让AI接管你的手机闹钟！",
  "极光深色界面，一句话设好闹钟",
  "到点全屏响铃，AI语音喊你起床",
  "十二个MCP工具，还能自己合成语音铃声",
  "开源免费，GitHub搜 soul667 phone alarm",
];
for (let i = 0; i < lines.length; i++) {
  const tts = new MsEdgeTTS();
  await tts.setMetadata("zh-CN-XiaoxiaoNeural", OUTPUT_FORMAT.AUDIO_24KHZ_48KBITRATE_MONO_MP3);
  const { audioStream } = tts.toStream(lines[i]);
  const chunks = [];
  for await (const ch of audioStream) chunks.push(Buffer.isBuffer(ch) ? ch : Buffer.from(ch));
  fs.writeFileSync(path.join(outDir, `v${i + 1}.mp3`), Buffer.concat(chunks));
  console.log(`v${i + 1}.mp3 ok`);
}
