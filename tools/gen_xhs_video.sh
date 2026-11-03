#!/usr/bin/env bash
# 生成小红书宣传视频（1080x1920 竖屏 18.5s）：单条 ffmpeg 渲染 5 镜头 + 程序合成 BGM。
# 依赖：ffmpeg 4.4+、Noto Sans CJK；素材：docs/img/*.png + app/res/raw/alarm_epic.mp3
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG="$ROOT/docs/img"
OUT="$ROOT/docs/video"
FONT="/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
FONT_MED="/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
mkdir -p "$OUT"

ffmpeg -y -v error \
  -i "$IMG/main_light.png" \
  -i "$IMG/ring.png" \
  -i "$IMG/main_dark.png" \
  -f lavfi -i "gradients=s=1080x1920:c0=0x6E62E8:c1=0x9B5AE0:c2=0x4A3ACF:d=3.2" \
  -f lavfi -i "color=c=0x171233:s=1080x1920:d=4" \
  -f lavfi -i "color=c=0x171233:s=1080x1920:d=4" \
  -f lavfi -i "color=c=0x171233:s=1080x1920:d=3.8" \
  -f lavfi -i "gradients=s=1080x1920:c0=0x4A3ACF:c1=0x6E62E8:d=3.5" \
  -i "$ROOT/app/res/raw/alarm_epic.mp3" \
  -filter_complex "\
[0:v]scale=-1:1440,zoompan=z='min(zoom+0.0012,1.12)':d=120:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1440:fps=30,format=yuv420p[shot1];\
[4:v][shot1]overlay=(W-w)/2:(H-h)/2+100,\
drawtext=fontfile=$FONT:fontcolor=white:fontsize=76:text='玻璃拟态 App':x=(w-tw)/2:y=170,\
drawtext=fontfile=$FONT_MED:fontcolor=0xDDFFFFFF:fontsize=46:text='深浅主题 · 磨砂玻璃 · 单行卡片':x=(w-tw)/2:y=1808[v2];\
[1:v]scale=-1:1440,zoompan=z='max(1.12-0.0012*on,1.0)':d=120:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1440:fps=30,format=yuv420p[shot2];\
[5:v][shot2]overlay=(W-w)/2:(H-h)/2+100,\
drawtext=fontfile=$FONT:fontcolor=white:fontsize=76:text='AI 喊你起床':x=(w-tw)/2:y=170,\
drawtext=fontfile=$FONT_MED:fontcolor=0xDDFFFFFF:fontsize=46:text='语音合成 · 大字消息 · 脉冲动画':x=(w-tw)/2:y=1808[v3];\
[2:v]scale=-1:1440,zoompan=z='min(zoom+0.0012,1.12)':d=114:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1440:fps=30,format=yuv420p[shot3];\
[6:v][shot3]overlay=(W-w)/2:(H-h)/2+100,\
drawtext=fontfile=$FONT:fontcolor=white:fontsize=76:text='对话即设置':x=(w-tw)/2:y=170,\
drawtext=fontfile=$FONT_MED:fontcolor=0xDDFFFFFF:fontsize=46:text='「明早七点核爆叫我」→ 真的会响':x=(w-tw)/2:y=1808[v4];\
[3:v]drawtext=fontfile=$FONT:fontcolor=white:fontsize=110:text='让 AI 接管':x=(w-tw)/2:y=560,\
drawtext=fontfile=$FONT:fontcolor=white:fontsize=110:text='你的手机闹钟':x=(w-tw)/2:y=700,\
drawtext=fontfile=$FONT_MED:fontcolor=0xCCFFFFFF:fontsize=44:text='开源 · MCP · AI 语音叫早':x=(w-tw)/2:y=880[v1];\
[7:v]drawtext=fontfile=$FONT:fontcolor=white:fontsize=96:text='GitHub 开源':x=(w-tw)/2:y=780,\
drawtext=fontfile=$FONT_MED:fontcolor=white:fontsize=56:text='soul667/phone-alarm':x=(w-tw)/2:y=930,\
drawtext=fontfile=$FONT_MED:fontcolor=0xCCFFFFFF:fontsize=42:text='零依赖 · 3MB · 12 个 MCP 工具':x=(w-tw)/2:y=1060[v5];\
[v1][v2][v3][v4][v5]concat=n=5:v=1:a=0[vout];\
[8:a]atrim=0:18.5,asetpts=PTS-STARTPTS,volume=0.9,afade=t=out:st=17:d=1.5[aout]" \
  -map "[vout]" -map "[aout]" -t 18.5 \
  -c:v libx264 -pix_fmt yuv420p -crf 21 -preset medium -r 30 \
  -c:a aac -b:a 128k -movflags +faststart "$OUT/xhs_phone_alarm.mp4"

# 封面图
ffmpeg -y -v error -ss 2.5 -i "$OUT/xhs_phone_alarm.mp4" -frames:v 1 "$OUT/xhs_cover.png"
echo "产物: $OUT"
ls -la "$OUT"
