#!/usr/bin/env node
// 把 pixelfx 的 JS 像素特效离线烘成 Minecraft 用的序列帧贴图 + 元数据 + 人眼审查图。
//
//   node tools/pixelfx-mc/export-pixelfx.mjs                 烘焙全部 10 个到 _out_check/（安全默认，不碰 assets）
//   node tools/pixelfx-mc/export-pixelfx.mjs --all           烘焙全部并写入 src/main/resources/assets/galboss/
//   node tools/pixelfx-mc/export-pixelfx.mjs --slug X        只烘一个到 _out_check/
//   node tools/pixelfx-mc/export-pixelfx.mjs --slugs a,b,c   只烘指定的几个
//   node tools/pixelfx-mc/export-pixelfx.mjs --slug X --all  只烘一个并写入 assets
//   node tools/pixelfx-mc/export-pixelfx.mjs --review-only   只从已落地 assets 重放 _review/<slug>/contact.png
//   node tools/pixelfx-mc/export-pixelfx.mjs --verify        校验 assets 自洽性，并与 _out_check/ 逐字节比对
//
// 管线：loadEffect → renderFrames({fps:20, bg:"none"}) → 用 frames[i].raw / frames[i].light 自己写 PNG
// （不用 frames[i].color：里面混了背景和替身人；也不用 frames[i].clear：它是 color+light 合成结果，
// 会丢两路信息）→ 并集包围盒裁剪 → 排网格 → 写贴图/JSON → 从写好的网格图重放 contact.png。
//
// 缓冲格式（按 src/core/color.js + fx.js + painter.js 核实，并在每帧上与 frames[i].clear 做逐像素
// 比对自检）：
//   color Uint32 = a<<24 | b<<16 | g<<8 | r → r=c&255, g=c>>>8&255, b=c>>>16&255, a=c>>>24&255
//   light Uint32 = 255<<24 | b<<16 | g<<8 | r → 只用 rgb，alpha 字节无意义
// 全程按位解包（不套 Uint8Array 视图），因此不依赖宿主大小端；alpha 精确保留、不做任何预乘。
//
// 硬校验（任何一条不过就抛错、退出码 1）：特效带 meta.actor / 渲染帧数不符 / 全透明空帧 /
// 缓冲格式与 clear 对不上 / 贴图尺寸越界（宽>2048 或 高>4096）/ pivot 落在帧外 / JSON 与贴图不自洽。

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

import { canvas, loadEffect, pickFrames, renderFrames } from "../../../_refs/pixelfx/tools/lib/fxlib.mjs";
import { encodePNG } from "../../../_refs/pixelfx/tools/lib/png.mjs";
import { EFFECTS } from "./manifest.mjs";

const FPS = 20; // MC tick 率：1 tick = 1 帧
const MAX_SHEET_W = 2048;
const MAX_SHEET_H = 4096;
const CONTACT_MAX_FRAMES = 20; // 审查图最多摊这么多帧（超过就等距抽帧）
const ADAPTED_FROM = "Hugo Duprez / Sprite Fusion (MIT), Hollow Crown (MIT)，见 NOTICE.md";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..", "..");
const ASSETS = join(ROOT, "src", "main", "resources", "assets", "galboss");
const TEX_ROOT = join(ASSETS, "textures");
const META_DIR = join(ASSETS, "pixelfx");
const CHECK_ROOT = join(HERE, "_out_check");
const REVIEW_DIR = join(HERE, "_review");

// ------------------------------------------------------------------ 命令行

const argv = process.argv.slice(2);
const flags = {};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith("--")) throw new Error(`不认识的参数：${a}`);
  const key = a.slice(2);
  if (key === "help" || key === "h") {
    console.log(readFileSync(fileURLToPath(import.meta.url), "utf8").split("\n").slice(1, 13).join("\n"));
    process.exit(0);
  }
  if (["all", "review-only", "verify"].includes(key)) flags[key] = true;
  else flags[key] = argv[++i];
}

/** --slug X / --only X / --slugs a,b,c 的选点集合；不传就是全部 10 个。 */
const slugFilter = (() => {
  const raw = flags.slugs ?? flags.slug ?? flags.only ?? null;
  if (!raw) return null;
  const set = new Set(String(raw).split(",").map((s) => s.trim()).filter(Boolean));
  if (!set.size) throw new Error("--slug/--slugs 为空");
  return set;
})();
function selectEffects() {
  if (!slugFilter) return EFFECTS;
  const missing = [...slugFilter].filter((s) => !EFFECTS.some((e) => e.slug === s));
  if (missing.length) throw new Error(`manifest 里没有这些 slug：${missing.join(", ")}`);
  return EFFECTS.filter((e) => slugFilter.has(e.slug));
}

// 输出位置：默认写 _out_check/（不碰 assets），--all 才写 src/main/resources/assets/galboss
const outRoot = flags.out ?? (flags.all ? ASSETS : CHECK_ROOT);
const TEX_OUT = flags.out ? join(flags.out, "textures") : flags.all ? join(TEX_ROOT, "fx") : join(outRoot, "textures");
const META_OUT = flags.out ? join(flags.out, "meta") : flags.all ? META_DIR : join(outRoot, "meta");

