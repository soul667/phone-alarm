# 📱 phone-alarm —— agent 可远程控制的手机闹钟

自研 Android 闹钟 App + PC 侧 MCP server：让 AI agent（ZCode / Claude 等）通过 MCP 直接**创建、管理、触发**你手机上的真实闹钟——到点全屏响铃、锁屏可响、内置核爆级铃声。

```text
┌──────────┐  stdio / MCP  ┌──────────────────┐   HTTP :8787    ┌──────────────────────┐
│ AI agent │ ────────────► │ phone_alarm_mcp  │ ──────────────► │ Android App（常驻     │
│ (ZCode…) │               │ (FastMCP, PC 侧) │  Bearer token   │ 前台服务 + 内嵌 API)  │
└──────────┘               └──────────────────┘                 └──────────┬───────────┘
                                                                           │ AlarmManager.setAlarmClock
                                                                           ▼
                                                             ⏰ 精确闹钟 → 全屏响铃（锁屏可响）
```

> 全栈零第三方依赖：App 用 Java 直写（无 Gradle、无 androidx），PC 侧仅用 `mcp` 官方库。APK 仅 ~950KB。

## ✨ 功能

| | 功能 | 说明 |
|---|---|---|
| ⏰ | 精确闹钟 | `AlarmManager.setAlarmClock`，Doze 下也准点；重复闹钟自动排下一次，一次性响后自动关闭 |
| 🔊 | 内置铃声 7 款 | 程序合成 5 款（💥核爆/🚨防空/🚢舰船/⚡脉冲/🐦鸟鸣）+ 用户自带 2 款（🥁史诗鼓点/🎸运动摇滚），按闹钟独立选择，支持设置全局默认 |
| 💬 | 响铃大字消息 | 响铃全屏显示 agent 下发的文字 + 日期 + 双圈脉冲动画，锁屏亮屏可见 |
| 🌓 | 玻璃拟态 UI | 磨砂渐变网格背景（API 31+ 真模糊）+ 半透明玻璃卡片，单行紧凑布局，深浅双主题，铃声试听 |
| 🤖 | MCP 12 工具 | 闹钟管理 + **AI 语音播报/语音铃声**，agent 开箱即用（Node 版内置轻量 TTS） |
| 🩹 | 崩溃自报 | 任何未捕获异常存栈，下次启动直接展示 + 一键复制，永不"盲闪退" |
| 🔁 | 自愈能力 | 开机/升级自恢复调度与 API；电池优化白名单指引 |
| 🗣️ | AI 语音 | node 内置 TTS 合成中文语音推送到手机播放；可存为自定义铃声给任何闹钟用 |
| 🧪 | 真机验证 | OnePlus PKR110 / Android 16 全链路实测（创建→调度→响铃→停止→清理） |

## 📸 界面

| 主界面（Aurora Refined） | 响铃界面（大字消息 + 光圈） |
|---|---|
| ![主界面](docs/img/main_dark.png) | ![响铃](docs/img/shot_ring.png) |

## 🚀 快速开始

### 1. 手机安装

```bash
git clone https://github.com/soul667/phone-alarm.git && cd phone-alarm
bash build.sh                    # 生成 phone-alarm.apk（需 build-tools 34 + platform-34 + JDK 17）
python3 -m http.server 8899      # 手机浏览器打开 http://<PC局域网IP>:8899/phone-alarm.apk
```

安装后打开 App：授权「闹钟和提醒」（Android 12+）与通知权限 → 点「测试响铃」验收。

### 2. 配置 MCP（Node 版，含内置 TTS）

```bash
cd mcp-node && npm install
cp config.json config.json.bak 2>/dev/null; 
# 把 App 主页显示的 IP 与 token 填入 mcp-node/config.json：
# { "host": "192.168.x.x", "port": 8787, "token": "xxxxxxxxxxxxxxxx" }
```

ZCode 配置（`mcp.servers`）：

