"""从交付贴图里抽出「红瞳」发光遮罩（AutoGlowingGeoLayer 用的 _glowmask）。

交付的 .bbmodel 只有一张 1024² 贴图，没有单独画发光层。这里按颜色判定把红瞳抠出来：
红通道明显占优、且亮度够的像素 -> 原色保留；其余全部透明。

判据（可按需调）：
    r >= 150 且 r >= g * 2.5 且 r >= b * 2.5

第一版用 r>=80 / 1.55 会把领带那圈暗红也抠进来 —— 暗红 rgb(122,31,43) 同样红占优，
所以亮度阈值必须高到只留红瞳 rgb(210,17,34) 这一档。

用法：
    python make_goat_v6_glowmask.py [--dry]

输出：src/main/resources/assets/galboss/textures/entity/black_goat_servant_glowmask.png
"""
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
RES = HERE.parent.parent / "src" / "main" / "resources" / "assets" / "galboss"
MAIN = RES / "textures" / "entity" / "black_goat_servant.png"
OUT = RES / "textures" / "entity" / "black_goat_servant_glowmask.png"

R_MIN = 150
RATIO = 2.5


def main() -> int:
    dry = "--dry" in sys.argv[1:]
    if not MAIN.exists():
        print("主贴图不存在: %s" % MAIN)
        return 1

    src = Image.open(MAIN).convert("RGBA")
    w, h = src.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))

    kept = 0
    min_x, min_y, max_x, max_y = w, h, -1, -1
    peak = (0, 0, 0)
    px = src.load()
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if r >= R_MIN and r >= g * RATIO and r >= b * RATIO:
                dst[x, y] = (r, g, b, 255)
                kept += 1
                if r > peak[0]:
                    peak = (r, g, b)
                min_x, min_y = min(min_x, x), min(min_y, y)
                max_x, max_y = max(max_x, x), max(max_y, y)

    total = w * h
    print("主贴图 %dx%d，发光像素 %d（%.3f%%），峰值色 rgb%s" % (w, h, kept, kept / total * 100, peak))
    if kept:
        print("发光区域包围盒: x %d..%d  y %d..%d" % (min_x, max_x, min_y, max_y))
        print("（红瞳应是左右各一小块；若包围盒横跨整张图，说明判据过宽）")
    if dry:
        print("[dry] 不写文件")
        return 0
    if not kept:
        print("没有命中任何像素，判据需要放宽，未写文件")
        return 1

    out.save(OUT)
    print("写出 %s" % OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