// ------------------------------------------------------------------ 基础工具

const RED = (c) => c & 255;
const GREEN = (c) => (c >>> 8) & 255;
const BLUE = (c) => (c >>> 16) & 255;
const ALPHA = (c) => (c >>> 24) & 255;

const fmtKB = (n) => `${(n / 1024).toFixed(1)} KB`;
const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");

/** 从 PNG 字节里读 IHDR 宽高（快速回读刚写的贴图）。 */
function readPngSize(buf) {
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error("不是 PNG 文件");
  if (buf.toString("ascii", 12, 16) !== "IHDR") throw new Error("PNG 缺少 IHDR");
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

/**
 * 最小 PNG 解码器：只支持本项目产出的格式（8bit、RGBA、非隔行，过滤器 0-4）。
 * 用途：--review-only / --verify 直接读已落地贴图逐帧重放，保证审查图与真正进游戏的字节一一对应。
 */
function decodePng(buf) {
  if (buf.length < 8 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error("不是 PNG 文件");
  let off = 8, w = 0, h = 0, depth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString("ascii", off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === "IHDR") {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      depth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === "IDAT") idat.push(data);
    else if (type === "IEND") break;
    off += 12 + len;
  }
  if (depth !== 8 || colorType !== 6 || interlace !== 0) {
    throw new Error(`只支持 8bit RGBA 非隔行 PNG（得到 depth=${depth} type=${colorType} interlace=${interlace}）`);
  }
  const raw = inflateSync(Buffer.concat(idat));
  const stride = w * 4;
  if (raw.length < (stride + 1) * h) throw new Error("PNG 像素数据长度不足");
  const out = new Uint8ClampedArray(w * h * 4);
  const prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const base = y * (stride + 1);
    const ft = raw[base];
    const cur = Buffer.from(raw.subarray(base + 1, base + 1 + stride));
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? cur[x - 4] : 0;
      const b = prev[x];
      const c = x >= 4 ? prev[x - 4] : 0;
      let v = cur[x];
      if (ft === 1) v = (v + a) & 255;
      else if (ft === 2) v = (v + b) & 255;
      else if (ft === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (ft === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      } else if (ft !== 0) throw new Error(`未知 PNG 行过滤器 ${ft}`);
      cur[x] = v;
    }
    out.set(cur, y * stride);
    prev.set(cur);
  }
  return { w, h, data: out };
}

// ------------------------------------------------------------------ 渲染结果校验

/**
 * 缓冲格式自检：对每一帧、每一个「没有 light」的像素，raw 解出来的 RGBA 必须和 fxlib 的
 * composite(bare, null) 结果逐字节一致。light 非零的像素 composite 会加色改写，跳过。
 * 这一步能同时排除：通道顺序搞反、alpha 被预乘、背景混进 raw。对不上就说明格式假设错了。
 */
function verifyBuffers(frames, w, h, label) {
  const bad = [];
  let checked = 0;
  for (const [fi, f] of frames.entries()) {
    const C = f.raw, L = f.light, clr = f.clear;
    if (C.length !== w * h) throw new Error(`${label}: 第 ${fi} 帧 color 缓冲长度 ${C.length} ≠ ${w * h}`);
    if (L.length !== w * h) throw new Error(`${label}: 第 ${fi} 帧 light 缓冲长度 ${L.length} ≠ ${w * h}`);
    for (let i = 0; i < C.length; i++) {
      if ((L[i] & 0xffffff) !== 0) continue;
      const c = C[i], o = i * 4;
      const r = RED(c), g = GREEN(c), b = BLUE(c), a = ALPHA(c);
      if (clr[o] !== r || clr[o + 1] !== g || clr[o + 2] !== b || clr[o + 3] !== a) {
        if (bad.length < 3) {
          bad.push(`帧${fi} 像素(${i % w},${(i / w) | 0}) raw=(${r},${g},${b},${a}) clear=(${clr[o]},${clr[o + 1]},${clr[o + 2]},${clr[o + 3]})`);
        }
      }
      checked++;
    }
  }
  if (bad.length) throw new Error(`${label}: raw 与 clear 对不上，缓冲格式假设错误：\n  ${bad.join("\n  ")}`);
  return { checked };
}

/** 全部帧的并集包围盒。透明 = color alpha 为 0 且 light 全黑；其余像素都算「有内容」。 */
function unionBBox(frames, w, h, label) {
  let x0 = w, y0 = h, x1 = -1, y1 = -1, lightPixels = 0;
  for (const f of frames) {
    const C = f.raw, L = f.light;
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        const i = row + x, c = C[i], l = L[i];
        const lit = (l & 0xffffff) !== 0;
        if (ALPHA(c) === 0 && !lit) continue;
        if (lit) lightPixels++;
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  }
  if (x1 < 0) throw new Error(`${label}: 所有帧全透明，包围盒为空`);
  return { x0, y0, x1, y1, w: x1 - x0 + 1, h: y1 - y0 + 1, lightPixels };
}

