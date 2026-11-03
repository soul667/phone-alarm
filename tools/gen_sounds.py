#!/usr/bin/env python3
"""程序合成手机闹钟内置铃声 → app/res/raw/*.wav（16bit/22050Hz/mono，stdlib only）。

铃声清单（爆炸系 + 一个温和的）：
  alarm_nuke   核爆冲击波：低频轰鸣 + 棕噪声衰减
  alarm_siren  防空警报：经典升降警笛
  alarm_klaxon 舰船警报：双频锯齿 ah-ooh-gah
  alarm_pulse  紧急脉冲：加速蜂鸣串
  alarm_birds  晨间鸟鸣：FM 啁啾（温和款）
"""
import math
import os
import random
import struct
import wave

SR = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res", "raw")


def write_wav(name, samples, gain=0.9):
    peak = max(1e-9, max(abs(s) for s in samples))
    scale = gain / peak
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, s * scale)) * 32767))
            for s in samples))
    print(f"{name}: {len(samples)/SR:.1f}s  {os.path.getsize(path)//1024}KB")


def nuke(dur=5.0):
    """核爆：瞬态爆点 + 亚低频扫频轰鸣 + 棕噪声余震。"""
    n = int(SR * dur)
    out, brown, phase = [], 0.0, 0.0
    random.seed(7)
    for i in range(n):
        t = i / SR
        env = math.exp(-t * 1.1)
        click = random.uniform(-1, 1) * 3.0 * math.exp(-t * 60)          # 起爆瞬态
        brown = max(-0.08, min(0.08, brown + random.uniform(-1, 1) * 0.02))  # 棕噪声
        f = 55.0 * math.pow(0.5, t / 2.2)                                 # 55→~28Hz 扫频
        phase += 2 * math.pi * f / SR
        boom = math.sin(phase) * 0.9
        out.append(click + (brown * 6.0 + boom) * env)
    return out


def siren(dur=4.0):
    """防空警报：500↔750Hz 正弦扫频，0.5Hz 循环 + 轻微颤音。"""
    n = int(SR * dur)
    out, phase = [], 0.0
    for i in range(n):
        t = i / SR
        f = 625.0 + 125.0 * math.sin(2 * math.pi * 0.5 * t)
        phase += 2 * math.pi * f / SR
        trem = 1.0 + 0.06 * math.sin(2 * math.pi * 7 * t)
        env = min(1.0, t / 0.05) * min(1.0, (dur - t) / 0.05)
        out.append(math.sin(phase) * 0.85 * trem * env)
    return out


def klaxon(dur=3.0):
    """舰船警报：310/240Hz 交替锯齿波 ah-ooh-gah。"""
    n = int(SR * dur)
    out, ph1, ph2 = [], 0.0, 0.0
    for i in range(n):
        t = i / SR
        seg = int(t / 0.45) % 2
        f = 310.0 if seg == 0 else 240.0
        ph1 += 2 * math.pi * f / SR
        saw = 2 * ((ph1 / (2 * math.pi)) % 1.0) - 1.0
        gate = 1.0 if (t % 0.45) < 0.38 else 0.15
        env = min(1.0, t / 0.03) * min(1.0, (dur - t) / 0.05)
        out.append(saw * 0.7 * gate * env)
    return out


def pulse(dur=4.0):
    """紧急脉冲：1.2kHz 蜂鸣串，5 连响 + 加速重复。"""
    n = int(SR * dur)
    out = []
    for i in range(n):
        t = i / SR
        cycle = 0.62
        pos = t % cycle
        beep_on = (pos < 0.08 or (0.16 <= pos < 0.24) or (0.32 <= pos < 0.40)
                   or (0.48 <= pos < 0.56) or (0.64 <= pos < 0.72))
        env = (math.exp(-((pos % 0.08)) * 30) if beep_on else 0.0)
        env *= min(1.0, t / 0.03) * min(1.0, (dur - t) / 0.05)
        s = math.sin(2 * math.pi * 1180 * t) + 0.4 * math.sin(2 * math.pi * 2360 * t)
        out.append(s * env * 0.75)
    return out


def birds(dur=5.0):
    """晨间鸟鸣：FM 啁啾组（温和款），确定性随机。"""
    n = int(SR * dur)
    out = [0.0] * n
    random.seed(42)
    t0 = 0.15
    while t0 < dur - 0.4:
        group = random.randint(2, 3)
        for g in range(group):
            start = t0 + g * random.uniform(0.14, 0.2)
            f0, f1 = random.uniform(2500, 3100), random.uniform(2100, 2600)
            length = random.uniform(0.09, 0.14)
            phase = 0.0
            for j in range(int(SR * length)):
                p = j / (SR * length)
                t = start + p * length
                if t >= dur:
                    break
                f = f0 + (f1 - f0) * p
                phase += 2 * math.pi * f / SR
                env = math.sin(math.pi * p) ** 1.5
                out[int(t * SR)] += math.sin(phase) * env * 0.5
        t0 += group * 0.17 + random.uniform(0.25, 0.6)
    return out


if __name__ == "__main__":
    write_wav("alarm_nuke.wav", nuke())
    write_wav("alarm_siren.wav", siren())
    write_wav("alarm_klaxon.wav", klaxon())
    write_wav("alarm_pulse.wav", pulse())
    write_wav("alarm_birds.wav", birds())
    print("DONE ->", os.path.abspath(OUT))
