---
name: phone-alarm
description: 用 MCP 控制 Android 手机的真实闹钟与语音播报：核心场景是**长任务跑完、或中途受阻（报错/中断/卡死）时立刻叫醒用户，防止挂机一夜白干**（训练/批处理/大下载挂机时用户去休息，agent 检测到完成或异常立即响铃+语音提醒），也支持定时闹钟、找手机。当用户说"跑完了叫我/训练完叫醒我/挂了叫我/卡住叫我/任务完成提醒我/叫我起床/设个闹钟/找一下手机/手机响一下"等，或提到 phone-alarm 时使用。前置：用户手机需装开源 App https://github.com/soul667/phone-alarm
---

# phone-alarm：控制用户手机的真实闹钟

通过 `phone-alarm` MCP server（已注册）操作用户 Android 手机上的闹钟 App。
所有工具返回 JSON 文本；出错时先读错误信息里的排查提示，不要盲目重试。

## 首次接入（新机器/新用户）

1. 用户手机安装 App 并拿到 IP+token（App 主页显示）：
   ```bash
   git clone https://github.com/soul667/phone-alarm && cd phone-alarm
   bash build.sh                     # 生成 phone-alarm.apk（需 build-tools 34 + JDK 17）
   python3 -m http.server 8899       # 手机浏览器访问 http://<PC局域网IP>:8899/phone-alarm.apk
   ```
2. 把 App 主页显示的 host/token 写入 `mcp-node/config.json`
3. 注册 MCP（ZCode `mcp.servers`）：
   ```json
   "phone-alarm": { "type": "stdio", "command": "node",
     "args": ["<repo路径>/mcp-node/index.js"], "enabled": true }
   ```
   依赖：`cd mcp-node && npm install`（node ≥18；TTS 功能零模型下载）
4. **改完 config 需重启会话**；验证：调 `phone_ping`，返回设备名即通

## 工具速查

| 任务 | 工具 |
|---|---|
| 建闹钟 | `phone_set_alarm(hour, minute, label, days?, sound?, tts?)` |
| 列表 / 删除 / 开关 / 改动 | `phone_list_alarms` / `phone_delete_alarm(id)` / `phone_toggle_alarm(id)` / `phone_update_alarm(id, ...)` |
| 立即响铃（找手机/强提醒） | `phone_ring_now(message, sound?="nuke")` → 之后 `phone_stop_ring` |
| 语音播报（手机说话） | `phone_say(text)` —— node 内置 TTS 合成后推送播放 |
| AI 自定义语音铃声 | `phone_tts(text, save_as)` → 得到 `custom:xxx` → 建钟时 `sound="custom:xxx"` |
| 默认铃声 | `phone_set_default_sound(sound)` |
| 状态 | `phone_status` / `phone_ping` |

`days`：1=周一…7=周日；`[1,2,3,4,5]`=工作日；不传=一次性（响后自动关闭）。
`sound` 内置：`nuke`核爆 `siren`防空警报 `klaxon`舰船警报 `pulse`紧急脉冲 `birds`晨间鸟鸣 `epic`史诗鼓点 `rock`运动摇滚 `system`系统。

## 常用配方

**明早 7 点叫起床（带 AI 语音）**：
1. `phone_tts(text="早上好，新的一天开始了", save_as="morning_greet")`
2. `phone_set_alarm(hour=7, minute=0, label="起床", sound="custom:morning_greet")`

**工作日闹钟**：`phone_set_alarm(hour=7, minute=30, label="上班", days=[1,2,3,4,5], sound="siren")`

**任务完成 + 异常叫醒（核心场景，防白干）**：跑长命令前告知用户"跑完或出错都会叫你"。失败更要叫——挂机一夜静默失败最浪费：
   ```bash
   # 成功分支
   phone_ring_now(message="训练完成，20000 步 ALL PASS，来收结果", sound="birds")
   # 失败分支（检测到非零退出/OOM/NaN/卡死）
   phone_ring_now(message="训练 2000 步 OOM 崩了，速来止损", sound="nuke")
   ```
   长任务建议加检查点轮询：每 N 分钟查一次日志/进度文件，连续 2 次无进展即视为卡死 → 立刻叫醒。
   更个性：先 `phone_tts(text="老板，训练跑完了，结果全绿", save_as="job_done")` → `phone_ring_now(message="训练完成", sound="custom:job_done")`

**找手机**：`phone_ring_now(message="手机在这里！")`（默认核爆铃声，最长响 5 分钟自动停）

**只说话不响铃**：`phone_say(text="外卖到了")`

## 排障

- `phone_ping` 连不上：①手机与 PC 同一 WiFi ②手机 App 打开过一次（服务常驻）③手机 IP 变了（让用户看 App 主页，更新 config.json）④防火墙
- 响了没声音：手机「闹钟音量」被调零；或铃声文件损坏 → `phone_status` 看 `ringing`，换内置铃声试
- `sound` 返回 400：custom 铃声要先 `phone_tts` 上传；`GET /api/sounds` 可查全部可用 key
- 响铃界面不亮屏：确认 App 已授「闹钟和提醒」权限（Android 12+）

## 边界

- 响铃最长 5 分钟自动停；贪睡=自动建 5 分钟后的一次性闹钟
- 一次性闹钟触发后自动关闭；重复闹钟自动排下一次
- 手机端 TTS（engine="phone"）在部分国产 ROM 不可用；默认走 node 侧合成（推荐，勿改）
