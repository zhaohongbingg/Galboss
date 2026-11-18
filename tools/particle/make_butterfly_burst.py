# -*- coding: utf-8 -*-
"""按设计图（金色蝴蝶攻击特效）生成所需粒子素材：

  蝴蝶 4 帧（32×32，2×2 图集 64×64）：
    帧0 完全展开 / 帧1 轻微收拢 / 帧2 翅膀合拢 / 帧3 重新展开
    素材仍用 AE 的三层（身体中轴 / 左翼 / 右翼），角度取 [0, 18, 38, 18]。

  星光（star）/ 光环（ring）/ 光点（dot）：程序化生成，加色混合用（透明底）。

输出：
  assets/galboss/textures/particle/butterfly_burst.png   (64×64)
  assets/galboss/textures/particle/burst_star.png        (48×48)
  assets/galboss/textures/particle/burst_ring.png        (64×64)
  assets/galboss/textures/particle/burst_dot.png         (32×32)
  预览：tools/particle/_preview/burst_*.png
"""
import os, math
from PIL import Image, ImageFilter, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src')
OUT = os.path.abspath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'galboss', 'textures', 'particle'))
PREV = os.path.join(HERE, '_preview')

FRAME = 32
GOLD = [(168, 118, 44), (198, 142, 62), (224, 166, 84), (240, 190, 108), (250, 214, 138), (255, 236, 180), (255, 250, 224)]


def ramp(t):
    t = max(0.0, min(1.0, t))
    x = t * (len(GOLD) - 1)
    i = int(x)
    if i >= len(GOLD) - 1:
        return GOLD[-1]
    f = x - i
    a, b = GOLD[i], GOLD[i + 1]
    return tuple(int(round(a[k] + (b[k] - a[k]) * f)) for k in range(3))


def load_cutout(name):
    im = Image.open(os.path.join(SRC, name)).convert('RGBA')
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            sat = mx - mn
            if mx > 228 and sat < 26:
                px[x, y] = (0, 0, 0, 0)
            elif mx > 205 and sat < 40:
                k = (mx - 205) / 51.0
                px[x, y] = (r, g, b, int(a * (1.0 - k) * 0.85))
    return im


def goldify(im):
    out = Image.new('RGBA', im.size)
    sp, dp = im.load(), out.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = sp[x, y]
            if a == 0:
                continue
            lum = (0.30 * r + 0.59 * g + 0.11 * b) / 255.0
            sat = (max(r, g, b) - min(r, g, b)) / 255.0
            t = lum ** 0.85
            t = (0.62 + 0.36 * t) if sat > 0.28 else (0.34 + 0.56 * t)
            c = ramp(t)
            dp[x, y] = (c[0], c[1], c[2], a)
    return out


