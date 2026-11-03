#!/usr/bin/env bash
# PPT → 视频：soffice 转图 + msedge-tts 逐页配音 + ffmpeg 合成（BGM 压底）。
# 产物：docs/video/xhs_ppt.mp4（PPT 源文件 docs/video/phone_alarm_xhs.pptx 可自行改文案重渲）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/docs/video"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "[1/5] PPTX → PDF → PNG"
soffice --headless --convert-to pdf --outdir "$TMP" "$V/phone_alarm_xhs.pptx" >/dev/null 2>&1
pdftoppm -png -r 150 "$TMP/phone_alarm_xhs.pdf" "$TMP/slide"
ls "$TMP"/slide-*.png

echo "[2/5] TTS 配音（msedge-tts，node 侧合成）"
NODE_DIR="$ROOT/mcp-node"
python3 - "$TMP" <<'EOPY'
import asyncio, sys
import edge_tts
out = sys.argv[1]
lines = [
    "让AI接管你的手机闹钟！",
    "极光深色界面，一句话设好闹钟",
    "到点全屏响铃，AI语音喊你起床",
    "十二个MCP工具，还能自己合成语音铃声",
    "开源免费，GitHub搜 soul667 phone alarm",
]
async def main():
    for i, ln in enumerate(lines):
        c = edge_tts.Communicate(ln, "zh-CN-XiaoxiaoNeural")
        await c.save(f"{out}/v{i+1}.mp3")
        print(f"v{i+1}.mp3 ok")
asyncio.run(main())
EOPY

echo "[3/5] 计算每页时长"
DURS=(); OFFSETS=(0); ACC=0
for i in 1 2 3 4 5; do
  D=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$TMP/v$i.mp3")
  SEG=$(python3 -c "print(f'{$D + 0.7:.3f}')")
  DURS+=("$SEG")
  [ $i -gt 1 ] && OFFSETS+=($(python3 -c "print(f'{$ACC + 0.35:.3f}')"))
  ACC=$(python3 -c "print($ACC + $SEG)")
done
TOTAL=$(python3 -c "print(f'{$ACC:.3f}')")
echo "durations: ${DURS[*]} total=$TOTAL"

echo "[4/5] 逐页视频段（轻推镜）"
for i in 1 2 3 4 5; do
  D=${DURS[$((i-1))]}
  ffmpeg -y -v error -loop 1 -framerate 30 -i "$TMP/slide-$i.png" \
    -vf "scale=1080:1920" \
    -t "$D" -r 30 -pix_fmt yuv420p "$TMP/seg$i.mp4"
done
printf "file '%s.mp4'\n" "$TMP/seg1" "$TMP/seg2" "$TMP/seg3" "$TMP/seg4" "$TMP/seg5" > "$TMP/list.txt"
ffmpeg -y -v error -f concat -safe 0 -i "$TMP/list.txt" -c copy "$TMP/video_nosound.mp4"

echo "[5/5] 混音（配音 + BGM 0.22）"
VDELAY=""
for i in 1 2 3 4 5; do VDELAY+="[$i:a]adelay=${OFFSETS[$((i-1))]}s:all=1[a$i];"; done
ffmpeg -y -v error -i "$TMP/video_nosound.mp4" \
  -i "$TMP/v1.mp3" -i "$TMP/v2.mp3" -i "$TMP/v3.mp3" -i "$TMP/v4.mp3" -i "$TMP/v5.mp3" \
  -i "$ROOT/docs/video/bgm_soft.wav" \
  -filter_complex "${VDELAY}[6:a]atrim=0:${TOTAL},asetpts=PTS-STARTPTS,volume=0.5,afade=t=out:st=$(python3 -c "print($TOTAL-1.6)"):d=1.6[bg];[a1][a2][a3][a4][a5][bg]amix=inputs=6:duration=longest:normalize=0,volume=1.6,alimiter=limit=0.95[aout]" \
  -map 0:v -map "[aout]" -t "$TOTAL" -c:v copy -c:a aac -b:a 128k -movflags +faststart "$V/xhs_ppt.mp4"

# 封面
ffmpeg -y -v error -ss 0.2 -i "$V/xhs_ppt.mp4" -frames:v 1 "$V/xhs_ppt_cover.png"
echo "产物: $V/xhs_ppt.mp4 ($(du -h "$V/xhs_ppt.mp4" | cut -f1)) + phone_alarm_xhs.pptx"
