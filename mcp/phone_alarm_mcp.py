#!/usr/bin/env python3
"""phone-alarm MCP server —— 让 agent 远程管理 Android 手机上的闹钟。

链路：ZCode agent ──stdio──> 本服务 ──HTTP──> 手机 App（com.axgu.phonealarm 内嵌 API）

配置（优先级：环境变量 > 同目录 config.json）：
  PHONE_ALARM_HOST   手机局域网 IP
  PHONE_ALARM_PORT   端口（默认 8787）
  PHONE_ALARM_TOKEN  手机 App 主页显示的 token
"""
import json
import os
import time
import urllib.error
import urllib.request

from mcp.server.fastmcp import FastMCP

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

mcp = FastMCP("phone-alarm")

DAY_NAMES = {1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六", 7: "周日"}

SOUND_NAMES = {
    "system": "系统闹钟铃声",
    "nuke": "核爆",
    "siren": "防空警报",
    "klaxon": "舰船警报",
    "pulse": "紧急脉冲",
    "birds": "晨间鸟鸣",
}
SOUND_KEYS = "system/nuke/siren/klaxon/pulse/birds"


def _cfg():
    cfg = {}
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        pass
    return {
        "host": os.environ.get("PHONE_ALARM_HOST", cfg.get("host", "")),
        "port": int(os.environ.get("PHONE_ALARM_PORT", cfg.get("port", 8787))),
        "token": os.environ.get("PHONE_ALARM_TOKEN", cfg.get("token", "")),
    }


def _req(method, path, body=None, timeout=6.0):
    c = _cfg()
    if not c["host"]:
        raise RuntimeError(
            "手机 IP 未配置。打开手机上的「手机闹钟」App，把主页显示的 host/port/token "
            f"填入 {CONFIG_PATH}（或设 PHONE_ALARM_HOST/PORT/TOKEN 环境变量后重启会话）。")
    url = f"http://{c['host']}:{c['port']}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {c['token']}")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        raise RuntimeError(f"手机返回 {e.code}: {detail}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise RuntimeError(
            f"连不上手机 {c['host']}:{c['port']}（{e}）。排查："
            "①手机与电脑是否同一 WiFi；②手机 App 是否打开过一次（API 服务常驻后台）；"
            "③手机 IP 是否变了（App 主页会显示当前 IP，同步改 config.json）；④电脑防火墙。")


def _fmt_alarm(a):
    """把 App 返回的闹钟转成人类友好的描述。"""
    days = a.get("days") or []
    if not days:
        repeat = "一次性"
    elif len(days) == 7:
        repeat = "每天"
    else:
        repeat = "周" + "".join(DAY_NAMES.get(d, "?")[1] for d in sorted(days))
    out = {
        "id": a.get("id"),
        "time": f"{a.get('hour', 0):02d}:{a.get('minute', 0):02d}",
        "label": a.get("label", ""),
        "repeat": repeat,
        "sound": a.get("sound", "system"),
        "sound_name": SOUND_NAMES.get(a.get("sound", "system"), "系统闹钟铃声"),
        "enabled": a.get("enabled", True),
        "next_ring": a.get("next_ring_human") or (
            time.strftime("%m-%d %H:%M", time.localtime(a["next_ring"] / 1000))
            if a.get("next_ring", 0) > 0 else "已关闭"),
    }
    return out


@mcp.tool()
def phone_ping() -> str:
    """测试与手机闹钟 App 的连通性（免 token 握手）。返回设备名、闹钟数、手机当前时间。"""
    r = _req("GET", "/api/ping")
    r["phone_time"] = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r.get("now", 0) / 1000))
    return json.dumps(r, ensure_ascii=False)