```json
"phone-alarm": { "type": "stdio", "command": "node", "args": ["/path/to/phone-alarm/mcp-node/index.js"] }
```

> 也有无 TTS 的纯 Python 版：`mcp/phone_alarm_mcp.py`（同样 12 工具，TTS 相关调用会提示改用 Node 版）。

然后对 agent 说：「明早 7 点用核爆铃声叫我起床」或「用你自己的声音录一段叫早语音存为铃声」。

**给别人用**：仓库 `skill/phone-alarm/SKILL.md` 是打包好的 agent skill，拷进对方的 skills 目录即可（含完整上手/配方/排障指南）。

## 🤖 MCP 工具

| 工具 | 说明 |
|---|---|
| `phone_ping` | 连通性测试（免 token，返回设备名/闹钟数） |
| `phone_set_alarm(hour, minute, label, days?, sound?)` | 建闹钟；`days` 1=周一…7=周日（`[1,2,3,4,5]`=工作日，不传=一次性）；不传 `sound` 用 App 默认铃声 |
| `phone_list_alarms` | 列出全部（时间/标签/重复/铃声/开关/下次响铃） |
| `phone_update_alarm(alarm_id, ...)` | 按 id 改任意字段（`days=[]` 表示改一次性） |
| `phone_toggle_alarm(alarm_id)` | 开/关切换 |
| `phone_delete_alarm(alarm_id)` | 删除 |
| `phone_ring_now(message?, sound?="nuke")` | **立即响铃**——核心场景：长任务跑完叫醒用户；也用于找手机 |
| `phone_stop_ring` | 停止响铃 |
| `phone_say(text, engine?="edge")` | 让手机说话：node 内置 TTS 合成→推送播放；`engine="phone"` 走手机系统端侧引擎（部分 ROM 不可用） |
| `phone_tts(text, save_as, voice?, play?)` | AI 合成语音存为自定义铃声（`sound="custom:名字"` 可用于任何闹钟） |
| `phone_set_default_sound(sound)` | 设置 App 默认铃声（App 新建与 `phone_set_alarm` 不传 sound 时的缺省） |
| `phone_status` | 在线状态/是否响铃中/下次响铃 |

**铃声 `sound`**：`system` 系统闹钟 · `nuke` 💥核爆 · `siren` 🚨防空警报 · `klaxon` 🚢舰船警报 · `pulse` ⚡紧急脉冲 · `birds` 🐦晨间鸟鸣 · `epic` 🥁史诗鼓点 · `rock` 🎸运动摇滚

## 🔌 HTTP API（App 内嵌，可 curl）

除 `/api/ping` 外均需 `Authorization: Bearer <token>`：

```text
GET    /api/ping                     免 token 握手
GET    /api/status                   运行状态/响铃状态/下次响铃
GET    /api/sounds                   内置铃声目录
GET    /api/alarms                   列表
POST   /api/alarms                   {"hour":7,"minute":30,"label":"起床","days":[1,2,3,4,5],"sound":"nuke"}
POST   /api/alarms/{id}              部分更新（同上字段任意子集）
POST   /api/alarms/{id}/toggle       开关
DELETE /api/alarms/{id}              删除
POST   /api/ring                     {"message":"文字","sound":"siren"} 立即响铃
GET    /api/settings                 查询默认铃声
POST   /api/settings                 {"default_sound":"epic"} 设置默认铃声
POST   /api/say                      {"text":"..."} 端侧 TTS 播报（需手机有可用 TTS 引擎）
POST   /api/audio?name=x&save=1&play=1   上传音频原始字节：存为 custom:x 铃声 / 立即播放
POST   /api/stop                     停止响铃
```

## 🥁 内置铃声是怎么来的

全部由 [`tools/gen_sounds.py`](tools/gen_sounds.py) 用 Python 标准库**程序合成**（16bit/22050Hz WAV，零素材、零版权）：

