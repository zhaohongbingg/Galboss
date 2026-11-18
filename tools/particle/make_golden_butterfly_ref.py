# -*- coding: utf-8 -*-
"""把 AE 素材（身体中轴 / 左翼 / 右翼）处理成金色镂空蝴蝶粒子图集。

步骤：
  1. 抠白底 -> 透明（按亮度阈值 + 边缘去白）
  2. 金色化：按原像素亮度映射到金色 ramp（暗部也抬到深金，避免夜里出现黑框）
  3. 以翼根为轴旋转左右翼，合成 N 帧扇翅动画
  4. 输出 color 图集 + glow 图集（亮部内核 + 轮廓外扩光边，MC 里加色趟用）

输入（已归档在仓库里）：tools/particle/src/蝴蝶-{中,左,右}.png
     —— 原始 AE 工程里的三层：身体中轴 / 左翼 / 右翼（白底 PNG）
输出：src/main/resources/assets/galboss/textures/particle/golden_butterfly.png
      src/main/resources/assets/galboss/textures/particle/golden_butterfly_glow.png
      预览：tools/particle/_preview/*.png
"""
import os, math
from PIL import Image, ImageFilter, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src')
OUT = os.path.abspath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'galboss', 'textures', 'particle'))
PREV = os.path.join(HERE, '_preview')

FRAME_W, FRAME_H = 64, 64      # 单元尺寸（MC 粒子用；缩到 64 以内控制显存）
FRAMES = 8                     # 扇翅帧数
COLS = 4

# 金色 ramp：暗 -> 亮（深金褐 -> 暗金 -> 金 -> 亮金 -> 白金）
# 暗端刻意不压到近黑：原蓝蝶的黑边如果在夜里变成纯黑，会变成突兀的"黑框"。
GOLD_RAMP = [
    (104, 72, 26), (138, 98, 34), (172, 124, 42), (206, 152, 58),
    (234, 184, 84), (250, 214, 138), (255, 238, 196), (255, 255, 244),
]


def ramp(t):
    """t in [0,1] -> 金色 ramp 颜色。"""
    t = max(0.0, min(1.0, t))
    x = t * (len(GOLD_RAMP) - 1)
    i = int(x)
    if i >= len(GOLD_RAMP) - 1:
        return GOLD_RAMP[-1]
    f = x - i
    a, b = GOLD_RAMP[i], GOLD_RAMP[i + 1]
    return tuple(int(round(a[k] + (b[k] - a[k]) * f)) for k in range(3))


def load_cutout(name):
    """读 PNG 并抠白底：近白像素 -> 全透明，其余按亮度稍压暗对比。"""
    im = Image.open(os.path.join(SRC, name)).convert('RGBA')
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            mx = max(r, g, b)
            mn = min(r, g, b)
            sat = mx - mn
            # 白底：很亮且几乎无色彩 -> 透明；带一点蓝翼的蓝底不要误杀
            if mx > 228 and sat < 26:
                px[x, y] = (0, 0, 0, 0)
            elif mx > 205 and sat < 40:
                # 边缘过渡：按比例淡出，避免白边
                k = (mx - 205) / 51.0
                px[x, y] = (r, g, b, int(a * (1.0 - k) * 0.85))
            else:
                px[x, y] = (r, g, b, a)
    return im


def goldify(im):
    """蓝蝶 -> 金蝶：亮度 -> 金色 ramp；原图高饱和蓝的部分压到中间调，保留结构。"""
    out = Image.new('RGBA', im.size)
    sp, dp = im.load(), out.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = sp[x, y]
            if a == 0:
                continue
            lum = (0.30 * r + 0.59 * g + 0.11 * b) / 255.0
            sat = (max(r, g, b) - min(r, g, b)) / 255.0
            # 蓝色区域（饱和度高）当成"翼面"，映射到中亮金；黑色轮廓（低亮度低饱和）保留暗金褐
            t = lum ** 0.85
            if sat > 0.28:
                # 蓝翼（饱和区域）当成翼面：映射到偏亮的金，别压得太暗
                t = 0.55 + 0.42 * t
            else:
                # 原本的黑边（低饱和高暗度）也抬到深金，避免夜里出现突兀黑框
                t = 0.22 + 0.62 * t
            col = ramp(t)
            dp[x, y] = (col[0], col[1], col[2], a)
    return out


def bbox(im):
    return im.getbbox()


def rot_about(im, angle_deg, pivot):
    return im.rotate(angle_deg, resample=Image.BICUBIC, center=pivot)


def build_frames(mid, left, right, pivot):
    """左右翼绕翼根旋转，合成扇翅帧。

    wing_angle：0 = 水平展开，负 = 上抬。
    """
    frames = []
    for i in range(FRAMES):
        # 0 -> 展开，-> 抬起 -> 落下：三角波
        phase = i / FRAMES
        wave = math.sin(phase * 2.0 * math.pi)          # -1..1
        up = -34.0 * wave                             # 上抬为负角
        # 左翼向右转、右翼向左转（镜像）
        l = rot_about(left, up, pivot)
        rr = rot_about(right, -up, pivot)
        canvas = Image.new('RGBA', mid.size, (0, 0, 0, 0))
        canvas.alpha_composite(l)
        canvas.alpha_composite(rr)
        canvas.alpha_composite(mid)
        frames.append(canvas)
    return frames


