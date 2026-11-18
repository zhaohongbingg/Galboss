"""从 ER 粒子预览表里导入单帧贴图（blood_drop / blood_splash 这类只有 1 帧的）。

与 `import_er_butterfly.py` 的区别：那个是专门给「2 帧拍翅 + 发光层」的蝴蝶用的；
这里是通用单帧导入，原样搬 32×32，不做重着色、不生成发光层。

用法：
    python import_er_particle.py <key> <输出名> [--from-html <html>] [--dry]
示例：
    python import_er_particle.py blood_drop blood_drop
    python import_er_particle.py blood_splash blood_splash
"""
import base64
import re
import sys
from io import BytesIO
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
RES = HERE.parent.parent / "src" / "main" / "resources" / "assets" / "galboss"
DEFAULT_HTML = Path("D:/game/mc/er-particle-preview.html")

ENTRY = re.compile(
    r'\{"n":"([^"]+)","key":"([^"]+)","idx":(\d+),"s":"[^"]*","w":(\d+),"h":(\d+),"sz":\d+,"b64":"([A-Za-z0-9+/=]+)"\}'
)


def extract_from_html(html: Path, key: str) -> list[Image.Image]:
    text = html.read_text(encoding="utf-8", errors="replace")
    frames = {}
    for _n, k, idx, w, h, b64 in ENTRY.findall(text):
        if k != key:
            continue
        img = Image.open(BytesIO(base64.b64decode(b64))).convert("RGBA")
        frames[int(idx)] = img
    if not frames:
        raise SystemExit(f"{html} 里找不到 key={key}")
    return [frames[i] for i in sorted(frames)]


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry" in sys.argv[1:]
    if len(args) < 2:
        raise SystemExit(__doc__)
    key, out_name = args[0], args[1]
    html = DEFAULT_HTML
    if "--from-html" in sys.argv[1:]:
        html = Path(sys.argv[sys.argv.index("--from-html") + 1])

    vendored = SRC / f"er_{key}.png"
    if vendored.exists():
        frames = [Image.open(vendored).convert("RGBA")]
        src_desc = f"vendor 源帧 {vendored.name}"
    else:
        frames = extract_from_html(html, key)
        vendored.write_bytes(vendored_bytes := (SRC / f"er_{key}.png").read_bytes()) if False else None
        frames[0].save(SRC / f"er_{key}.png")
        src_desc = f"{html.name}（已 vendor 到 src/er_{key}.png）"

    img = frames[0]
    alpha = img.getchannel("A")
    print(f"{key}: {len(frames)} 帧，{img.size[0]}×{img.size[1]}，不透明包围盒 {alpha.getbbox()}，来源 {src_desc}")

    out = RES / "textures" / "particle" / f"{out_name}.png"
    if dry:
        print(f"[dry] 将写出 {out}")
        return 0
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print(f"写出 {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