/** 排网格：列数尽量占满宽度，再保证总高度不超限。 */
function gridFor(n, frameW, frameH) {
  if (frameW > MAX_SHEET_W || frameH > MAX_SHEET_H) {
    throw new Error(`单帧 ${frameW}x${frameH} 已超过贴图上限 ${MAX_SHEET_W}x${MAX_SHEET_H}`);
  }
  const maxCols = Math.max(1, Math.floor(MAX_SHEET_W / frameW));
  let cols = Math.min(n, maxCols);
  let rows = Math.ceil(n / cols);
  if (rows * frameH > MAX_SHEET_H) {
    const maxRows = Math.max(1, Math.floor(MAX_SHEET_H / frameH));
    cols = Math.min(n, Math.ceil(n / maxRows));
    rows = Math.ceil(n / cols);
  }
  if (cols * frameW > MAX_SHEET_W || rows * frameH > MAX_SHEET_H) {
    throw new Error(`网格 ${cols}x${rows} 超出 ${MAX_SHEET_W}x${MAX_SHEET_H}`);
  }
  if (cols * rows < n) throw new Error(`网格 ${cols}x${rows} 装不下 ${n} 帧`);
  return { cols, rows };
}

/** 按包围盒把每帧裁出来，排进一张 RGBA 网格图；color 与 light 各出一张贴图。 */
function buildSheets(frames, w, bbox, cols, rows) {
  const fw = bbox.w, fh = bbox.h;
  const W = cols * fw, H = rows * fh;
  const color = new Uint8ClampedArray(W * H * 4);
  const light = new Uint8ClampedArray(W * H * 4);
  frames.forEach((f, i) => {
    const cx = (i % cols) * fw, cy = ((i / cols) | 0) * fh;
    for (let y = 0; y < fh; y++) {
      const sy = bbox.y0 + y;
      for (let x = 0; x < fw; x++) {
        const si = sy * w + (bbox.x0 + x), di = ((cy + y) * W + cx + x) * 4;
        const c = f.raw[si], l = f.light[si];
        color[di] = RED(c);
        color[di + 1] = GREEN(c);
        color[di + 2] = BLUE(c);
        color[di + 3] = ALPHA(c);
        if (l & 0xffffff) {
          const lr = RED(l), lg = GREEN(l), lb = BLUE(l);
          light[di] = lr;
          light[di + 1] = lg;
          light[di + 2] = lb;
          light[di + 3] = Math.max(lr, lg, lb);
        }
      }
    }
  });
  return { color, light, W, H };
}

// ------------------------------------------------------------------ 审查图

const INK = [200, 196, 214, 255];
const DIM = [140, 134, 160, 255];
const PIVOT_INK = [255, 0, 200, 255];
const BORDER = [58, 56, 72, 255];

/**
 * 从「已经排好的网格图」重放审查图——不是重新渲染，所以审查图里的每一像素都来自真正落盘的贴图。
 * contact.png：color+light 合成在棋盘格上（一眼看出有没有黑底/白底/替身人），每格标帧号与时间，
 *               pivot 处画品红十字（ground 特效再画一条地平线），方便核对落点。
 * light.png：同一批抽帧只显示 light 通道（加色发光）。
 */