def make_butterfly_atlas():
    mid = goldify(load_cutout('蝴蝶-中.png'))
    left = goldify(load_cutout('蝴蝶-左.png'))
    right = goldify(load_cutout('蝴蝶-右.png'))
    bbox = mid.getbbox()
    px = bbox[0] + (bbox[2] - bbox[0]) * 0.5
    py = bbox[1] + (bbox[3] - bbox[1]) * 0.52

    angles = [0.0, -18.0, -38.0, -18.0]   # 上抬为负
    cells = []
    for ang in angles:
        l = left.rotate(ang, resample=Image.BICUBIC, center=(px, py))
        r = right.rotate(-ang, resample=Image.BICUBIC, center=(px, py))
        c = Image.new('RGBA', mid.size, (0, 0, 0, 0))
        c.alpha_composite(l)
        c.alpha_composite(r)
        c.alpha_composite(mid)
        cells.append(c)

    # 统一缩放：4 帧取最大包围盒，缩进 32×32（留 2px 边）
    allb = None
    for c in cells:
        b = c.getbbox()
        if b:
            allb = b if allb is None else (min(allb[0], b[0]), min(allb[1], b[1]), max(allb[2], b[2]), max(allb[3], b[3]))
    span = max(allb[2] - allb[0], allb[3] - allb[1])
    scale = (FRAME - 4) / span

    atlas = Image.new('RGBA', (FRAME * 2, FRAME * 2), (0, 0, 0, 0))
    for i, c in enumerate(cells):
        sw, sh = int(c.width * scale), int(c.height * scale)
        im2 = c.resize((sw, sh), Image.LANCZOS)
        cx, cy = int(px * scale), int(py * scale)
        cell = Image.new('RGBA', (FRAME, FRAME), (0, 0, 0, 0))
        cell.alpha_composite(im2, (FRAME // 2 - cx, FRAME // 2 - cy))
        atlas.alpha_composite(cell, ((i % 2) * FRAME, (i // 2) * FRAME))
    return atlas


def make_star(size=48):
    """4 角星光：长芒 + 短芒 + 中心亮核（加色混合用，RGB 亮金，alpha 由亮度给）。"""
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = im.load()
    c = size / 2.0
    for y in range(size):
        for x in range(size):
            dx, dy = (x + 0.5 - c) / c, (y + 0.5 - c) / c
            r = math.hypot(dx, dy)
            ax, ay = abs(dx), abs(dy)
            # 十字长芒（水平/垂直），带一点柔化
            ray = max(0.0, 1.0 - min(ax, ay * 9.0)) * max(0.0, 1.0 - ay) + max(0.0, 1.0 - min(ay, ax * 9.0)) * max(0.0, 1.0 - ax)
            # 斜向短芒
            d1 = abs((dx + dy) / 1.414)
            d2 = abs((dx - dy) / 1.414)
            ray2 = 0.55 * (max(0.0, 1.0 - min(d1, d2 * 11.0)) * max(0.0, 1.0 - d2))
            core = max(0.0, 1.0 - r * 2.6) ** 1.5
            v = min(1.0, 0.9 * core + 0.85 * ray + 0.5 * ray2)
            if v <= 0.02:
                continue
            col = ramp(0.62 + 0.38 * v)
            px[x, y] = (col[0], col[1], col[2], int(255 * min(1.0, v)))
    return im.filter(ImageFilter.GaussianBlur(0.35))


def make_ring(size=64, thickness=0.10):
    """透视椭圆光环（俯视效果，加色混合）。"""
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = im.load()
    c = size / 2.0
    for y in range(size):
        for x in range(size):
            dx = (x + 0.5 - c) / c
            dy = (y + 0.5 - c) / (c * 0.62)   # 压扁成俯视椭圆
            r = math.hypot(dx, dy)
            d = abs(r - 1.0)
            if d > thickness * 2.2:
                continue
            v = max(0.0, 1.0 - d / (thickness * 2.2))
            col = ramp(0.55 + 0.45 * v)
            px[x, y] = (col[0], col[1], col[2], int(255 * v ** 1.4))
    return im.filter(ImageFilter.GaussianBlur(0.4))


def make_dot(size=32):
    """柔和光点（加色混合）。"""
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = im.load()
    c = size / 2.0
    for y in range(size):
        for x in range(size):
            r = math.hypot((x + 0.5 - c) / c, (y + 0.5 - c) / c)
            v = max(0.0, 1.0 - r) ** 2.2
            if v <= 0.01:
                continue
            col = ramp(0.62 + 0.38 * v)
            px[x, y] = (col[0], col[1], col[2], int(255 * v))
    return im


def save(im, name, preview_scale=8):
    os.makedirs(OUT, exist_ok=True)
    im.save(os.path.join(OUT, name))
    os.makedirs(PREV, exist_ok=True)
    pw, ph = im.width * preview_scale, im.height * preview_scale
    prev = Image.new('RGBA', (pw, ph), (10, 8, 14, 255))
    prev.alpha_composite(im.resize((pw, ph), Image.NEAREST))
    prev.save(os.path.join(PREV, 'burst_' + name.replace('.png', '_preview.png')))
    print(name, im.size, os.path.getsize(os.path.join(OUT, name)), 'B')


if __name__ == '__main__':
    save(make_butterfly_atlas(), 'butterfly_burst.png')
    save(make_star(), 'burst_star.png')
    save(make_ring(), 'burst_ring.png')
    save(make_dot(), 'burst_dot.png')
