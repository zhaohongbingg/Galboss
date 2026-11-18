"""把 ER 粒子预览表里的 `butterfly_orange_flap`（2 帧拍翅）打成 mod 用的粒子图集。

来源：`file:///D:/game/mc/er-particle-preview.html` 内嵌的 base64 贴图表，
条目形如 `{"n":"butterfly_orange_flap_0","key":"butterfly_orange_flap","idx":0,"s":"butterfly","w":32,"h":32,"b64":...}`。
两帧已经 vendor 到 `tools/particle/src/er_butterfly_orange_flap_{0,1}.png`，本脚本不再依赖那个 html
（想重新从 html 抽：`python import_er_butterfly.py --from-html "D:/game/mc/er-particle-preview.html"`）。

产出两张 64×32 的图集（2 帧 × 32px，1 行）：
    textures/particle/butterfly_orange.png        本体：原样放大到图集，保留 4 色调色板的硬边
    textures/particle/butterfly_orange_glow.png   发光层：同轮廓的高亮 + 一圈柔和光晕（给加色混合用）

为什么是「2 帧 × 1 行」而不是沿用原 4×2 的 8 帧：
    ER 这只蝴蝶只有展开/收拢两帧，硬凑 8 帧会出现重复帧或空帧（空帧 = 闪烁）。
    Java 侧的图集布局已经参数化（ButterflySprite），2×1 是原生支持的一档。

用法：
    python import_er_butterfly.py [--from-html <html>] [--dry]
"""
import base64
import json
import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageFilter

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
RES = HERE.parent.parent / "src" / "main" / "resources" / "assets" / "galboss"
OUT_BODY = RES / "textures" / "particle" / "butterfly_orange.png"
OUT_GLOW = RES / "textures" / "particle" / "butterfly_orange_glow.png"

FRAME_PX = 32           # 单帧边长（原图就是 32×32）
KEY = "butterfly_orange_flap"

# 发光层配色：把原画的「亮黄高光」提纯成暖白光，其余部分用橙色做光晕
GLOW_HIGHLIGHT = (255, 236, 170)
GLOW_BASE = (255, 168, 72)
GLOW_CORE_ALPHA = 210    # 轮廓内部的亮度（加色混合下越亮越「烧」）
HALO_ALPHA = 70          # 外扩一圈柔光的强度
HALO_BLUR = 1.6


def extract_from_html(html_path: Path) -> list[Image.Image]:
    """按表内顺序取出该 key 的所有帧。"""
    text = html_path.read_text(encoding="utf-8", errors="replace")
    pat = re.compile(
        r'\{"n":"[^"]+","key":"([^"]+)","idx":(\d+),"s":"[^"]*","w":(\d+),"h":(\d+),"sz":\d+,"b64":"([A-Za-z0-9+/=]+)"\}'
    )
    frames = {}
    for key, idx, w, h, b64 in pat.findall(text):
        if key != KEY:
            continue
        from io import BytesIO
        img = Image.open(BytesIO(base64.b64decode(b64))).convert("RGBA")
        frames[int(idx)] = img
    if not frames:
        raise SystemExit(f"{html_path} 里找不到 {KEY}")
    return [frames[i] for i in sorted(frames)]


def load_frames() -> list[Image.Image]:
    frames = []
    for i in (0, 1):
        p = SRC / f"er_butterfly_orange_flap_{i}.png"
        if not p.exists():
            raise SystemExit(f"缺少源帧 {p}（先跑 --from-html 从预览表抽取）")
        img = Image.open(p).convert("RGBA")
        if img.size != (FRAME_PX, FRAME_PX):
            raise SystemExit(f"{p} 尺寸 {img.size} 不是 {FRAME_PX}×{FRAME_PX}")
        frames.append(img)
    return frames


def build_atlas(frames: list[Image.Image]) -> Image.Image:
    atlas = Image.new("RGBA", (FRAME_PX * len(frames), FRAME_PX), (0, 0, 0, 0))
    for i, im in enumerate(frames):
        atlas.paste(im, (i * FRAME_PX, 0))
    return atlas