- `nuke`：起爆瞬态 + 55→28Hz 亚低频扫频轰鸣 + 棕噪声余震
- `siren`：500–750Hz 正弦扫频经典防空警笛
- `klaxon`：310/240Hz 交替锯齿波 ah-ooh-gah
- `pulse`：1.2kHz 加速蜂鸣串
- `birds`：FM 啁啾组（温和款，怕被核爆吵醒的话）

另外两款来自用户挑选的无版权音频（`epic` 史诗鼓点 92s / `rock` 运动摇滚），ffmpeg 转 96kbps 单声道后打包进 `res/raw/`。

## 🏗️ 目录结构

```
phone-alarm/
├── app/AndroidManifest.xml          # 权限/组件（FGS specialUse 声明在此）
├── app/java/com/axgu/phonealarm/    # MainActivity / ApiService(路由) / HttpServer(内嵌HTTP)
│                                    # AlarmStore(模型+调度) / RingService(响铃) / RingActivity
│                                    # SoundCatalog / AlarmReceiver / BootReceiver / PhoneAlarmApp
├── app/res/                         # 布局/主题(含夜间)/矢量图/5个铃声 WAV
├── mcp/phone_alarm_mcp.py           # PC 侧 MCP server (FastMCP, stdio)
├── mcp/config.json                  # 手机 IP/port/token
├── tools/gen_sounds.py              # 铃声合成器
├── tools/gen_xhs_video.sh           # 宣传视频渲染（ffmpeg 单条命令）
├── skill/phone-alarm/SKILL.md       # 打包给其他用户的 agent skill
├── docs/video/xhs_phone_alarm.mp4   # 小红书宣传视频（不入库）
└── build.sh                         # 零 Gradle 构建脚本
```

## 🗣️ AI 语音播报（TTS）

三条路径，按场景选：

| 路径 | 说明 | 适用 |
|---|---|---|
| **node 内置 TTS**（推荐） | MCP server 内嵌 `msedge-tts`（微软神经音色，中文自然，零模型下载），合成后经 `/api/audio` 推送手机、走闹钟音量播放 | `phone_say` / `phone_tts` |
| 手机端侧系统 TTS | `/api/say` 走系统 `TextToSpeech`（完全离线）；**部分国产 ROM（实测 OnePlus 16）未开放标准 TTS 服务，会初始化失败** | 手机装有可用引擎时 |
| 完全离线小模型 | PC 上跑 [KittenTTS](https://github.com/KittenML/KittenTTS)（~25MB onnx，英文音色）等本地推理，产物 curl 上传 `/api/audio?save=1` | 无网/自定义音色 |

安全提示：App 状态卡对 token 做**掩码显示**（v1.8+），截图分享不泄漏；「复制 API」按钮才给全量。若旧版本截图曾泄漏 token，手机端 `pm clear com.axgu.phonealarm`（或重装）即可重新生成。

## 🎬 宣传视频

`bash tools/gen_xhs_video.sh` 一条命令用 ffmpeg 渲染 1080×1920 竖屏 18.5s（截图 Ken Burns + 文字卡 + 自合成 BGM），产物在 `docs/video/`（含封面图），可直接发小红书。

## 👥 贡献

| 贡献者 | 贡献 |
|---|---|
| **axgu** ([@soul667](https://github.com/soul667)) | 产品需求与定义、真机测试与验收、USB 调试联调支持 |
| **ZCode agent** (GLM) | 方案调研、架构设计、Android App 与 MCP server 全部开发、真机调试闭环、文档 |

> 由人机协作完成：人定义「要什么」，agent 实现「怎么要」。

## ⚠️ 已知边界

- 手机 IP 变更（DHCP）后需同步 `mcp/config.json`（建议路由器绑定静态租约）；App 主页实时显示当前 IP
- ColorOS 等深度定制系统上，App 升级后可能不立即自恢复 API，手动打开一次即可
- 响铃音量跟随系统「闹钟音量」通道
- `MY_PACKAGE_REPLACED` 自恢复依赖厂商广播投递，部分 ROM 延迟较高

## License

MIT