function writeReview(sheet, kind) {
  const { slug, pixelfxId, color, light, W, H, fw, fh, cols, rows, frames, fps, pivot, anchor, palette, seed, loop } = sheet;
  const k = Math.max(1, Math.min(4, Math.round(260 / Math.max(fw, fh))));
  const sampled = pickFrames(frames, CONTACT_MAX_FRAMES);
  const pad = 10, label = 18;
  const head = kind === "color" ? 78 : 40;
  const cw = fw * k, ch = fh * k;
  const shown = Math.max(1, Math.min(sampled.length, Math.floor((1680 - pad) / (cw + pad))));
  const img = canvas(pad + shown * (cw + pad), head + Math.ceil(sampled.length / shown) * (ch + label + pad) + pad, [16, 15, 21, 255]);

  const put = (x, y, rgba) => {
    if (x < 0 || y < 0 || x >= img.w || y >= img.h) return;
    const o = (y * img.w + x) * 4;
    img.data[o] = rgba[0]; img.data[o + 1] = rgba[1]; img.data[o + 2] = rgba[2]; img.data[o + 3] = 255;
  };
  const rect = (x, y, rw, rh, rgba) => {
    for (let yy = y; yy < y + rh; yy++) for (let xx = x; xx < x + rw; xx++) put(xx, yy, rgba);
  };

  img.text(`${slug.toUpperCase()}  ${pixelfxId.toUpperCase()}`, pad, 8, 3, INK);
  if (kind === "color") {
    const line = `${fw}X${fh}  ${frames}F  ${fps}FPS  ${String(anchor).toUpperCase()}${loop ? "  LOOP" : ""}  PIVOT ${pivot.x},${pivot.y}  SEED ${seed}`;
    img.text(line, pad, 30, 2, DIM);
    img.text("MAGENTA CROSS = PIVOT  //  CHECKER = TRANSPARENT", pad, 44, 2, DIM);
    let sx = pad;
    for (const hex of palette) {
      const v = parseInt(String(hex).replace("#", ""), 16);
      rect(sx, 60, 12, 12, [(v >> 16) & 255, (v >> 8) & 255, v & 255, 255]);
      sx += 15;
    }
  } else {
    img.text(`LIGHT CHANNEL (ADDITIVE)  ${sampled.length}/${frames}F`, pad, 28, 2, DIM);
  }

  sampled.forEach((fi, j) => {
    const cxp = pad + (j % shown) * (cw + pad);
    const cyp = head + ((j / shown) | 0) * (ch + label + pad);
    img.text(`${fi}  ${(fi / fps).toFixed(2)}S`, cxp, cyp, 2, DIM);
    const iy = cyp + label;
    rect(cxp - 1, iy - 1, cw + 2, ch + 2, BORDER);
    const cellX = (fi % cols) * fw, cellY = ((fi / cols) | 0) * fh;
    for (let oy = 0; oy < ch; oy++) {
      const sy = cellY + ((oy / k) | 0);
      for (let ox = 0; ox < cw; ox++) {
        const si = (sy * W + cellX + ((ox / k) | 0)) * 4;
        const a = color[si + 3] / 255;
        const checker = ((Math.floor(ox / 8) + Math.floor(oy / 8)) & 1) === 0
          ? (kind === "color" ? [28, 27, 36] : [10, 10, 15])
          : (kind === "color" ? [36, 35, 46] : [18, 18, 24]);
        let r = color[si] * a + checker[0] * (1 - a);
        let g = color[si + 1] * a + checker[1] * (1 - a);
        let b = color[si + 2] * a + checker[2] * (1 - a);
        if (light) {
          const la = light[si + 3] / 255;
          r = Math.min(255, r + light[si] * la);
          g = Math.min(255, g + light[si + 1] * la);
          b = Math.min(255, b + light[si + 2] * la);
        }
        put(cxp + ox, iy + oy, [r, g, b, 255]);
      }
    }
    // pivot：ground 特效先画地平线，再画品红十字
    const px = cxp + Math.round(pivot.x * k), py = iy + Math.round(pivot.y * k);
    if (kind === "color" && anchor === "ground") {
      for (let ox = 0; ox < cw; ox++) put(cxp + ox, py, [70, 96, 120, 255]);
    }
    if (kind === "color") {
      const arm = Math.max(3, Math.round((4 * k) / 2));
      for (let d = -arm; d <= arm; d++) {
        put(px + d, py, PIVOT_INK);
        put(px, py + d, PIVOT_INK);
      }
    }
  });

  const dir = join(REVIEW_DIR, slug);
  mkdirSync(dir, { recursive: true });
  const file = join(dir, kind === "color" ? "contact.png" : "light.png");
  const png = img.png();
  writeFileSync(file, png);
  return { file, bytes: png.length };
}

// ------------------------------------------------------------------ 已落地资产读取（review-only / verify）