@mcp.tool()
def phone_set_alarm(hour: int, minute: int, label: str = "", days: list[int] | None = None,
                    sound: str = "system") -> str:
    """在手机上新建闹钟。hour 0-23，minute 0-59，label 是响铃界面显示的大字消息。
    days: 要重复的星期列表，1=周一 … 7=周日，如 [1,2,3,4,5]=工作日；不传（None）= 一次性闹钟。
    sound: 内置铃声，可选 system/nuke/siren/klaxon/pulse/birds（nuke=核爆 siren=防空警报 klaxon=舰船警报 pulse=紧急脉冲 birds=晨间鸟鸣）。
    返回创建好的闹钟（含 id）。"""
    body = {"hour": hour, "minute": minute, "label": label or "", "sound": sound}
    if days is not None:
        if not all(isinstance(d, int) and 1 <= d <= 7 for d in days):
            raise ValueError("days 取值必须是 1(周一) 到 7(周日) 的整数")
        body["days"] = sorted(set(days))
    r = _req("POST", "/api/alarms", body)
    return json.dumps({"ok": True, "alarm": _fmt_alarm(r)}, ensure_ascii=False)


@mcp.tool()
def phone_list_alarms() -> str:
    """列出手机上全部闹钟（时间/标签/重复/开关/下次响铃时间）。"""
    r = _req("GET", "/api/alarms")
    alarms = [_fmt_alarm(a) for a in r.get("alarms", [])]
    return json.dumps({"ok": True, "count": len(alarms), "alarms": alarms}, ensure_ascii=False)


@mcp.tool()
def phone_update_alarm(alarm_id: int, hour: int | None = None, minute: int | None = None,
                       label: str | None = None, days: list[int] | None = None,
                       enabled: bool | None = None, sound: str | None = None) -> str:
    """按 id 修改手机上的闹钟（只传要改的字段）。days 语义同 phone_set_alarm；
    注意 days=[] 表示改成一次性。sound 可选值同 phone_set_alarm。"""
    body = {}
    if hour is not None:
        body["hour"] = hour
    if minute is not None:
        body["minute"] = minute
    if label is not None:
        body["label"] = label
    if enabled is not None:
        body["enabled"] = enabled
    if sound is not None:
        body["sound"] = sound
    if days is not None:
        if not all(isinstance(d, int) and 1 <= d <= 7 for d in days):
            raise ValueError("days 取值必须是 1(周一) 到 7(周日) 的整数")
        body["days"] = sorted(set(days))
    if not body:
        raise ValueError("没有要修改的字段")
    r = _req("POST", f"/api/alarms/{alarm_id}", body)
    return json.dumps({"ok": True, "alarm": _fmt_alarm(r)}, ensure_ascii=False)


@mcp.tool()
def phone_delete_alarm(alarm_id: int) -> str:
    """按 id 删除手机上的闹钟。"""
    r = _req("DELETE", f"/api/alarms/{alarm_id}")
    return json.dumps({"ok": bool(r.get("ok"))}, ensure_ascii=False)


@mcp.tool()
def phone_toggle_alarm(alarm_id: int) -> str:
    """按 id 开/关切换手机上的闹钟。"""
    r = _req("POST", f"/api/alarms/{alarm_id}/toggle")
    return json.dumps({"ok": True, "alarm": _fmt_alarm(r)}, ensure_ascii=False)


@mcp.tool()
def phone_ring_now(message: str = "", sound: str = "nuke") -> str:
    """让手机立即响铃（用于找手机/立即提醒）。message 显示在响铃界面大字区。
    sound 可选 system/nuke/siren/klaxon/pulse/birds，默认 nuke（核爆，确保找得到手机）。"""
    r = _req("POST", "/api/ring", {"message": message or "", "sound": sound})
    return json.dumps({"ok": bool(r.get("ok")), "ringing": True,
                       "hint": "手机正在响铃；结束后用 phone_stop_ring 停止"},
                      ensure_ascii=False)


@mcp.tool()
def phone_stop_ring() -> str:
    """停止手机上正在响的铃声。"""
    r = _req("POST", "/api/stop")
    return json.dumps({"ok": bool(r.get("ok")), "ringing": False}, ensure_ascii=False)


@mcp.tool()
def phone_status() -> str:
    """查询手机 App 运行状态：API 是否在线、是否正在响铃、下次响铃时间。"""
    r = _req("GET", "/api/status")
    if r.get("next_ring"):
        r["next_ring_human"] = time.strftime("%m-%d %H:%M", time.localtime(r["next_ring"] / 1000))
    return json.dumps(r, ensure_ascii=False)


if __name__ == "__main__":
    mcp.run()
