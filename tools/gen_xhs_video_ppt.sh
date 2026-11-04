#!/usr/bin/env bash
# PPT → 视频：soffice 转图 + edge-tts 逐页配音（封面页静场）+ ffmpeg 合成（轻柔 BGM 压底）。
# 产物：docs/video/xhs_ppt.mp4（PPT 源文件 docs/video/phone_alarm_xhs.pptx 可自行改文案重渲）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/docs/video"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "[1/5] PPTX → PDF → PNG"
soffice --headless --convert-to pdf --outdir "$TMP" "$V/phone_alarm_xhs.pptx" >/dev/null 2>&1
pdftoppm -png -r 150 "$TMP/phone_alarm_xhs.pdf" "$TMP/slide"

echo "[2/5] TTS 配音（第 2-5 页；封面静场）"
python3 - "$TMP" <<'EOPY'
import asyncio, sys
import edge_tts
out = sys.argv[1]
lines = {
    2: "挂上长任务，放心去休息",
    3: "跑完叫你，出错了更第一时间叫你，防止白干",
    4: "十二个MCP工具，还能自己合成语音铃声",
    5: "开源免费，GitHub搜 soul667 phone alarm",
}
async def main():
    for i, ln in lines.items():
        c = edge_tts.Communicate(ln, "zh-CN-XiaoxiaoNeural")
        await c.save(f"{out}/v{i}.mp3")
        print(f"v{i}.mp3 ok")
asyncio.run(main())
EOPY

echo "[3/5] 计算每页时长（封面静场 2.4s，其余=配音+0.7s）"
read -r INTRO D2 D3 D4 D5 TOTAL MS2 MS3 MS4 MS5 <<<"$(python3 - "$TMP" <<'EOP'
import subprocess, sys
def dur(p):
    return float(subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]
    ).decode().strip())
intro = 2.4
ds = {i: dur(f"{sys.argv[1]}/v{i}.mp3") + 0.7 for i in (2, 3, 4, 5)}
t, offs = intro, {}
for i in (2, 3, 4, 5):
    offs[i] = t + 0.35
    t += ds[i]
print(f"{intro:.3f}", *[f"{ds[i]:.3f}" for i in (2,3,4,5)], f"{t:.3f}", *[int(offs[i]*1000) for i in (2,3,4,5)])
EOP
)"
DURS=("$INTRO" "$D2" "$D3" "$D4" "$D5")

echo "[4/5] 逐页视频段（静态）"
for i in 1 2 3 4 5; do
  D=${DURS[$((i-1))]}
  ffmpeg -y -v error -loop 1 -framerate 30 -i "$TMP/slide-$i.png" \
    -vf "scale=1080:1920" \
    -t "$D" -r 30 -pix_fmt yuv420p "$TMP/seg$i.mp4"
done
printf "file '%s.mp4'\n" "$TMP/seg1" "$TMP/seg2" "$TMP/seg3" "$TMP/seg4" "$TMP/seg5" > "$TMP/list.txt"
ffmpeg -y -v error -f concat -safe 0 -i "$TMP/list.txt" -c copy "$TMP/video_nosound.mp4"

echo "[5/5] 混音（第 2-5 页配音 + 轻柔 BGM）"
# 语音输入 = $1..$4（对应 slide 2..5），BGM = $5
VDELAY="[1:a]adelay=${MS2}:all=1[a0];[2:a]adelay=${MS3}:all=1[a1];[3:a]adelay=${MS4}:all=1[a2];[4:a]adelay=${MS5}:all=1[a3];"
ffmpeg -y -v error -i "$TMP/video_nosound.mp4" \
  -i "$TMP/v2.mp3" -i "$TMP/v3.mp3" -i "$TMP/v4.mp3" -i "$TMP/v5.mp3" \
  -i "$ROOT/docs/video/bgm_soft.wav" \
  -filter_complex "${VDELAY}[5:a]atrim=0:${TOTAL},asetpts=PTS-STARTPTS,volume=0.5,afade=t=out:st=$(python3 -c "print($TOTAL-1.6)"):d=1.6[bg];[a0][a1][a2][a3][bg]amix=inputs=5:duration=longest:normalize=0,volume=1.6,alimiter=limit=0.95[aout]" \
  -map 0:v -map "[aout]" -t "$TOTAL" -c:v copy -c:a aac -b:a 128k -movflags +faststart "$V/xhs_ppt.mp4"

ffmpeg -y -v error -ss 0.2 -i "$V/xhs_ppt.mp4" -frames:v 1 "$V/xhs_ppt_cover.png"
echo "产物: $V/xhs_ppt.mp4 ($(du -h "$V/xhs_ppt.mp4" | cut -f1)) + phone_alarm_xhs.pptx"
