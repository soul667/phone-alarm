#!/usr/bin/env python3
"""生成 PPT/视频素材：①极光深色背景图（真高斯模糊，替代 PPT 平光斑）②轻柔氛围垫乐 BGM。
运行：python3 tools/gen_bg_assets.py  → docs/video/{bg_cover,bg_content}.png + bgm_soft.wav"""
import numpy as np
from PIL import Image, ImageFilter
import os, struct, wave

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "video")
os.makedirs(OUT, exist_ok=True)
W, H = 1080, 1920


def aurora_bg(name, glows):
    """深底 + 高斯模糊光斑 → 柔和极光。glows: [(cx,cy,radius,hex,strength)]"""
    img = np.zeros((H, W, 3), dtype=np.float64)
    img[:, :] = np.array([0x0A, 0x0C, 0x14]) / 255.0
    yy, xx = np.mgrid[0:H, 0:W]
    for cx, cy, r, hexc, k in glows:
        col = np.array([int(hexc[i:i + 2], 16) for i in (0, 2, 4)]) / 255.0
        d2 = ((xx - cx) ** 2 + (yy - cy) ** 2) / (r * r)
        img += np.clip(np.exp(-d2) * k, 0, 1)[:, :, None] * col[None, None, :]
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    im = im.filter(ImageFilter.GaussianBlur(60))
    im.save(os.path.join(OUT, name))
    print(name, "ok")


# 封面：光更强；内容页：光更收敛
aurora_bg("bg_cover.png", [
    (140, 120, 620, "7C6CFF", 0.55), (980, 900, 560, "5AD8E6", 0.22), (240, 1780, 600, "4A3ACF", 0.42),
])
aurora_bg("bg_content.png", [
    (90, 40, 520, "7C6CFF", 0.34), (1010, 1000, 500, "5AD8E6", 0.14), (180, 1860, 540, "4A3ACF", 0.30),
])


def soft_bgm(dur=30.0, sr=22050):
    """轻柔氛围垫乐：Am-F-C-G 和弦正弦垫 + 缓入缓出，音量低。"""
    chords = [
        [220.00, 261.63, 329.63, 440.00],   # Am
        [174.61, 220.00, 261.63, 349.23],   # F
        [196.00, 261.63, 329.63, 392.00],   # C (G4 顶)
        [196.00, 246.94, 293.66, 392.00],   # G
    ]
    seg = dur / len(chords)
    xf = 2.0  # 和弦交叉淡化
    n = int(sr * dur)
    t = np.arange(n) / sr
    out = np.zeros(n)
    for ci, ch in enumerate(chords):
        start, end = ci * seg - xf, (ci + 1) * seg + xf
        i0, i1 = max(0, int(start * sr)), min(n, int(end * sr))
        if i1 <= i0:
            continue
        tt = t[i0:i1]
        env = np.ones(i1 - i0)
        fade = int(min(xf, seg) * sr)
        env[:fade] *= np.linspace(0, 1, fade)
        env[-fade:] *= np.linspace(1, 0, fade)
        s = np.zeros(i1 - i0)
        for f in ch:
            s += np.sin(2 * np.pi * f * tt) * 0.25
            s += np.sin(2 * np.pi * f * 1.003 * tt) * 0.12  # 轻微失谐制造温度
        out[i0:i1] += s * env
    # 简易低通（滑动平均去毛刺）+ 全局包络
    k = 24
    out = np.convolve(out, np.ones(k) / k, mode="same")
    master = np.ones(n)
    e = int(1.5 * sr)
    master[:e] *= np.linspace(0, 1, e)
    master[-e:] *= np.linspace(1, 0, e)
    out *= master
    out *= 0.9 / (np.max(np.abs(out)) + 1e-9)
    path = os.path.join(OUT, "bgm_soft.wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in out))
    print("bgm_soft.wav", f"{dur}s")


soft_bgm()