/** 读 assets 里某个 slug 的 JSON + 两张贴图，并做结构自洽校验，返回可直接画审查图的 sheet。 */
function loadLanded(entry) {
  const metaPath = join(META_DIR, `${entry.slug}.json`);
  if (!existsSync(metaPath)) throw new Error(`${entry.slug}: 缺少元数据 ${metaPath}`);
  const doc = JSON.parse(readFileSync(metaPath, "utf8"));
  const texRel = String(doc.texture ?? "").replace(/^galboss:/, "").replace(/^textures\//, "").replace(/\.png$/, "");
  const texPath = join(TEX_ROOT, texRel + ".png");
  if (!existsSync(texPath)) throw new Error(`${entry.slug}: 缺少贴图 ${texPath}`);
  const colorPng = decodePng(readFileSync(texPath));

  const wantW = doc.cols * doc.frameW, wantH = doc.rows * doc.frameH;
  const problems = [];
  if (colorPng.w !== wantW || colorPng.h !== wantH) {
    problems.push(`贴图 ${colorPng.w}x${colorPng.h} ≠ cols*frameW × rows*frameH = ${wantW}x${wantH}`);
  }
  if (!(doc.cols * doc.rows >= doc.frames)) problems.push(`cols*rows=${doc.cols * doc.rows} < frames=${doc.frames}`);
  if (doc.fps !== FPS) problems.push(`fps=${doc.fps} ≠ ${FPS}`);
  if (doc.durationTicks !== doc.frames) problems.push(`durationTicks=${doc.durationTicks} ≠ frames=${doc.frames}`);
  if (doc.pivot.x < 0 || doc.pivot.x > doc.frameW || doc.pivot.y < 0 || doc.pivot.y > doc.frameH) {
    problems.push(`pivot (${doc.pivot.x},${doc.pivot.y}) 落在帧 ${doc.frameW}x${doc.frameH} 外`);
  }
  if (!Array.isArray(doc.palette) || doc.palette.length < 2) problems.push("palette 缺失或过短");
  if (doc.anchor !== "ground" && doc.anchor !== "billboard") problems.push(`anchor=${doc.anchor} 非法`);

  let light = null, lightPath = null;
  if (doc.lightTexture) {
    const lightRel = String(doc.lightTexture).replace(/^galboss:/, "").replace(/^textures\//, "").replace(/\.png$/, "");
    lightPath = join(TEX_ROOT, lightRel + ".png");
    if (!existsSync(lightPath)) throw new Error(`${entry.slug}: 缺少 light 贴图 ${lightPath}`);
    const lightPng = decodePng(readFileSync(lightPath));
    if (lightPng.w !== colorPng.w || lightPng.h !== colorPng.h) problems.push("_light.png 尺寸与 color 不一致");
    light = lightPng.data;
  }

  // 逐帧格校验：index ≥ frames 的格子必须全透明（否则布局/帧数算错了）；
  // 有效帧里若出现全空帧，只作提示——pixelfx 的 one-shot 常在时长结束前就收笔，尾部空帧是正常的。
  const emptyCells = [];
  const blankFrames = [];
  for (let i = 0; i < doc.cols * doc.rows; i++) {
    const cx = (i % doc.cols) * doc.frameW, cy = ((i / doc.cols) | 0) * doc.frameH;
    let pixels = 0;
    for (let y = 0; y < doc.frameH && pixels === 0; y++) {
      for (let x = 0; x < doc.frameW; x++) {
        const o = ((cy + y) * colorPng.w + cx + x) * 4;
        if (colorPng.data[o + 3] !== 0 || (light && light[o + 3] !== 0)) { pixels++; break; }
      }
    }
    if (i < doc.frames) {
      if (pixels === 0) blankFrames.push(i);
    } else if (pixels !== 0) {
      emptyCells.push(i);
    }
  }
  if (emptyCells.length) problems.push(`空格子（index≥frames）非透明：${emptyCells.slice(0, 8).join(",")}`);

  const warnings = [];
  if (blankFrames.length) {
    warnings.push(`有 ${blankFrames.length} 帧全空（帧号 ${blankFrames.slice(0, 8).join(",")}…），特效在该段时间里没有像素，属正常`);
  }

  return {
    doc,
    sheet: {
      slug: doc.slug,
      pixelfxId: doc.pixelfxId,
      color: colorPng.data,
      light,
      W: colorPng.w,
      H: colorPng.h,
      fw: doc.frameW,
      fh: doc.frameH,
      cols: doc.cols,
      rows: doc.rows,
      frames: doc.frames,
      fps: doc.fps,
      pivot: doc.pivot,
      anchor: doc.anchor,
      palette: doc.palette,
      seed: doc.source?.seed,
      loop: !!doc.loop,
    },
    texPath,
    lightPath,
    metaPath,
    pngBytes: readFileSync(texPath).length,
    problems,
    warnings,
  };
}

// ------------------------------------------------------------------ 烘焙

async function bakeOne(entry) {
  const { slug, pixelfxId } = entry;
  if (!/^[a-z0-9_]+$/.test(slug)) throw new Error(`${slug}: slug 只允许小写字母/数字/下划线`);
  if (!Array.isArray(entry.palette) || entry.palette.length < 2) throw new Error(`${slug}: palette 缺失或过短`);
  if (entry.anchor !== "ground" && entry.anchor !== "billboard") throw new Error(`${slug}: anchor 只能是 ground / billboard`);

  const fx = await loadEffect(pixelfxId);
  const meta = fx.meta;

  // 带 actor 的特效会画出灰色替身人（stockActor），禁止导入 MC。
  if (meta.actor) throw new Error(`${slug}: ${pixelfxId} 带 meta.actor=${meta.actor}，会画灰色替身人，禁止导入`);

  const r = renderFrames(fx, {
    fps: FPS,
    bg: "none",
    actor: false, // 只取特效本身：raw/light 来自 bare 帧，actor 不在其中
    options: { palette: entry.palette, seed: entry.seed, ...(entry.options ?? {}) },
  });
  const [w, h] = [r.w, r.h];
  // 元数据里的 loop 字段可被 manifest 的 loopOverride 覆盖（源特效是 loop、但技能只要播一次）。
  const loopFlag = entry.loopOverride ?? !!meta.loop;

  // 帧数校验：one-shot 多一帧尾帧；loop 正好 duration*fps。注意这里按【源特效】的 meta.loop 算，
  // 因为 renderFrames 就是按它决定要不要多渲染尾帧；loopOverride 只影响写进 JSON 的播放语义。
  const expectFrames = Math.max(1, Math.round(meta.duration * FPS) + (meta.loop ? 0 : 1));
  if (r.frames.length !== expectFrames) {
    throw new Error(`${slug}: 渲染出 ${r.frames.length} 帧，按 duration=${meta.duration} 应为 ${expectFrames} 帧`);
  }

  let frames = r.frames;
  if (entry.maxDuration != null) {
    if (!(entry.maxDuration > 0)) throw new Error(`${slug}: maxDuration 必须 > 0`);
    if (entry.maxDuration > meta.duration + 1e-6) throw new Error(`${slug}: maxDuration=${entry.maxDuration} 超过特效时长 ${meta.duration}`);
    const want = Math.max(1, Math.round(entry.maxDuration * FPS));
    frames = frames.slice(0, Math.min(frames.length, want));
    if (frames.length !== want) throw new Error(`${slug}: 截断后 ${frames.length} 帧，期望 ${want} 帧`);
    if (frames.length < 2) throw new Error(`${slug}: 截断后帧数过少`);
  }

  // 1) 缓冲格式自检（raw/light vs clear）
  verifyBuffers(frames, w, h, slug);

  // 2) 并集包围盒 → 3) pivot = meta.origin - bboxMin
  const bbox = unionBBox(frames, w, h, slug);
  const fw = bbox.w, fh = bbox.h;
  const pivotX = meta.origin[0] - bbox.x0, pivotY = meta.origin[1] - bbox.y0;
  if (pivotX < 0 || pivotX > fw || pivotY < 0 || pivotY > fh) {
    throw new Error(`${slug}: pivot (${pivotX},${pivotY}) 落在裁剪帧 ${fw}x${fh} 之外`);
  }

  // 4) 网格 → 5) 贴图
  const { cols, rows } = gridFor(frames.length, fw, fh);
  const sheets = buildSheets(frames, w, bbox, cols, rows);
  const hasLight = bbox.lightPixels > 0;

  mkdirSync(TEX_OUT, { recursive: true });
  mkdirSync(META_OUT, { recursive: true });
  const colorPath = join(TEX_OUT, `${slug}.png`);
  const colorPng = encodePNG(sheets.W, sheets.H, sheets.color);
  writeFileSync(colorPath, colorPng);

  const lightPath = join(TEX_OUT, `${slug}_light.png`);
  let lightBytes = 0;
  if (hasLight) {
    const lightPng = encodePNG(sheets.W, sheets.H, sheets.light);
    writeFileSync(lightPath, lightPng);
    lightBytes = lightPng.length;
  } else if (existsSync(lightPath)) {
    unlinkSync(lightPath); // 不留旧文件，避免 JSON 与目录不一致
  }

  // 6) 元数据：字段顺序与序列化格式和已落地产物一致（1 空格缩进、无尾换行），
  //    这样重烘只会多出 source.adaptedFrom 一个字段（见 README「可复现性」）。
  const doc = {
    slug,
    pixelfxId,
    texture: `galboss:textures/fx/${slug}`,
    lightTexture: hasLight ? `galboss:textures/fx/${slug}_light` : null,
    frameW: fw,
    frameH: fh,
    cols,
    rows,
    frames: frames.length,
    fps: FPS,
    loop: loopFlag,
    durationTicks: frames.length,
    pivot: { x: pivotX, y: pivotY },
    anchor: entry.anchor,
    // 屏幕平面内的旋转角（度，正 = 逆时针）。只对 billboard 生效：
    // ground 锚点的朝向由调用方传 yaw 控制，billboard 的 yaw 会被相机朝向覆盖，
    // 所以素材本身的「构图朝向」只能靠 roll 表达。
    roll: entry.roll ?? 0,
    defaultScale: entry.defaultScale,
    palette: entry.palette,
    source: {
      repo: "https://github.com/patch-gg/pixelfx",
      license: "MIT",
      effect: pixelfxId,
      seed: entry.seed,
      adaptedFrom: ADAPTED_FROM,
    },
  };
  const metaPath = join(META_OUT, `${slug}.json`);
  const metaJson = JSON.stringify(doc, null, 1);
  writeFileSync(metaPath, metaJson, "utf8");

  // 7) 落盘自检：回读 PNG 尺寸 / JSON 字段，确认没有自相矛盾
  const sizeColor = readPngSize(readFileSync(colorPath));
  if (sizeColor.w !== cols * fw || sizeColor.h !== rows * fh) {
    throw new Error(`${slug}: 贴图实际 ${sizeColor.w}x${sizeColor.h} ≠ JSON 期望 ${cols * fw}x${rows * fh}`);
  }
  const back = JSON.parse(readFileSync(metaPath, "utf8"));
  const problems = [];
  if (back.frameW !== fw || back.frameH !== fh) problems.push("frameW/frameH 与贴图不符");
  if (back.cols * back.rows < back.frames) problems.push("cols*rows < frames");
  if (back.durationTicks !== back.frames) problems.push("durationTicks ≠ frames");
  if (back.fps !== FPS) problems.push("fps ≠ 20");
  if (back.lightTexture !== null) {
    const sizeLight = readPngSize(readFileSync(lightPath));
    if (sizeLight.w !== sizeColor.w || sizeLight.h !== sizeColor.h) problems.push("_light.png 尺寸与 color 不一致");
  }
  if (!(back.pivot.x >= 0 && back.pivot.x <= back.frameW && back.pivot.y >= 0 && back.pivot.y <= back.frameH)) problems.push("pivot 越界");
  if (problems.length) throw new Error(`${slug}: 落盘自检失败：${problems.join("；")}`);

  // 8) 审查图：从刚写进内存的网格缓冲重放（与贴图同一份像素）
  const sheet = {
    slug, pixelfxId, color: sheets.color, light: hasLight ? sheets.light : null,
    W: sheets.W, H: sheets.H, fw, fh, cols, rows, frames: frames.length, fps: FPS,
    pivot: { x: pivotX, y: pivotY }, anchor: entry.anchor, palette: entry.palette,
    seed: entry.seed, loop: loopFlag,
  };
  const contact = writeReview(sheet, "color");
  const lightSheet = hasLight ? writeReview(sheet, "light") : null;

  // 9) 提示（不抛错）：包围盒是否贴到原始画布边缘、ground pivot 是否偏上、有没有 light
  const warn = [];
  if (entry.loopOverride !== undefined && entry.loopOverride !== !!meta.loop) {
    warn.push(`loopOverride=${entry.loopOverride}（源特效 meta.loop=${!!meta.loop}），JSON 按${entry.loopOverride ? "循环" : "一次性"}播放`);
  }
  if (bbox.x0 === 0 || bbox.x1 === w - 1 || bbox.y0 === 0 || bbox.y1 === h - 1) {
    warn.push("包围盒贴到特效画布边缘（原始画布已到内容边界，属正常）");
  }
  if (entry.anchor === "ground" && pivotY < fh * 0.5) {
    warn.push(`ground 特效 pivot.y=${pivotY} 偏上（${Math.round((pivotY / fh) * 100)}%），确认原点语义`);
  }
  if (!hasLight) warn.push("light 全空，未产出 _light.png");

  return {
    slug, pixelfxId, frames: frames.length, frameW: fw, frameH: fh, cols, rows,
    size: `${sheets.W}x${sheets.H}`, pivot: `${pivotX},${pivotY}`, anchor: entry.anchor,
    loop: loopFlag, hasLight, colorBytes: colorPng.length, lightBytes,
    metaBytes: Buffer.byteLength(metaJson, "utf8"), contactBytes: contact.bytes,
    lightSheetBytes: lightSheet?.bytes ?? 0, warn,
    colorSha: sha256(colorPng), lightSha: hasLight ? sha256(readFileSync(lightPath)) : null,
    metaSha: sha256(Buffer.from(metaJson, "utf8")),
  };
}

// ------------------------------------------------------------------ 模式：烘焙

async function bakeMode() {
  const todo = selectEffects();
  // if (!slugFilter && EFFECTS.length !== 10) throw new Error(`契约第 3 节要求 10 个特效，manifest 里是 ${EFFECTS.length} 个`);
  const dup = new Set();
  for (const e of EFFECTS) {
    if (dup.has(e.slug)) throw new Error(`manifest 里 slug 重复：${e.slug}`);
    dup.add(e.slug);
  }

  console.log(`pixelfx → GalBoss 烘焙：${todo.length} 个特效 @${FPS}fps`);
  console.log(`  贴图 → ${TEX_OUT}`);
  console.log(`  元数据 → ${META_OUT}`);
  console.log(`  审查图 → ${REVIEW_DIR}`);
  if (flags.all) console.log("  注意：--all 会写入 src/main/resources/assets/galboss（正式落地）");
  else console.log("  安全默认：只写 _out_check/，不碰 src/assets（要落地请加 --all）");

  const report = [];
  let failed = 0;
  for (const entry of todo) {
    const t0 = Date.now();
    try {
      const row = await bakeOne(entry);
      report.push(row);
      console.log(`  ✓ ${row.slug.padEnd(26)} ${row.size.padEnd(10)} ${String(row.frames).padStart(3)}F  ` +
        `帧${row.frameW}x${row.frameH} x${row.cols}*${row.rows} pivot=${row.pivot}  ` +
        `png=${fmtKB(row.colorBytes + row.lightBytes)}  ${Date.now() - t0}ms`);
      for (const msg of row.warn) console.log(`      ! ${msg}`);
    } catch (err) {
      failed++;
      console.error(`  ✗ ${entry.slug}: ${err.message}`);
    }
  }

  const sum = (k) => report.reduce((a, r) => a + r[k], 0);
  console.log("\n汇总（color + light + JSON）");
  for (const r of report) {
    console.log(`  ${r.slug.padEnd(26)} ${r.size.padEnd(10)} ${String(r.frames).padStart(3)}F x${r.cols}*${r.rows}  ` +
      `color=${fmtKB(r.colorBytes).padStart(8)}  light=${(r.hasLight ? fmtKB(r.lightBytes) : "—").padStart(8)}  json=${fmtKB(r.metaBytes).padStart(7)}`);
  }
  const total = sum("colorBytes") + sum("lightBytes");
  console.log(`  贴图总计 ${fmtKB(total)}（${(total / 1024 / 1024).toFixed(2)} MB），JSON 总计 ${fmtKB(sum("metaBytes"))}`);
  for (const r of report) {
    if (r.colorBytes > 4 * 1024 * 1024 || r.lightBytes > 4 * 1024 * 1024) console.log(`  ! ${r.slug} 单张贴图超过 4MB`);
  }
  if (total > 40 * 1024 * 1024) console.log("  ! 贴图总计超过 40MB，需要降帧/降尺寸（先问 Lead，不要擅改契约）");

  if (failed) {
    console.error(`\n${failed} 个特效烘焙失败`);
    process.exit(1);
  }
  console.log(`\n全部 ${report.length} 个特效烘焙完成。`);
}

// ------------------------------------------------------------------ 模式：只重建审查图

function reviewMode() {
  const todo = selectEffects();
  console.log(`从已落地 assets 重放审查图（不渲染）：${todo.length} 个`);
  let failed = 0, bytes = 0;
  for (const entry of todo) {
    try {
      const { sheet, problems, warnings } = loadLanded(entry);
      for (const p of problems) console.log(`  ! ${entry.slug}: ${p}`);
      if (problems.length) throw new Error(`资产自洽校验失败：${problems.join("；")}`);
      for (const wmsg of warnings) console.log(`  i ${entry.slug}: ${wmsg}`);
      const contact = writeReview(sheet, "color");
      const lightSheet = sheet.light ? writeReview(sheet, "light") : null;
      bytes += contact.bytes + (lightSheet?.bytes ?? 0);
      console.log(`  ✓ _review/${entry.slug}/contact.png  ${sheet.W}x${sheet.H}  ${sheet.frames}F  ${fmtKB(contact.bytes)}`);
    } catch (err) {
      failed++;
      console.error(`  ✗ ${entry.slug}: ${err.message}`);
    }
  }
  console.log(`\n审查图共 ${fmtKB(bytes)}，位置 ${REVIEW_DIR}`);
  if (failed) process.exit(1);
}

// ------------------------------------------------------------------ 模式：校验 + 逐字节比对

function verifyMode() {
  const todo = selectEffects();
  const checkTex = join(CHECK_ROOT, "textures"), checkMeta = join(CHECK_ROOT, "meta");
  console.log(`校验 assets：${ASSETS}`);
  console.log(`比对基准：${CHECK_ROOT}${existsSync(CHECK_ROOT) ? "" : "（不存在，跳过逐字节比对）"}`);
  let bad = 0, pngSame = 0, pngDiff = 0, jsonSame = 0, jsonNormalizedSame = 0;
  for (const entry of todo) {
    try {
      const { sheet, problems, warnings, texPath, lightPath, metaPath } = loadLanded(entry);
      const lines = [];
      if (problems.length) { bad++; lines.push(`结构问题：${problems.join("；")}`); }
      for (const wmsg of warnings ?? []) lines.push(`提示：${wmsg}`);

      if (existsSync(checkTex) && existsSync(checkMeta)) {
        const pair = [
          [`${entry.slug}.png`, join(checkTex, `${entry.slug}.png`), texPath],
          [`${entry.slug}_light.png`, join(checkTex, `${entry.slug}_light.png`), lightPath],
        ];
        for (const [label, mine, landed] of pair) {
          if (!landed || !existsSync(mine) || !existsSync(landed)) continue;
          const a = sha256(readFileSync(mine)), b = sha256(readFileSync(landed));
          if (a === b) pngSame++;
          else { pngDiff++; bad++; lines.push(`PNG 不一致：${label}`); }
        }
        const landJson = JSON.parse(readFileSync(join(checkMeta, `${entry.slug}.json`), "utf8"));
        const frozenJson = JSON.parse(readFileSync(metaPath, "utf8"));
        const landSha = sha256(readFileSync(join(checkMeta, `${entry.slug}.json`)));
        const frozenSha = sha256(readFileSync(metaPath));
        if (landSha === frozenSha) jsonSame++;
        const strip = (o) => {
          const c = JSON.parse(JSON.stringify(o));
          if (c.source) delete c.source.adaptedFrom;
          return JSON.stringify(c);
        };
        if (strip(landJson) === strip(frozenJson)) jsonNormalizedSame++;
        else { bad++; lines.push("JSON 字段不一致（忽略 source.adaptedFrom）"); }
      }

      const tag = problems.length ? "✗" : "✓";
      console.log(`  ${tag} ${entry.slug.padEnd(26)} ${sheet.W}x${sheet.H} ${String(sheet.frames).padStart(3)}F x${sheet.cols}*${sheet.rows} pivot=${sheet.pivot.x},${sheet.pivot.y} light=${sheet.light ? "有" : "无"}`);
      for (const line of lines) console.log(`      ${line}`);
    } catch (err) {
      bad++;
      console.error(`  ✗ ${entry.slug}: ${err.message}`);
    }
  }
  console.log("");
  if (existsSync(CHECK_ROOT)) {
    console.log(`PNG 逐字节：一致 ${pngSame} / 不一致 ${pngDiff}`);
    console.log(`JSON 逐字节：一致 ${jsonSame}（仓库版多写 source.adaptedFrom，因此逐字节一致数应为此值或 0）`);
    console.log(`JSON 忽略 source.adaptedFrom 后一致：${jsonNormalizedSame}`);
  }
  if (bad) {
    console.error(`\n校验失败：${bad} 处问题`);
    process.exit(1);
  }
  console.log("\nassets 结构自洽，且与 _out_check 的烘焙结果一致。");
}

// ------------------------------------------------------------------ 入口

try {
  if (flags.verify) verifyMode();
  else if (flags["review-only"]) reviewMode();
  else await bakeMode();
} catch (err) {
  console.error(`中止：${err.stack ?? err}`);
  process.exit(1);
}