def main():
    mid_raw = load_cutout('蝴蝶-中.png')
    left_raw = load_cutout('蝴蝶-左.png')
    right_raw = load_cutout('蝴蝶-右.png')

    mid = goldify(mid_raw)
    left = goldify(left_raw)
    right = goldify(right_raw)

    print('bbox mid  ', bbox(mid))
    print('bbox left ', bbox(left))
    print('bbox right', bbox(right))

    # 找身体中心：取中图里最靠上的"身体"区域（触角/头）
    # 简化：取中图不透明像素的横向中位（身体在中间），纵向取上部 1/3 区域
    mbox = bbox(mid)
    pivot_x = mbox[0] + (mbox[2] - mbox[0]) * 0.5
    # 翼根大约在身体中部（图中蝴蝶腰部）
    pivot_y = mbox[1] + (mbox[3] - mbox[1]) * 0.52
    print('pivot', pivot_x, pivot_y)

    frames = build_frames(mid, left, right, (pivot_x, pivot_y))

    # 统一裁到固定画布：以 pivot 为中心，取 W x H
    W, H = FRAME_W, FRAME_H
    def crop_center(im, scale=1.0):
        # 把 pivot 放到画面中心，按 scale 缩放
        sw, sh = int(im.width * scale), int(im.height * scale)
        im2 = im.resize((sw, sh), Image.LANCZOS)
        cx = int(pivot_x * scale)
        cy = int(pivot_y * scale)
        canvas = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        canvas.alpha_composite(im2, (W // 2 - cx, H // 2 - cy))
        return canvas

    # 自动取一个合适的缩放：让翅膀展开时刚好放进 64x64
    bbox_all = None
    for f in frames:
        b = f.getbbox()
        if b:
            bbox_all = b if bbox_all is None else (
                min(bbox_all[0], b[0]), min(bbox_all[1], b[1]),
                max(bbox_all[2], b[2]), max(bbox_all[3], b[3]))
    span = max(bbox_all[2] - bbox_all[0], bbox_all[3] - bbox_all[1])
    scale = (FRAME_W - 4) / span
    print('span', span, 'scale', round(scale, 3))

    cells = []
    for f in frames:
        cells.append(crop_center(f, scale))

    # ---- 图集 ----
    rows = (FRAMES + COLS - 1) // COLS
    atlas = Image.new('RGBA', (COLS * FRAME_W, rows * FRAME_H), (0, 0, 0, 0))
    glow_cells = []
    for i, c in enumerate(cells):
        x = (i % COLS) * FRAME_W
        y = (i // COLS) * FRAME_H
        atlas.alpha_composite(c, (x, y))

        # 辉光 = ① 内核亮部（只取最亮的一小撮）② 轮廓外扩一圈的描边光（像参考图的轮廓光）
        alpha = c.getchannel('A')
        lum = c.convert('L')
        core = lum.point(lambda v: 0 if v < 205 else min(255, (v - 205) * 4))
        g = Image.new('RGBA', c.size, (255, 232, 168, 0))
        g.putalpha(core)
        g = g.filter(ImageFilter.GaussianBlur(0.7))
        # 轮廓外扩：膨胀一圈减去本体 = 一像素光边（加色下就是"轮廓会发光"）
        dil = alpha.filter(ImageFilter.MaxFilter(3))
        ring = ImageChops.subtract(dil, alpha).filter(ImageFilter.GaussianBlur(0.9))
        rg = Image.new('RGBA', c.size, (255, 226, 150, 0))
        rg.putalpha(ring.point(lambda v: int(v * 0.8)))
        g.alpha_composite(rg)
        glow_cells.append(g)

    glow = Image.new('RGBA', atlas.size, (0, 0, 0, 0))
    for i, g in enumerate(glow_cells):
        glow.alpha_composite(g, ((i % COLS) * FRAME_W, (i // COLS) * FRAME_H))

    os.makedirs(OUT, exist_ok=True)
    atlas.save(os.path.join(OUT, 'golden_butterfly.png'))
    glow.save(os.path.join(OUT, 'golden_butterfly_glow.png'))
    print('atlas', atlas.size, '->', os.path.join(OUT, 'golden_butterfly.png'))

    # ---- 预览：深底 + 淡紫底，模拟游戏里的观感 ----
    os.makedirs(PREV, exist_ok=True)
    K = 8
    for name, bg in (('golden_butterfly_preview.png', (24, 18, 28, 255)),
                     ('golden_butterfly_glow_preview.png', (12, 8, 16, 255))):
        src = atlas if 'glow' not in name else glow
        pw, ph = src.width * K, src.height * K
        prev = Image.new('RGBA', (pw, ph), bg)
        prev.alpha_composite(src.resize((pw, ph), Image.NEAREST))
        prev.save(os.path.join(PREV, name))
    print('preview ok')


if __name__ == '__main__':
    main()