def build_glow(frames: list[Image.Image]) -> Image.Image:
    """同布局的发光图集：轮廓内提亮 + 外扩光晕（加色混合用）。"""
    w = FRAME_PX * len(frames)
    out = Image.new("RGBA", (w, FRAME_PX), (0, 0, 0, 0))
    for i, im in enumerate(frames):
        px = im.load()
        core = Image.new("RGBA", (FRAME_PX, FRAME_PX), (0, 0, 0, 0))
        cpx = core.load()
        for y in range(FRAME_PX):
            for x in range(FRAME_PX):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                # 亮黄高光（接近 255,226,120）→ 暖白；其余 → 橙色光
                bright = (r + g) / 2.0
                t = max(0.0, min(1.0, (bright - 170.0) / 70.0))
                cr = int(GLOW_BASE[0] * (1 - t) + GLOW_HIGHLIGHT[0] * t)
                cg = int(GLOW_BASE[1] * (1 - t) + GLOW_HIGHLIGHT[1] * t)
                cb = int(GLOW_BASE[2] * (1 - t) + GLOW_HIGHLIGHT[2] * t)
                cpx[x, y] = (cr, cg, cb, GLOW_CORE_ALPHA)

        # 光晕：把轮廓 dilate + 模糊，取其中一圈
        mask = Image.new("L", (FRAME_PX, FRAME_PX), 0)
        mpx = mask.load()
        for y in range(FRAME_PX):
            for x in range(FRAME_PX):
                if px[x, y][3] > 0:
                    mpx[x, y] = 255
        halo_mask = mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(HALO_BLUR))
        halo = Image.new("RGBA", (FRAME_PX, FRAME_PX), (0, 0, 0, 0))
        hpx = halo.load()
        hm = halo_mask.load()
        for y in range(FRAME_PX):
            for x in range(FRAME_PX):
                v = hm[x, y]
                if v <= 4:
                    continue
                # 边界衰减：这只蝴蝶几乎顶到画框边（bbox x 1..31），不衰减的话外扩光晕会在
                # 图集帧边界被硬切，游戏里加色混合下会看到一圈方形"鬼影"。
                edge = min(x, y, FRAME_PX - 1 - x, FRAME_PX - 1 - y)
                falloff = min(1.0, edge / 3.0)
                if falloff <= 0.0:
                    continue
                hpx[x, y] = (GLOW_BASE[0], GLOW_BASE[1], GLOW_BASE[2], int(v / 255.0 * HALO_ALPHA * falloff))

        frame = Image.alpha_composite(halo, core)
        out.paste(frame, (i * FRAME_PX, 0))
    return out


def main() -> int:
    dry = "--dry" in sys.argv[1:]
    if "--from-html" in sys.argv[1:]:
        html = Path(sys.argv[sys.argv.index("--from-html") + 1])
        frames = extract_from_html(html)
        print(f"从 {html.name} 抽出 {KEY} 的 {len(frames)} 帧")
        for i, im in enumerate(frames):
            im.save(SRC / f"er_butterfly_orange_flap_{i}.png")
    else:
        frames = load_frames()

    body = build_atlas(frames)
    glow = build_glow(frames)
    print(f"图集: {body.size[0]}×{body.size[1]}，{len(frames)} 帧 × {FRAME_PX}px")
    for i, im in enumerate(frames):
        alpha = im.getchannel("A")
        print(f"  帧 {i}: 不透明包围盒 {alpha.getbbox()}")

    if dry:
        print("[dry] 不写文件")
        return 0

    OUT_BODY.parent.mkdir(parents=True, exist_ok=True)
    body.save(OUT_BODY)
    glow.save(OUT_GLOW)
    print(f"写出 {OUT_BODY}")
    print(f"写出 {OUT_GLOW}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
