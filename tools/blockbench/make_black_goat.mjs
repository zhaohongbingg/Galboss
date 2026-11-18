#!/usr/bin/env node
// ============================================================================
// 黑山羊仆从（Black Goat Servant）程序化建模脚本
//
// 契约：tools/blockbench/BLACK-GOAT-SERVANT.md（§2 骨骼 / §3 几何 / §4 贴图）
//
// 链路（本机没有 Blockbench GUI/MCP）：
//   1. 本脚本生成 tools/blockbench/black_goat_servant.bbmodel
//      —— Blockbench 4.x bedrock/geckolib 工程，贴图以 data:image/png;base64 内嵌；
//      同时直接写出 assets/galboss/textures/entity/black_goat_servant_glowmask.png。
//   2. node tools/blockbench/import_bbmodel.mjs tools/blockbench/black_goat_servant.bbmodel \
//        black_goat_servant.geo.json --texture black_goat_servant.png
//      —— 导出 geo + 主贴图（坐标/UV 转换规则见 import_bbmodel.mjs 文件头）。
//   3. node tools/blockbench/make_black_goat.mjs --verify
//      —— 核对导出结果：12 骨骼名/层级/pivot、立方体数、128×128、
//         每个立方体 6 个面 100% 不透明、真实包围盒高度；并写 _preview/goat_head.geo.json
//         与贴图放大预览，供 pose_tool 出图。
//
// 用法：
//   node tools/blockbench/make_black_goat.mjs            # 生成 bbmodel + glowmask
//   node tools/blockbench/make_black_goat.mjs --verify   # 自检导出结果 + 写预览辅助文件
//
// 设计说明（坐标一律是 Blockbench 坐标：+X = 角色右手侧，+Y 上，正面朝 -Z）：
//   - 12 根骨骼严格按契约 §2，骨骼 rotation 全部为 0；羊角弯曲写在 cube rotation 上。
//   - 黑山羊骷髅头：颅骨 6.4×6.5×6.5 + 向前伸出的细长吻部（4.05 单位）+ 下颌 + 外伸下压耳
//     + 每侧 3 段后弯羊角（cube rotation，角根 2.2 → 角尖 1.0）。
//   - 幻魔者味身体：深靛长袍躯干 + 兜帽领 + 宽袖 + 腰带金扣 + 下摆（robe 骨骼）。
//   - 贴图 128×128：按部件分配 UV 矩形（shelf packing），每个立方体 6 面都分配 UV；
//     隐藏面复用共享的纯色小矩形（仍然不透明），可见面按各自密度绘制。
//   - 确定性：没有任何 Math.random / 时间 / 环境依赖，所有噪声来自整数哈希，
//     所有 UUID 来自 sha1(名字)，重复运行输出字节一致。
// ============================================================================

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import zlib from 'node:zlib';
import { Canvas } from './png_write.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const RES = join(ROOT, 'src', 'main', 'resources', 'assets', 'galboss');
const OUT_BBMODEL = join(HERE, 'black_goat_servant.bbmodel');
const OUT_GLOW = join(RES, 'textures', 'entity', 'black_goat_servant_glowmask.png');
const PREVIEW = join(HERE, '_preview');

// v5：贴图升到 256×256 —— 参考板（GOAT SERVANT）加了翻领/马甲/领带/怀表链/白手套分指，
// 128² 装不下（v4 的最后 90% 面积已用满）。立方体也从 55 涨到 ~130，见 BLACK-GOAT-SERVANT-V5.md。
const TEX_W = 256;
const TEX_H = 256;
// v5 立方体预算：参考板的翻领/马甲/领带/分指手套/燕尾/螺旋角需要 ~130 块；
// v1 契约的 35~70 是「低模长袍」时代的窗口，v5 正式上调（原因写在 BLACK-GOAT-SERVANT-V5.md）。
const CUBE_MIN = 100;
const CUBE_MAX = 200;

// ---------------------------------------------------------------- 色板（v5：黑西装 + 白衬衫/白手套 + 酒红领带 + 灰马甲 + 金饰 + 红眼）
// 设计原则：本体大面积是「近黑但有明度阶梯」，辨识度全靠 4 个高对比件（白衬衫、白手套、红领带、金饰），
// 所以黑色必须压到 #0b0b0e~#2e2e37 之间才有层次，不能全糊成 #111。
const PAL = {
  // 黑山羊骨（头骨/角基/下颌）
  boneDark: '#101014', boneMid: '#1b1b21', boneLight: '#292930', boneHigh: '#3a3a44', bonePeak: '#52525e',
  // 羊角角质
  hornDark: '#0f0f13', hornMid: '#191920', hornLight: '#25252d', hornHigh: '#33333d', hornTip: '#0a0a0d',
  // 红眼（发光层）
  eyeCore: '#c0161f', eyeHigh: '#ff4d4d', eyeSocket: '#3a080c',
  // 黑西装（羊毛）
  suitDark: '#141419', suitMid: '#1d1d24', suitLight: '#282831', suitHigh: '#36363f',
  // 旧键别名（保留：老绘制函数仍按 robe*/lining* 取值）
  robeDark: '#141419', robeMid: '#1d1d24', robeLight: '#282831', robeHigh: '#36363f',
  liningDark: '#0b0b0e', liningMid: '#121216',
  // 白衬衫 / 白手套
  shirtDark: '#a8aab0', shirtMid: '#d7d9dd', shirtLight: '#f4f5f7',
  gloveDark: '#b6b8be', gloveMid: '#d9dbdf', gloveLight: '#f2f3f5',
  // 灰马甲
  vestDark: '#3a3c41', vestMid: '#50535a', vestLight: '#666a72', vestHigh: '#7c8089',
  // 酒红领带
  tieDark: '#3a0b15', tieMid: '#571420', tieLight: '#7a1d2e', tieHigh: '#9d2c40',
  // 长裤 / 皮鞋
  trouserDark: '#17171c', trouserMid: '#212128',
  shoeDark: '#121216', shoeMid: '#1e1e24', sole: '#2b2b33',
  // 皮革（腰带）
  leatherDark: '#171009', leatherMid: '#241a12', leatherLight: '#33251a',
  // 金饰（怀表链/纽扣/袖扣）
  metal: '#b98a2e', metalHigh: '#f0c65a', metalDark: '#5e4412',
  // 尾巴毛
  furDark: '#191920', furMid: '#23232b', furLight: '#2f2f38', furHigh: '#3e3e48',
  bg: '#08080a'
};
const rgb = (hex) => [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
const C = Object.fromEntries(Object.entries(PAL).map(([k, v]) => [k, rgb(v)]));
const mix = (a, b, t) => [Math.round(a[0] + (b[0] - a[0]) * t), Math.round(a[1] + (b[1] - a[1]) * t), Math.round(a[2] + (b[2] - a[2]) * t)];

/** 确定性整数哈希 → [0,1)；像素噪声全靠它，保证重复运行结果一致。 */
function hash(x, y, seed = 0) {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(seed | 0, 1442695041);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

// ---------------------------------------------------------------- 贴图工具
/** 只在矩形内落笔，防止绘图越界污染相邻 UV 区。 */
function put(c, r, x, y, col) {
  if (x < r.x || y < r.y || x >= r.x + r.w || y >= r.y + r.h) return;
  c.set(x, y, [col[0], col[1], col[2], 255]);
}

/** 贴图矩形打包器：MaxRects（best-short-side-fit），比货架法省很多空间。
 *  每次分配 w×h 时实际占用 (w+1)×(h+1)，多出的 1px 作为采样溢出留白。 */
class Sheet {
  constructor(w, h) { this.w = w; this.h = h; this.free = [{ x: 0, y: 0, w, h }]; }
  alloc(w, h) {
    w = Math.max(1, Math.ceil(w));
    h = Math.max(1, Math.ceil(h));
    const ow = w + 1, oh = h + 1;
    let best = null;
    let bestScore = Infinity;
    for (const f of this.free) {
      if (f.w >= ow && f.h >= oh) {
        const score = Math.min(f.w - ow, f.h - oh) * 4 + Math.max(f.w - ow, f.h - oh);
        if (score < bestScore) { bestScore = score; best = f; }
      }
    }
    if (!best) throw new Error(`贴图空间不足：需要 ${w}x${h}（128x128 已满）`);
    const used = { x: best.x, y: best.y, w: ow, h: oh };
    const next = [];
    const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    for (const fr of this.free) {
      if (!overlap(fr, used)) { next.push(fr); continue; }
      if (used.x > fr.x) next.push({ x: fr.x, y: fr.y, w: used.x - fr.x, h: fr.h });
      if (used.x + used.w < fr.x + fr.w) next.push({ x: used.x + used.w, y: fr.y, w: fr.x + fr.w - (used.x + used.w), h: fr.h });
      if (used.y > fr.y) next.push({ x: fr.x, y: fr.y, w: fr.w, h: used.y - fr.y });
      if (used.y + used.h < fr.y + fr.h) next.push({ x: fr.x, y: used.y + used.h, w: fr.w, h: fr.y + fr.h - (used.y + used.h) });
    }
    this.free = next.filter((a) => !next.some((b) => b !== a && b.x <= a.x && b.y <= a.y && b.x + b.w >= a.x + a.w && b.y + b.h >= a.y + a.h));
    return { x: best.x, y: best.y, w, h };
  }
}

// ---------------------------------------------------------------- 绘制函数
// 统一签名 (canvas, rect, opt)，opt 里可能有 {seed, glow, ...}

/** 黑骨：以 #383847 打底，顶部向骨脊提亮、底部略压暗，叠加清晰横向骨节与棱边 #58586d/#787890 高光。 */
function pBone(c, r, o = {}) {
  const seed = o.seed ?? 11;
  const base = o.base ?? C.boneLight;
  const top = o.top ?? C.boneHigh;
  for (let y = 0; y < r.h; y++) {
    const t = r.h > 1 ? y / (r.h - 1) : 0.5;
    for (let x = 0; x < r.w; x++) {
      let col = mix(top, base, Math.min(1, t * 1.20));
      if (t > 0.84) col = mix(col, C.boneMid, ((t - 0.84) / 0.16) * 0.85);
      const n = hash(x, y, seed);
      if (n > 0.972) col = mix(col, C.boneHigh, 0.45);
      else if (n < 0.028) col = mix(col, C.boneMid, 0.55);
      put(c, r, r.x + x, r.y + y, col);
    }
  }
  // 骨脊：每 5 行一条很浅的横棱
  for (let y = 3; y < r.h; y += 5) {
    for (let x = 1; x < r.w - 1; x++) if (hash(x, y, seed + 11) > 0.5) put(c, r, r.x + x, r.y + y, mix(base, top, 0.55));
  }
  // 裂纹：少而短
  const cracks = Math.max(0, Math.round((r.w * r.h) / 1200));
  for (let i = 0; i < cracks; i++) {
    let x = 1 + Math.floor(hash(i, 3, seed) * Math.max(1, r.w - 3));
    let y = 1 + Math.floor(hash(i, 7, seed) * Math.max(1, r.h - 3));
    const len = 2 + Math.floor(hash(i, 13, seed) * 3);
    for (let k = 0; k < len; k++) {
      put(c, r, r.x + Math.min(r.w - 2, x), r.y + Math.min(r.h - 2, y), C.boneDark);
      x += 1;
      if (hash(k, i, seed + 17) > 0.6) y += 1;
    }
  }
  // 立体边：上缘亮、下缘暗、左亮右暗（骨块的切面感全靠这 4 条边）
  if (r.h > 2) {
    for (let x = 0; x < r.w; x++) {
      put(c, r, r.x + x, r.y, mix(top, C.bonePeak, 0.45));
      put(c, r, r.x + x, r.y + r.h - 1, mix(base, C.boneDark, 0.55));
    }
  }
  if (r.w > 2) {
    for (let y = 0; y < r.h; y++) {
      put(c, r, r.x, r.y + y, mix(top, C.boneHigh, 0.5));
      put(c, r, r.x + r.w - 1, r.y + y, mix(base, C.boneDark, 0.35));
    }
  }
}

/** 利爪骨骼：骨节感 + 指尖亮棱。 */
function pClaw(c, r, o = {}) {
  pBone(c, r, { seed: o.seed ?? 121, base: mix(C.boneLight, C.boneHigh, 0.35) });
  for (let y = 0; y < r.h; y++) {
    if (y >= Math.floor(r.h * 0.65)) {
      for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + y, mix(C.boneHigh, C.bonePeak, 0.7));
    }
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.bonePeak);
}

/** 颅骨顶面：中央骨矢缝（人字缝）+ 两侧高光骨棱。 */
function pSkullTop(c, r, o = {}) {
  pBone(c, r, { seed: 43, base: mix(C.boneLight, C.boneHigh, 0.25) });
  const cx = r.x + Math.floor(r.w / 2);
  for (let y = 0; y < r.h; y++) {
    const jit = hash(y, 17, 43) > 0.62 ? (hash(y, 19, 43) > 0.5 ? 1 : -1) : 0;
    put(c, r, cx + jit, r.y + y, C.boneDark);
    if (cx + jit - 1 >= r.x) put(c, r, cx + jit - 1, r.y + y, C.boneHigh);
    if (cx + jit + 1 < r.x + r.w) put(c, r, cx + jit + 1, r.y + y, C.boneHigh);
  }
}

/** v5 红瞳眼片（每只眼一块独立薄片，正面画斜挑红瞳，四周压成深眼窝）：
 *  - 逐列 3 行红瞳，靠外侧整体上抬 1 行 → 参考板那种「挑起来」的凶相；
 *  - 上眼睑压深、下眼睑留一线半透明感（红↔眼窝），外上角 1px 亮核进发光遮罩。 */
function pEye(c, r, o = {}) {
  const seed = o.seed ?? 401;
  const outerRight = o.outer !== 'l';
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y + y, hash(x, y, seed) > 0.55 ? C.boneDark : rgb('#06060a'));
  }
  const rows = Math.max(3, Math.round(r.h * 0.44));
  const y0 = Math.max(1, Math.round(r.h * 0.28));
  for (let x = 1; x < r.w - 1; x++) {
    const t = outerRight ? x / (r.w - 1) : 1 - x / (r.w - 1);
    const lift = t > 0.50 ? 1 : 0;
    const edgeTaper = x <= 1 || x >= r.w - 2;
    for (let k = 0; k < rows; k++) {
      if (edgeTaper && k !== Math.floor(rows / 2)) continue;
      const y = y0 - lift + k;
      let col = k === Math.floor(rows / 2) ? mix(C.eyeCore, C.eyeHigh, 0.35) : C.eyeCore;
      if (k === 0) col = mix(C.eyeCore, C.eyeSocket, 0.5);
      if (k === rows - 1) col = mix(C.eyeCore, C.eyeSocket, 0.25);
      put(c, r, r.x + x, r.y + y, col);
      o.glow?.push({ x: r.x + x, y: r.y + y, c: col });
    }
  }
  // 外上角亮核
  const hx = outerRight ? r.x + r.w - 3 : r.x + 2;
  const hy = Math.max(r.y, r.y + y0 - 1);
  put(c, r, hx, hy, C.eyeHigh);
  o.glow?.push({ x: hx, y: hy, c: C.eyeHigh });
}

/** 颅骨正面（-Z 面）= 额头 + 颧面：竖额缝 + 眉下压暗 + 菱形颧面切面 + 两侧颧弓亮棱。
 *  v5 起眼睛是独立薄片（pEye），这里不再画眼睛，只负责「黑骨面」的切面层次。 */
function pSkullFront(c, r, o = {}) {
  pBone(c, r, { seed: 31 });
  // 额骨顶阴影（贴图顶部 = 颅顶）
  for (let y = 0; y < Math.max(1, Math.round(r.h * 0.10)); y++) {
    for (let x = 0; x < r.w; x++) if (hash(x, y, 41) > 0.25) put(c, r, r.x + x, r.y + y, C.boneMid);
  }
  const cx = r.x + Math.round(r.w * 0.5);
  // 竖额缝（中线，向上收窄）
  for (let y = 0; y < Math.round(r.h * 0.55); y++) {
    put(c, r, cx, r.y + y, C.boneDark);
    if (hash(y, 3, 43) > 0.55) put(c, r, cx + (hash(y, 5, 47) > 0.5 ? 1 : -1), r.y + y, C.boneDark);
  }
  // 眉下压暗：眼片上方一条横向暗带（两眼之间最暗，形成 V 形怒眉）
  const browY = r.y + Math.round(r.h * 0.30);
  for (let x = 0; x < r.w; x++) {
    const t = Math.abs(x - (cx - r.x)) / Math.max(1, (r.w - 1) / 2);
    const th = 1 + Math.round((1 - t) * 2);
    for (let k = 0; k < th; k++) put(c, r, r.x + x, browY + k, mix(C.boneDark, C.boneMid, 0.25));
  }
  // 菱形颧面切面（参考板整张脸是「风筝形」分面）：中央菱形亮、四角暗
  const halfW = Math.max(2, Math.round(r.w * 0.34));
  const yTop = r.y + Math.round(r.h * 0.42), yBot = r.y + Math.round(r.h * 0.92);
  const yMid = Math.round((yTop + yBot) / 2);
  for (let y = yTop; y < yBot; y++) {
    const t = y < yMid ? (y - yTop) / Math.max(1, yMid - yTop) : (yBot - y) / Math.max(1, yBot - yMid);
    const hw = Math.round(1 + t * (halfW - 1));
    for (let dx = -hw; dx <= hw; dx++) {
      if (hash(cx + dx, y, 49) > 0.72) continue;
      put(c, r, cx + dx, y, dx === -hw || dx === hw ? C.boneMid : C.boneLight);
    }
  }
  // 颧弓高光棱（左右各一条斜亮边）
  for (let k = 0; k < Math.round(r.h * 0.4); k++) {
    const y = r.y + Math.round(r.h * 0.5) + k;
    for (const x of [r.x + 1 + Math.round(k * 0.3), r.x + r.w - 2 - Math.round(k * 0.3)]) {
      if (hash(x, y, 53) > 0.30) put(c, r, x, y, C.boneHigh);
    }
  }
  // 两侧下缘暗影（颧骨下方的凹陷）
  for (let y = Math.round(r.h * 0.78); y < r.h; y++) {
    for (const x of [r.x, r.x + 1, r.x + r.w - 2, r.x + r.w - 1]) put(c, r, x, y, C.boneDark);
  }
}

/** 颅骨侧面（东/西面）：颞骨切面 + 靠前侧压出深眼窝（v5 眼睛是独立薄片，这里只留凹陷阴影）。 */
function pSkullSide(c, r, o = {}) {
  pBone(c, r, { seed: 37 });
  const frontRight = o.front !== 'left';
  const eh = Math.max(2, Math.round(r.h * 0.22));
  const ey = r.y + Math.max(2, Math.round(r.h * 0.16));
  const ew = Math.max(3, Math.round(r.w * 0.30));
  const ex = frontRight ? r.x + r.w - ew - 1 : r.x + 1;
  // 眼窝凹陷（深色，无发光）
  for (let y = -1; y <= eh; y++) for (let x = -1; x <= ew; x++) {
    if (x >= 0 && x < ew && y >= 0 && y < eh) continue;
    put(c, r, ex + x, ey + y, C.boneDark);
  }
  for (let y = 0; y < eh; y++) for (let x = 1; x < ew - 1; x++) {
    put(c, r, ex + x, ey + y, y === 0 ? C.boneDark : mix(C.boneDark, C.boneMid, 0.45));
  }
  // 前缘（吻部方向的接缝）压一道暗边
  const fx = frontRight ? r.x + r.w - 1 : r.x;
  for (let y = 0; y < r.h; y++) put(c, r, fx, r.y + y, C.boneDark);
  // 颧弓与颞骨脊（斜向亮棱）
  for (let k = 0; k < Math.min(r.w, r.h); k++) {
    const x = frontRight ? r.x + Math.round(r.w * 0.42) + k : r.x + Math.round(r.w * 0.58) - k;
    const y = r.y + Math.round(r.h * 0.38) - Math.round(k * 0.5);
    if (hash(x, y, 59) > 0.30) put(c, r, x, y, C.boneHigh);
  }
  // 角根阴影
  const backX = frontRight ? r.x : r.x + r.w - 1;
  for (let y = 0; y < Math.round(r.h * 0.20); y++) {
    for (let k = 0; k < Math.round(r.w * 0.30); k++) {
      const x = frontRight ? backX + k : backX - k;
      if (hash(x, y, 61) > 0.35) put(c, r, x, y, mix(C.boneDark, C.boneMid, 0.35));
    }
  }
}

/** 颅骨背面：骨色 + 人字缝 + 枕骨脊。 */
function pSkullBack(c, r, o = {}) {
  pBone(c, r, { seed: 67 });
  let x = r.x + Math.round(r.w * 0.5);
  for (let y = 0; y < r.h; y++) {
    put(c, r, x, r.y + y, C.boneDark);
    if (hash(y, 9, 71) > 0.5) x += hash(y, 11, 73) > 0.5 ? 1 : -1;
    x = Math.max(r.x + 1, Math.min(r.x + r.w - 2, x));
  }
  for (let y = 0; y < Math.round(r.h * 0.14); y++) {
    for (let xx = 0; xx < r.w; xx++) if (hash(xx, y, 79) > 0.35) put(c, r, r.x + xx, r.y + y, mix(C.boneMid, C.boneDark, 0.55));
  }
}

/** 吻部侧面：亮骨底 + 上缘骨脊高光 + 颧鼻棱 + 下缘牙床齿槽 + 靠前鼻孔。 */
function pMuzzleSide(c, r, o = {}) {
  pBone(c, r, { seed: 83 });
  const frontRight = o.front !== 'left';
  for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y, C.bonePeak);
    put(c, r, r.x + x, r.y + r.h - 1, C.boneDark);
    if (r.h > 3) put(c, r, r.x + x, r.y + r.h - 2, mix(C.boneDark, C.boneMid, 0.45));
  }
  // 上牙床微齿节（在吻部下缘前侧点出 1px 骨牙节）
  if (r.h >= 4) {
    for (let x = 1; x < r.w - 1; x += 2) put(c, r, r.x + x, r.y + r.h - 2, C.boneHigh);
  }
  // 鼻梁方向的骨棱（横向亮线，靠上 1/3）
  const ry = r.y + Math.max(1, Math.round(r.h * 0.3));
  for (let x = 0; x < r.w; x++) if (hash(x, ry, 85) > 0.25) put(c, r, r.x + x, ry, mix(C.boneLight, C.boneHigh, 0.65));
  const nx = frontRight ? r.x + r.w - 3 : r.x + 1;
  const ny = r.y + Math.round(r.h * 0.35);
  for (let y = 0; y < 2; y++) for (let x = 0; x < 2; x++) put(c, r, nx + x, ny + y, C.boneDark);
}

/** 吻部顶面：中央 1px 鼻梁亮边 + 鼻骨缝 + 两侧骨面明暗。 */
function pMuzzleTop(c, r, o = {}) {
  pBone(c, r, { seed: 89 });
  const cx = r.x + Math.floor(r.w / 2);
  for (let y = 0; y < r.h; y++) {
    put(c, r, cx, r.y + y, C.bonePeak);
    if (cx - 1 >= r.x) put(c, r, cx - 1, r.y + y, mix(C.boneHigh, C.boneLight, 0.45));
    if (cx + 1 < r.x + r.w) put(c, r, cx + 1, r.y + y, mix(C.boneLight, C.boneMid, 0.6));
  }
  // 两侧骨棱
  for (let y = 0; y < r.h; y++) {
    put(c, r, r.x, r.y + y, mix(C.boneLight, C.boneHigh, 0.6));
    put(c, r, r.x + r.w - 1, r.y + y, C.boneMid);
  }
}

/** 吻部底面：腭骨暗槽。 */
function pMuzzleUnder(c, r, o = {}) {
  pBone(c, r, { seed: 97, base: mix(C.boneMid, C.boneDark, 0.5) });
  const cx = r.x + Math.floor(r.w / 2);
  for (let y = 0; y < r.h; y++) put(c, r, cx, r.y + y, C.boneDark);
}

/** 吻部前端（鼻子）：亮骨底 + 1px 鼻梁亮边 + 两个深陷鼻腔裂孔 + 咬合牙列感。 */
function pNoseFront(c, r, o = {}) {
  pBone(c, r, { seed: 103 });
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    if (hash(x, y, 104) > 0.30) put(c, r, r.x + x, r.y + y, mix(C.boneLight, C.boneHigh, 0.55));
  }
  const cx = r.x + Math.floor(r.w / 2);
  // 鼻梁 1px 亮边
  for (let y = 0; y < Math.round(r.h * 0.62); y++) put(c, r, cx, r.y + y, C.bonePeak);
  // 两个梨形鼻孔（斜向深孔，更像山羊头骨鼻腔）
  const ny = r.y + Math.round(r.h * 0.45);
  for (let dy = 0; dy < 2; dy++) {
    put(c, r, cx - 2 - (dy === 0 ? 1 : 0), ny + dy, C.boneDark);
    put(c, r, cx - 1, ny + dy, C.boneDark);
    put(c, r, cx + 1, ny + dy, C.boneDark);
    put(c, r, cx + 2 + (dy === 0 ? 1 : 0), ny + dy, C.boneDark);
  }
  // 嘴缝 + 微牙齿节（黑白交错齿感）
  for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y + r.h - 1, x % 2 === 0 ? C.boneHigh : rgb('#0d0d12'));
  }
  for (let y = 0; y < r.h; y++) {
    put(c, r, r.x, r.y + y, C.boneHigh);
    put(c, r, r.x + r.w - 1, r.y + y, C.boneMid);
  }
}

/** 下颌侧面：暗骨 + 上缘牙尖。 */
function pJawSide(c, r, o = {}) {
  pBone(c, r, { seed: 107, base: mix(C.boneMid, C.boneDark, 0.45) });
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.boneLight);
  for (let x = 0; x < r.w; x += 3) put(c, r, r.x + x, r.y + 1, C.boneHigh);
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.boneDark);
}

/** 下颌前端/底面。 */
function pJawFront(c, r, o = {}) {
  pBone(c, r, { seed: 109, base: mix(C.boneMid, C.boneDark, 0.4) });
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.boneLight);
}

function pJawUnder(c, r, o = {}) {
  pBone(c, r, { seed: 113, base: mix(C.boneMid, C.boneDark, 0.55) });
}

/** 耳朵：外缘暗、内缘一道亮骨脊（沿长轴）。 */
function pEar(c, r, o = {}) {
  pBone(c, r, { seed: 127 });
  const inner = Math.round(r.h * 0.5);
  for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y + inner, mix(C.boneLight, C.boneHigh, 0.35));
    put(c, r, r.x + x, r.y + inner - 1, C.boneDark);
    put(c, r, r.x + x, r.y + inner + 1, C.boneDark);
    put(c, r, r.x + x, r.y + r.h - 1, mix(C.boneDark, rgb('#000000'), 0.5));
  }
  for (let y = 0; y < r.h; y++) {
    put(c, r, r.x + r.w - 1, r.y + y, C.boneDark);
    put(c, r, r.x, r.y + y, C.boneLight);
  }
}

/** 羊角侧面：角根→角尖渐暗角质 + 立体环纹（高光+阴影槽）+ 角根仪式符文（进发光遮罩）。 */
function pHornSide(c, r, o = {}) {
  const seed = o.seed ?? 131;
  const seg = o.seg ?? 0;
  const tipFade = seg >= 4 ? 0.30 : seg === 3 ? 0.14 : 0;
  for (let y = 0; y < r.h; y++) {
    const t = r.h > 1 ? (1 - y / (r.h - 1)) : 0.5;
    for (let x = 0; x < r.w; x++) {
      // 横向明暗：外棱（x=0）亮、内缘（x=w-1）暗，中间平滑过渡（角质是光滑的，不要噪点）
      const edge = 1 - x / Math.max(1, r.w - 1);
      let col = mix(C.hornMid, C.hornLight, edge * 0.75);
      col = mix(col, C.hornTip, tipFade);
      if (hash(x, y, seed) > 0.93) col = mix(col, C.hornLight, 0.5);
      if (x === 0) col = mix(col, C.hornHigh, 0.65);
      else if (x === r.w - 1) col = mix(col, C.hornDark, 0.7);
      put(c, r, r.x + x, r.y + y, col);
    }
  }
  // 环纹：一道暗槽 + 下面一行微亮（羊角是光滑角质，只留很轻的节）
  for (const f of [0.46]) {
    const y = r.y + Math.min(r.h - 2, Math.round(r.h * f));
    for (let x = 0; x < r.w; x++) {
      put(c, r, r.x + x, y + 1, mix(C.hornLight, C.hornHigh, 0.5));
      put(c, r, r.x + x, y, mix(C.hornDark, C.hornMid, 0.5));
    }
  }
  // 角根：压一圈更深的角质环（拉出「角根嵌进颅骨」的层次），不再画金色符文
  if (o.root) {
    for (let x = 0; x < r.w; x++) {
      put(c, r, r.x + x, r.y + r.h - 1, C.hornDark);
      put(c, r, r.x + x, r.y + r.h - 2, mix(C.hornDark, C.hornMid, 0.5));
    }
  }
}

/** 羊角端面。 */
function pHornCap(c, r, o = {}) {
  const seed = o.seed ?? 137;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    put(c, r, r.x + x, r.y + y, n < 0.2 ? C.hornDark : n > 0.85 ? C.hornLight : C.hornMid);
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.hornDark);
}

/** 黑毛（羊腿）：以 #1e1e25 打底 + 毛发条纹与 #3a3a46 毛尖高光，避免糊成纯黑。 */
function pFur(c, r, o = {}) {
  const seed = o.seed ?? 149;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.boneMid;
    if (n < 0.22) col = C.boneDark;
    else if (n > 0.70) col = mix(C.boneMid, C.boneLight, 0.8);
    else if (n > 0.94) col = C.boneHigh;
    put(c, r, r.x + x, r.y + y, col);
  }
  // 毛发条纹（竖向）
  for (let x = 0; x < r.w; x += 2) {
    for (let y = 0; y < r.h; y++) if (hash(x, y, seed + 7) > 0.5) put(c, r, r.x + x, r.y + y, C.boneLight);
  }
  for (let y = 0; y < r.h; y++) put(c, r, r.x + r.w - 1, r.y + y, C.boneDark);
}

/** 蹄：黑角质 + 上缘亮边。 */
function pHoof(c, r, o = {}) {
  const seed = o.seed ?? 151;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = rgb('#101014');
    if (n > 0.82) col = C.boneDark;
    else if (n > 0.97) col = C.boneMid;
    put(c, r, r.x + x, r.y + y, col);
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, mix(C.boneLight, C.boneHigh, 0.4));
  for (let x = 0; x < r.w; x++) if (hash(x, 0, seed + 3) > 0.4) put(c, r, r.x + x, r.y + 1, C.boneMid);
}

/** 深靛长袍布料：每 3~4 像素一条明暗褶皱 + 垂直垂坠渐变 + 下沿可选银/暗紫金边饰，杜绝大片糊暗。 */
function pCloth(c, r, o = {}) {
  const seed = o.seed ?? 163;
  const base = o.base ?? C.robeMid;
  const dark = o.dark ?? C.robeDark;
  const light = o.light ?? C.robeLight;
  const high = o.high ?? C.robeHigh;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = base;
    if (n < 0.14) col = dark;
    else if (n > 0.86) col = light;
    else if (n > 0.97) col = high;
    put(c, r, r.x + x, r.y + y, col);
  }
  // 竖向褶皱：每 3~4 像素一条，脊线亮、背侧暗
  const period = o.period ?? 4;
  for (let x = 1; x < r.w; x += period) {
    const ph = hash(x, 0, seed + 5) * 6.283;
    for (let y = 0; y < r.h; y++) {
      const v = Math.sin((y / Math.max(1, r.h)) * 6.283 + ph) * 0.5 + 0.5;
      put(c, r, r.x + x, r.y + y, v > 0.6 ? mix(light, high, 0.35) : v > 0.35 ? light : mix(base, dark, 0.5));
    }
    if (x + 1 < r.w) {
      for (let y = 0; y < r.h; y++) if (hash(x, y, seed + 7) > 0.38) put(c, r, r.x + x + 1, r.y + y, dark);
    }
  }
  // 横向布纹（稀疏）
  for (let y = 2; y < r.h; y += 5) {
    for (let x = 0; x < r.w; x++) if (hash(x, y, seed + 11) > 0.65) put(c, r, r.x + x, r.y + y, mix(base, light, 0.45));
  }
  // 中缝（内衬深线 + 两侧暗紫镶边）
  if (o.seam) {
    const cx = r.x + Math.floor(r.w / 2);
    for (let y = 0; y < r.h; y++) {
      put(c, r, cx, r.y + y, dark);
      if (cx - 1 >= r.x) put(c, r, cx - 1, r.y + y, mix(light, high, 0.25));
      if (cx + 1 < r.x + r.w) put(c, r, cx + 1, r.y + y, mix(light, high, 0.25));
    }
  }
  // 袖口 / 下摆的暗金与亮紫镶边（让幻魔者长袍轮廓更精神）
  if (o.trim && r.h >= 3) {
    const ty = r.y + r.h - 2;
    for (let x = 0; x < r.w; x++) {
      put(c, r, r.x + x, ty - 1, C.robeHigh);
      put(c, r, r.x + x, ty, x % 3 === 1 ? C.metal : mix(C.metal, C.robeDark, 0.45));
      put(c, r, r.x + x, ty + 1, C.liningDark);
    }
  }
  // 破布下摆
  if (o.tatter) {
    for (let x = 0; x < r.w; x++) {
      const h = 1 + Math.floor(hash(x, 0, seed + 13) * 3);
      for (let y = r.h - h; y < r.h; y++) put(c, r, r.x + x, r.y + y, y === r.h - 1 ? C.boneDark : dark);
    }
  }
}

/** 腰带皮革：深皮革 + 缝线点 + 上下暗边。 */
function pBelt(c, r, o = {}) {
  const seed = o.seed ?? 173;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.leatherMid;
    if (n < 0.18) col = C.leatherDark;
    else if (n > 0.9) col = mix(C.leatherMid, C.metal, 0.25);
    put(c, r, r.x + x, r.y + y, col);
  }
  for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y, C.leatherDark);
    put(c, r, r.x + x, r.y + r.h - 1, C.leatherDark);
  }
  for (let x = 1; x < r.w; x += 3) {
    put(c, r, r.x + x, r.y + 1, mix(C.metal, C.leatherDark, 0.4));
    put(c, r, r.x + x, r.y + r.h - 2, mix(C.metal, C.leatherDark, 0.4));
  }
}

/** 金扣：金属底 + 菱形高光 + 暗边。 */
function pBuckle(c, r, o = {}) {
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const edge = x === 0 || y === 0 || x === r.w - 1 || y === r.h - 1;
    let col = edge ? mix(C.metal, C.leatherDark, 0.55) : C.metal;
    if (!edge) {
      const cx = (x - r.w / 2) / r.w, cy = (y - r.h / 2) / r.h;
      if (Math.abs(cx) + Math.abs(cy) < 0.28) col = C.metalHigh;
      else if (Math.abs(cx) + Math.abs(cy) < 0.55) col = mix(C.metal, C.metalHigh, 0.45);
    }
    put(c, r, r.x + x, r.y + y, col);
  }
  // 中心一点暗孔
  put(c, r, r.x + Math.floor(r.w / 2), r.y + Math.floor(r.h / 2), C.leatherDark);
}

/** 胸襟饰条：比袍身亮的镶边 + 两粒金扣。 */
function pPlacket(c, r, o = {}) {
  pCloth(c, r, { seed: 179, base: C.robeLight, dark: C.robeMid, light: C.robeHigh, period: 3 });
  for (let y = 0; y < r.h; y++) {
    put(c, r, r.x, r.y + y, C.robeDark);
    put(c, r, r.x + r.w - 1, r.y + y, C.robeDark);
  }
  const cx = r.x + Math.floor(r.w / 2);
  for (const f of [0.25, 0.55, 0.85]) {
    const y = r.y + Math.round(r.h * f);
    for (let dy = 0; dy < 2; dy++) for (let dx = -1; dx <= 1; dx++) put(c, r, cx + dx, y + dy, C.metal);
    put(c, r, cx, y, C.metalHigh);
  }
}

/** 兜帽领外表面（深靛内衬色 + 亮边）。 */
function pCollarOuter(c, r, o = {}) {
  pCloth(c, r, { seed: 181, base: C.liningMid, dark: C.liningDark, light: mix(C.liningMid, C.robeLight, 0.55), period: 4 });
  for (let x = 0; x < r.w; x++) {
    put(c, r, r.x + x, r.y, mix(C.robeLight, C.robeHigh, 0.4));
    put(c, r, r.x + x, r.y + 1, C.robeLight);
    put(c, r, r.x + x, r.y + r.h - 1, C.liningDark);
  }
}

/** 兜帽领内表面（更暗）。 */
function pCollarInner(c, r, o = {}) {
  pCloth(c, r, { seed: 191, base: C.liningDark, dark: rgb('#0a0912'), light: C.liningMid, period: 5 });
}

/** 领口顶面。 */
function pCollarTop(c, r, o = {}) {
  pCloth(c, r, { seed: 193, base: C.liningMid, dark: C.liningDark, light: C.robeLight, period: 4 });
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.robeLight);
}

/** 领口侧边。 */
function pCollarSide(c, r, o = {}) {
  pCloth(c, r, { seed: 197, base: C.liningMid, dark: C.liningDark, light: C.robeLight, period: 3 });
}

/** 颈部（骨）：正常骨色，保证头与兜帽领之间不是一团黑。 */
function pNeck(c, r, o = {}) {
  pBone(c, r, { seed: 199 });
}

// =============================================================== v5 材质画笔
// 参考板（GOAT SERVANT）的辨识度全押在「黑西装 + 白衬衫 + 酒红领带 + 灰马甲 + 白手套 + 金链 + 红眼」上，
// 所以每种材质一支画笔：黑色系用 4 级很窄的明度阶梯，白色系 3 级，金色只占 1~2 像素。

/** 黑羊毛呢料：窄明度区间噪点 + 稀疏横织纹。opt: fold（竖折痕 0~1）、edge（下缘亮折边）。 */
function pSuit(c, r, o = {}) {
  const seed = o.seed ?? 301;
  const base = o.base ?? C.suitMid;
  const top = mix(base, C.suitLight, 0.55);
  for (let y = 0; y < r.h; y++) {
    const t = r.h > 1 ? y / (r.h - 1) : 0.5;
    for (let x = 0; x < r.w; x++) {
      let col = mix(top, base, Math.min(1, t * 1.15));
      if (t > 0.86) col = mix(col, C.suitDark, ((t - 0.86) / 0.14) * 0.7);
      const n = hash(x, y, seed);
      if (n > 0.975) col = mix(col, C.suitLight, 0.5);
      else if (n < 0.02) col = mix(col, C.suitDark, 0.6);
      put(c, r, r.x + x, r.y + y, col);
    }
  }
  // 呢料横织纹：每 3 行一条 ±1 阶，远看只加质感不出雪花
  for (let y = 1; y < r.h; y += 3) {
    for (let x = 0; x < r.w; x++) if (hash(x, y, seed + 7) > 0.35) put(c, r, r.x + x, r.y + y, mix(base, C.suitLight, 0.35));
  }
  // 竖折痕
  if (o.fold != null) {
    const fx = r.x + Math.min(r.w - 1, Math.round(r.w * o.fold));
    for (let y = 0; y < r.h; y++) {
      put(c, r, fx, r.y + y, C.suitDark);
      if (fx + 1 < r.x + r.w) put(c, r, fx + 1, r.y + y, mix(C.suitLight, C.suitHigh, 0.35));
    }
  }
  // 下缘折边亮线
  if (o.edge && r.h > 3) {
    for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 2, mix(C.suitLight, C.suitHigh, 0.5));
  }
  // 立体边（可选）：大面板描边能读出体块，小块描边会变成"拼图网格"
  if (o.bevel) {
    if (r.h > 2) {
      for (let x = 0; x < r.w; x++) {
        put(c, r, r.x + x, r.y, mix(base, C.suitHigh, 0.55));
        put(c, r, r.x + x, r.y + r.h - 1, mix(C.suitDark, base, 0.3));
      }
    }
    for (let y = 0; y < r.h; y++) put(c, r, r.x + r.w - 1, r.y + y, mix(C.suitDark, base, 0.35));
  }
  // 前开衩的竖亮边（vEdge='l' 画在 rect 左列，'r' 画在右列）
  if (o.vEdge) {
    const col = o.vEdge === 'l' ? r.x : r.x + r.w - 1;
    const dark = o.vEdge === 'l' ? r.x + 1 : r.x + r.w - 2;
    for (let y = 0; y < r.h; y++) {
      put(c, r, col, r.y + y, mix(C.suitHigh, C.suitLight, 0.45));
      if (dark >= r.x && dark < r.x + r.w) put(c, r, dark, r.y + y, C.suitDark);
    }
  }
}

/** 翻领：比西装亮一档的呢料，内缘 1~2px 折边高光、外缘压暗，做出「翻出来的领面」。 */
function pLapel(c, r, o = {}) {
  const seed = o.seed ?? 311;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = mix(C.suitLight, C.suitHigh, 0.25);
    if (n < 0.22) col = C.suitLight;
    else if (n > 0.86) col = C.suitHigh;
    else if (n > 0.965) col = mix(C.suitHigh, C.bonePeak, 0.5);
    put(c, r, r.x + x, r.y + y, col);
  }
  // 折边：内缘（o.inner==='r' 时在右侧）1~2px 亮线 + 旁边 1px 暗槽
  const right = o.inner === 'r';
  for (let y = 0; y < r.h; y++) {
    const x0 = right ? r.x + r.w - 1 : r.x;
    const dx = right ? -1 : 1;
    put(c, r, x0, r.y + y, mix(C.suitHigh, C.bonePeak, 0.55));
    if (r.w > 3) put(c, r, x0 + dx, r.y + y, C.suitDark);
    const xOut = right ? r.x : r.x + r.w - 1;
    put(c, r, xOut, r.y + y, C.suitDark);
  }
  // 领面下缘亮折边
  for (let x = 0; x < r.w; x++) if (hash(x, 3, seed + 13) > 0.4) put(c, r, r.x + x, r.y + r.h - 1, C.suitHigh);
}

/** 灰马甲：竖向细条纹 + 可选「门襟纽扣 / 怀表链 / 侧扣」。 */
function pVest(c, r, o = {}) {
  const seed = o.seed ?? 321;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const stripe = x % 3 === 0;
    const n = hash(x, y, seed);
    let col = stripe ? mix(C.vestMid, C.vestLight, 0.55) : C.vestMid;
    if (n < 0.14) col = mix(C.vestDark, C.vestMid, 0.4);
    else if (n > 0.9) col = C.vestLight;
    if (x === 0 || x === r.w - 1) col = C.vestDark;
    put(c, r, r.x + x, r.y + y, col);
  }
  const cx = r.x + Math.round(r.w / 2);
  // 门襟中线
  for (let y = 0; y < r.h; y++) {
    put(c, r, cx, r.y + y, C.vestDark);
    if (cx + 1 < r.x + r.w) put(c, r, cx + 1, r.y + y, mix(C.vestMid, C.vestLight, 0.7));
  }
  // 小纽扣（灰白点，沿中线）
  if (o.buttons) {
    for (const t of [0.18, 0.46, 0.74]) {
      const y = r.y + Math.min(r.h - 1, Math.round(r.h * t));
      put(c, r, cx, y, C.shirtMid);
      put(c, r, cx + 1, y, C.shirtDark);
    }
  }
  // 怀表链：从左侧腰钉上扬再垂到右侧腰钉的 U 形金链（1px 链身 + 端点亮点）
  if (o.chain && r.w >= 12 && r.h >= 10) {
    const y0 = r.y + Math.round(r.h * 0.30);
    const xa = r.x + 2, xb = r.x + r.w - 3;
    const drop = Math.round(r.h * 0.20);
    for (let x = xa; x <= xb; x++) {
      const t = (x - xa) / Math.max(1, xb - xa);
      const y = y0 + Math.round(Math.sin(t * Math.PI) * drop);
      put(c, r, x, y, (x % 2 === 0) ? C.metalHigh : C.metal);
    }
    for (const [x, y] of [[xa, y0 - 1], [xb, y0 - 1]]) {
      put(c, r, x, y, C.metalHigh);
      put(c, r, x, y + 1, C.metal);
    }
  }
  // 腰侧金扣（左右各一）
  if (o.studs && r.h >= 8) {
    const y = r.y + Math.round(r.h * 0.72);
    for (const x of [r.x + 3, r.x + r.w - 4]) {
      put(c, r, x, y, C.metal);
      put(c, r, x, y + 1, C.metalDark);
      put(c, r, x + (x < cx ? 1 : -1), y, C.metalHigh);
    }
  }
}

/** 白衬衫：亮白 + 领口 V 形暗槽 + 两道细褶。 */
function pShirt(c, r, o = {}) {
  const seed = o.seed ?? 331;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.shirtMid;
    if (n < 0.12) col = C.shirtDark;
    else if (n > 0.82) col = C.shirtLight;
    put(c, r, r.x + x, r.y + y, col);
  }
  // 领口 V：顶部中央向下收的暗槽（正面看就是衬衫领口）
  const cx = r.x + Math.round(r.w / 2);
  if (o.vneck) {
    for (let y = 0; y < Math.max(2, Math.round(r.h * 0.55)); y++) {
      const half = Math.max(0, Math.round((1 - y / Math.max(1, r.h * 0.55)) * (r.w * 0.32)));
      for (let x = cx - half; x <= cx + half; x++) put(c, r, x, r.y + y, mix(C.shirtDark, C.vestDark, 0.55));
      put(c, r, cx - half - 1, r.y + y, C.shirtLight);
      put(c, r, cx + half + 1, r.y + y, C.shirtLight);
    }
  }
  for (let x = 1; x < r.w; x += 4) {
    for (let y = 0; y < r.h; y++) if (hash(x, y, seed + 5) > 0.6) put(c, r, r.x + x, r.y + y, C.shirtDark);
  }
  for (let y = 0; y < r.h; y++) { put(c, r, r.x, r.y + y, C.shirtLight); put(c, r, r.x + r.w - 1, r.y + y, C.shirtLight); }
}

/** 酒红领带：中央亮脊 + 两侧压暗 + 可选收尖的下端。 */
function pTie(c, r, o = {}) {
  const seed = o.seed ?? 341;
  const cx = r.x + Math.round(r.w / 2);
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    const dist = Math.abs(x - (cx - r.x)) / Math.max(1, (r.w - 1) / 2);
    let col = mix(C.tieLight, C.tieDark, Math.min(1, dist * 0.9));
    if (n < 0.16) col = C.tieDark;
    else if (n > 0.9) col = C.tieHigh;
    // 底部收尖：下半段两侧各收 1px
    if (o.taper) {
      const t = y / Math.max(1, r.h - 1);
      if (t > 0.72 && dist > 0.55 + (1 - t) * 0.2) col = C.tieDark;
    }
    put(c, r, r.x + x, r.y + y, col);
  }
  for (let y = 1; y < r.h; y += 3) put(c, r, cx - 1, r.y + y, C.tieHigh);
}

/** 白手套：白底 + 指缝阴影线（o.gaps = 几条竖缝）+ 掌背高光。 */
function pGlove(c, r, o = {}) {
  const seed = o.seed ?? 351;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.gloveMid;
    if (n < 0.10) col = C.gloveDark;
    else if (n > 0.80) col = C.gloveLight;
    put(c, r, r.x + x, r.y + y, col);
  }
  // 指缝：n 条纵向暗线，从下半段开始（指根以下）
  const gaps = o.gaps ?? 3;
  for (let i = 1; i <= gaps; i++) {
    const x = r.x + Math.round((r.w * i) / (gaps + 1));
    const y0 = r.y + Math.round(r.h * (o.gapTop ?? 0.30));
    for (let y = y0; y < r.h; y++) put(c, r, x, y, mix(C.gloveDark, C.vestDark, 0.45));
  }
  // 手套口（袖口）一道亮折边
  if (o.cuff && r.h > 3) {
    for (let x = 0; x < r.w; x++) { put(c, r, r.x + x, r.y, C.gloveLight); put(c, r, r.x + x, r.y + 1, C.gloveMid); }
  }
  for (let y = 0; y < r.h; y++) { put(c, r, r.x, r.y + y, C.gloveDark); put(c, r, r.x + r.w - 1, r.y + y, C.gloveLight); }
}

/** 长裤：黑呢 + 前中线一道细折痕。 */
function pTrouser(c, r, o = {}) {
  const seed = o.seed ?? 361;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.trouserMid;
    if (n < 0.18) col = C.trouserDark;
    else if (n > 0.90) col = C.suitLight;
    put(c, r, r.x + x, r.y + y, col);
  }
  if (o.crease && r.w > 2) {
    const cx = r.x + Math.round(r.w / 2);
    for (let y = 0; y < r.h; y++) {
      put(c, r, cx, r.y + y, C.suitLight);
      put(c, r, cx - 1, r.y + y, C.trouserDark);
    }
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.trouserDark);
}

/** 皮鞋：漆皮，上缘亮 + 鞋头一条高光带。 */
function pShoe(c, r, o = {}) {
  const seed = o.seed ?? 371;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.shoeMid;
    if (n < 0.18) col = C.shoeDark;
    else if (n > 0.92) col = mix(C.shoeMid, C.suitHigh, 0.7);
    put(c, r, r.x + x, r.y + y, col);
  }
  // 上缘（鞋口）一圈亮线 + 鞋面高光
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, mix(C.suitHigh, C.bonePeak, 0.35));
  const hy = r.y + Math.max(1, Math.round(r.h * 0.45));
  for (let x = Math.round(r.w * 0.2); x < Math.round(r.w * 0.8); x++) {
    if (hash(x, hy, seed + 3) > 0.35) put(c, r, r.x + x, hy, mix(C.suitHigh, C.bonePeak, 0.25));
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.sole);
}

/** 鞋底：深灰 + 横向纹（o.tread）。 */
function pSole(c, r, o = {}) {
  const seed = o.seed ?? 381;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    put(c, r, r.x + x, r.y + y, n < 0.2 ? C.shoeDark : n > 0.85 ? mix(C.sole, C.suitHigh, 0.5) : C.sole);
  }
  if (o.tread) for (let y = 1; y < r.h; y += 3) for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + y, C.shoeDark);
}

/** 尾巴毛：短毛束，尖端提亮。 */
function pTail(c, r, o = {}) {
  const seed = o.seed ?? 391;
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) {
    const n = hash(x, y, seed);
    let col = C.furMid;
    if (n < 0.20) col = C.furDark;
    else if (n > 0.74) col = C.furLight;
    else if (n > 0.94) col = C.furHigh;
    put(c, r, r.x + x, r.y + y, col);
  }
  // 毛束：每 2 列一束，向下收尖
  for (let x = 0; x < r.w; x += 2) {
    const len = 1 + Math.floor(hash(x, 0, seed + 3) * 3);
    for (let y = r.h - len; y < r.h; y++) put(c, r, r.x + x, r.y + y, C.furDark);
  }
  for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.furHigh);
}

/** 金色小件（纽扣/袖扣/链扣）：纯金 + 1px 高光 + 1px 暗边。 */
function pGold(c, r, o = {}) {
  for (let y = 0; y < r.h; y++) for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + y, C.metal);
  if (r.w > 1 && r.h > 1) {
    put(c, r, r.x, r.y, C.metalHigh);
    put(c, r, r.x + 1, r.y, C.metalHigh);
    put(c, r, r.x, r.y + 1, mix(C.metal, C.metalHigh, 0.5));
    put(c, r, r.x + r.w - 1, r.y + r.h - 1, C.metalDark);
  }
}

// ---------------------------------------------------------------- 共享小矩形（隐藏面）
// 隐藏面也必须有 UV 且不透明（契约 §4），统一指向这些纯色小矩形，省下大量贴图空间。
const SHARED_DEFS = {
  suitDark: C.suitDark,
  suitMid: C.suitMid,
  lining: C.liningDark,
  leather: C.leatherDark,
  metal: C.metal,
  metalDark: C.metalDark,
  boneDark: C.boneDark,
  boneLight: C.boneHigh,
  shirtDark: C.shirtDark,
  trouser: C.trouserDark,
  shoeDark: C.shoeDark,
  sole: C.sole,
  vestDark: C.vestDark,
  tieDark: C.tieDark,
  furDark: C.furDark,
  gloveDark: C.gloveDark,
  gloveMid: C.gloveMid,
  void: rgb('#050506')
};
const SH = (name) => ({ sh: name });

// ---------------------------------------------------------------- 骨骼契约（§2 v2）
// v2 重构（用户要求）：把「一个骨骼塞一堆 cube」改成「三层骨骼 + 语义分组」。
// 关键点：**重挂立方体不改几何** —— bedrock/Blockbench 的 cube 坐标是工程空间绝对值，
// 骨骼 origin 只是旋转轴心，所以换父骨骼不会移动任何像素（下面自检会逐项比对证明这一点）。
// 轴心（origin）全部落在解剖学关节上：吻部根在颅骨前壁、下颌铰链在下颌后上沿、
// 耳根在颅骨侧壁 x=±3.0、角根在两段夹角起点、爪锚点在手心底部。
const BONES = [
  { name: 'root', parent: null, pivot: [0, 0, 0] },
  { name: 'body', parent: 'root', pivot: [0, 12, 0] },

  // -- body 的次级容器：袍襟 / 腰带 / 肩甲（各自独立摆动，身体动画不用再管这些 cube）
  { name: 'robe_placket', parent: 'body', pivot: [0, 22.8, -3.2] },   // 胸襟：上沿挂在胸口，可下摆摆动
  { name: 'belt', parent: 'body', pivot: [0, 13.95, 0] },             // 腰带 + 金扣
  { name: 'shoulder_right', parent: 'body', pivot: [4.0, 22.75, 0] }, // 肩甲：轴心在躯干侧壁与袖顶的交线
  { name: 'shoulder_left', parent: 'body', pivot: [-4.0, 22.75, 0] },

  // -- 头：head 保留为总闸（旧动画只认这个名字），下面按语义分组
  { name: 'head', parent: 'body', pivot: [0, 24, 0] },
  { name: 'skull', parent: 'head', pivot: [0, 24, 0] },               // 颅骨 + 脖子，与 head 同心：歪头时两者等效
  { name: 'brow_ridge', parent: 'head', pivot: [0, 30.6, -3.2] },     // 眉骨：轴心在后下沿，绕 X 抬起眉弓
  { name: 'muzzle_base', parent: 'head', pivot: [0, 27.8, -3.45] },   // 吻部根：轴心在颅骨前壁接缝
  { name: 'muzzle_tip', parent: 'muzzle_base', pivot: [0, 27.4, -5.8] }, // 吻尖：两段式，可做嗅/咧嘴
  { name: 'jaw', parent: 'head', pivot: [0, 25.3, -3.0] },            // 下颌：铰链在下颌后上沿，绕 X 即张嘴
  { name: 'horns_group', parent: 'head', pivot: [0, 28.0, 1.85] },    // 双角总成：轴心在两角根中点
  { name: 'ears_group', parent: 'head', pivot: [0, 28.5, 0.25] },     // 双耳总成
  { name: 'ear_right', parent: 'ears_group', pivot: [2.95, 28.5, 0.25] },  // 右耳根（颅骨侧壁）
  { name: 'ear_left', parent: 'ears_group', pivot: [-2.95, 28.5, 0.25] },  // 左耳根

  // -- 手臂：claw_anchor 是空骨骼，给「黑焰」粒子/挂件当锚点（不要给它写旋转）
  { name: 'right_arm', parent: 'body', pivot: [5, 22, 0] },
  { name: 'right_forearm', parent: 'right_arm', pivot: [5, 18, 0] },
  { name: 'right_claw_anchor', parent: 'right_forearm', pivot: [6.9, 8.0, 0] },
  { name: 'left_arm', parent: 'body', pivot: [-5, 22, 0] },
  { name: 'left_forearm', parent: 'left_arm', pivot: [-5, 18, 0] },
  { name: 'left_claw_anchor', parent: 'left_forearm', pivot: [-6.9, 8.0, 0] },

  // -- 腿
  { name: 'right_leg', parent: 'body', pivot: [2, 12, 0] },
  { name: 'right_shin', parent: 'right_leg', pivot: [2, 6, 0] },
  { name: 'left_leg', parent: 'body', pivot: [-2, 12, 0] },
  { name: 'left_shin', parent: 'left_leg', pivot: [-2, 6, 0] },

  { name: 'robe', parent: 'body', pivot: [0, 12, 0] }
];
const BONE_PIVOT = Object.fromEntries(BONES.map((b) => [b.name, b.pivot]));

// ---------------------------------------------------------------- 立方体定义
const CUBES = [];
const F = (p, s, o) => ({ p, s, o });

function cube(bone, name, from, to, faces, opts = {}) {
  CUBES.push({
    bone,
    name,
    from,
    to,
    origin: opts.origin ?? BONE_PIVOT[bone],
    rotation: opts.rotation ?? null,
    faces
  });
}

/** 由「角根点 + 方向 + 长度 + 截面」反解出 cube 的 rotation [rx,0,rz]。
 *  R = Rx*Ry*Rz 作用在局部 +Y 上得 dir = (-sin rz, cos rx*cos rz, sin rx*cos rz)。 */
function hornSeg(base, dir, len, width, depth) {
  const norm = Math.hypot(...dir);
  const [dx, dy, dz] = dir.map((v) => v / norm);
  const rz = -Math.asin(Math.max(-1, Math.min(1, dx)));
  const cosrz = Math.cos(rz);
  const rx = Math.atan2(dz / cosrz, dy / cosrz);
  const r4 = (v) => Math.round(v * 10000) / 10000;
  const from = [r4(base[0] - width / 2), r4(base[1]), r4(base[2] - depth / 2)];
  const to = [r4(base[0] + width / 2), r4(base[1] + len), r4(base[2] + depth / 2)];
  const rotation = [r4((rx * 180) / Math.PI), 0, r4((rz * 180) / Math.PI)];
  const next = [r4(base[0] + dx * len), r4(base[1] + dy * len), r4(base[2] + dz * len)];
  return { from, to, origin: base.map(r4), rotation, next };
}

// ============================== v5 比例总纲（参考板实测） ==============================
// 全高 ~31.4（鞋底 0 → 角尖）；实体碰撞箱 1.95 格；坐标：+X = 角色右侧（importer 会镜像），正面 = -Z。
//   腰线 15.45 / 肩线 21.90 / 下巴 23.50 / 眼 26.85 / 颅顶 28.50 / 角尖 ~31.4
//   躯干 6.6 宽；肩（含肩甲）10.8 宽；头 4.6 宽 × 4.2 高 × 4.25 深 + 前伸 4.0 的长吻
// 与 v4 相比：头从 6.0×6.0×6.6 压到 4.6×4.2×4.25（山羊是窄长头颅，不是方砖），
// 长袍换成收腰西装 + 燕尾，多出翻领 / 马甲 / 领带 / 白手套分指 / 尾巴 / 红瞳。

// ============================== body：黑西装躯干 ==============================
// 外套主体 6.6×7.15×5.4；前脸 z=-2.55 会被马甲/领带/翻领盖住，只露翻领外侧那一条呢料。
cube('body', 'torso', [-3.30, 15.45, -2.55], [3.30, 22.60, 2.85], {
  north: F('suitFront', 3.0), south: F('suitBack', 3.0), east: F('suitSide', 2.5), west: F('suitSide', 2.5),
  up: SH('suitDark'), down: SH('suitDark')
});
// 肩窝过渡块：托住头颅、堵住躯干顶面与脖子之间的空当
cube('body', 'neck_yoke', [-2.35, 22.30, -1.95], [2.35, 23.55, 2.15], {
  north: F('suitFront', 3.0), south: F('suitBack', 3.0), east: F('suitSide', 2.5), west: F('suitSide', 2.5),
  up: SH('suitDark'), down: SH('suitDark')
});
// 盘骨/胯部填充块：v4 的裆部是空的，外套开衩后能看穿；v5 用它堵住前开衩与燕尾的开缝
cube('body', 'pelvis', [-1.02, 3.40, -1.70], [1.02, 12.60, 1.70], {
  north: F('trouser', 3.0, { crease: true }), south: F('trouser', 3.0), east: SH('trouser'), west: SH('trouser'),
  up: SH('trouser'), down: SH('trouser')
});
// 立领：后片 + 左右两片（黑呢，比躯干高 1.35，托住头颅）
cube('body', 'collar_back', [-2.15, 22.30, 1.55], [2.15, 23.95, 2.45], {
  south: F('suitBack', 3.5), north: SH('lining'), up: F('suitTop', 3.0), down: SH('suitDark'),
  east: F('suitSide', 3.0), west: F('suitSide', 3.0)
});
cube('body', 'collar_right', [1.30, 22.35, -1.55], [2.20, 23.80, 2.05], {
  east: F('suitSide', 3.0), west: SH('lining'), up: F('suitTop', 3.0), down: SH('suitDark'),
  north: F('suitFront', 3.0), south: F('suitBack', 3.0)
});
cube('body', 'collar_left', [-2.20, 22.35, -1.55], [-1.30, 23.80, 2.05], {
  west: F('suitSide', 3.0), east: SH('lining'), up: F('suitTop', 3.0), down: SH('suitDark'),
  north: F('suitFront', 3.0), south: F('suitBack', 3.0)
});
// 白衬衫胸领（参考板：一圈白领 + 向下的 V 形白衬衫）
cube('body', 'shirt_collar', [-1.32, 22.45, -2.32], [1.32, 23.45, -1.55], {
  north: F('shirtV', 8.0), south: SH('shirtDark'), up: F('shirt', 8.0), down: SH('shirtDark'),
  east: F('shirt', 6.0), west: F('shirt', 6.0)
});
// 马甲：五段阶梯拼出「领口窄 / 胸最宽 / 腰收」的菱形开襟，灰底竖条纹
const VEST = [
  { y0: 20.60, y1: 22.05, hw: 1.00, p: 'vestTop' },
  { y0: 19.35, y1: 20.60, hw: 1.30, p: 'vestMid' },
  { y0: 18.05, y1: 19.35, hw: 1.52, p: 'vestChain' },
  { y0: 16.75, y1: 18.05, hw: 1.40, p: 'vestButtons' },
  { y0: 15.45, y1: 16.75, hw: 1.26, p: 'vestLow' }
];
for (let i = 0; i < VEST.length; i++) {
  const v = VEST[i];
  const z0 = -2.74 + (i % 2) * 0.02;
  cube('body', `vest_${i + 1}`, [-v.hw, v.y0, z0], [v.hw, v.y1, z0 + 0.24], {
    north: F(v.p, 7.0), south: SH('vestDark'), up: SH('vestDark'), down: SH('vestDark'),
    east: F('vestSide', 5.0), west: F('vestSide', 5.0)
  });
}
// 酒红领带：领结 + 下垂的带身
cube('body', 'tie_knot', [-0.64, 21.45, -2.92], [0.64, 22.15, -2.62], {
  north: F('tieKnot', 9.0), south: SH('tieDark'), up: SH('tieDark'), down: SH('tieDark'),
  east: F('tieSide', 7.0), west: F('tieSide', 7.0)
});
cube('body', 'tie_body', [-0.56, 19.10, -2.90], [0.56, 21.50, -2.66], {
  north: F('tie', 8.0), south: SH('tieDark'), up: SH('tieDark'), down: SH('tieDark'),
  east: F('tieSide', 7.0), west: F('tieSide', 7.0)
});
// 胸袋巾：白布叠成的一小条（参考板右胸那抹白）
cube('body', 'pocket_square', [1.95, 19.85, -2.94], [2.78, 20.42, -2.82], {
  north: F('shirt', 10.0), south: SH('shirtDark'), up: F('shirt', 10.0), down: SH('shirtDark'),
  east: SH('shirtDark'), west: SH('shirtDark')
});
// 翻领：每侧 6 段阶梯，内缘由 1.15 外扩到 1.95 再收到 1.50 —— 参考板的「沙漏形开襟」
const LAPEL = [
  { y0: 21.80, y1: 22.55, xin: 1.00 },
  { y0: 20.60, y1: 21.80, xin: 1.34 },
  { y0: 19.45, y1: 20.60, xin: 1.66 },
  { y0: 18.15, y1: 19.45, xin: 1.58 },
  { y0: 16.80, y1: 18.15, xin: 1.44 },
  { y0: 15.48, y1: 16.80, xin: 1.28 }
];
for (const side of ['right', 'left']) {
  const sgn = side === 'right' ? 1 : -1;
  for (let i = 0; i < LAPEL.length; i++) {
    const l = LAPEL[i];
    const z0 = -2.86 + (i % 2) * 0.03;
    const xa = sgn * l.xin, xb = sgn * 2.90;
    cube('robe_placket', `lapel_${side}_${i + 1}`,
      [Math.min(xa, xb), l.y0, z0], [Math.max(xa, xb), l.y1, z0 + 0.31], {
        north: F(side === 'right' ? 'lapelR' : 'lapelL', 4.0), south: SH('suitDark'),
        up: SH('suitDark'), down: SH('suitDark'),
        east: F('lapelEdge', 4.0), west: F('lapelEdge', 4.0)
      });
  }
}
// 腰带 + 背后两粒金扣（参考板背面腰线处两颗金点）
cube('belt', 'belt', [-3.44, 15.20, -2.70], [3.44, 16.15, 3.00], {
  north: F('leather', 5.0), south: F('leather', 5.0), east: F('leather', 5.0), west: F('leather', 5.0),
  up: SH('leather'), down: SH('leather')
});
cube('belt', 'belt_stud_right', [0.78, 15.42, 3.00], [1.18, 15.94, 3.16], {
  south: F('gold', 10.0), north: SH('metal'), up: SH('metal'), down: SH('metalDark'),
  east: SH('metal'), west: SH('metal')
});
cube('belt', 'belt_stud_left', [-1.18, 15.42, 3.00], [-0.78, 15.94, 3.16], {
  south: F('gold', 10.0), north: SH('metal'), up: SH('metal'), down: SH('metalDark'),
  east: SH('metal'), west: SH('metal')
});
// 尾巴：三段短毛束，从燕尾开缝里向后下方甩出（bone body，跟着躯干转）
cube('body', 'tail_1', [-0.55, 12.55, 2.85], [0.55, 14.20, 3.85], {
  south: F('tail', 4.0), north: SH('furDark'), up: F('tail', 4.0), down: SH('furDark'),
  east: F('tail', 4.0), west: F('tail', 4.0)
});
cube('body', 'tail_2', [-0.42, 11.55, 3.70], [0.42, 12.80, 4.70], {
  south: F('tail', 4.0), north: SH('furDark'), up: F('tail', 4.0), down: SH('furDark'),
  east: F('tail', 4.0), west: F('tail', 4.0)
});
cube('body', 'tail_3', [-0.28, 10.65, 4.55], [0.28, 11.80, 5.35], {
  south: F('tail', 4.0), north: SH('furDark'), up: F('tail', 4.0), down: F('tailTip', 4.0),
  east: F('tail', 4.0), west: F('tail', 4.0)
});

// ============================== head：窄长山羊头骨 + 后弯大角 ==============================
cube('skull', 'neck', [-1.05, 21.80, -1.05], [1.05, 24.40, 1.05], {
  north: F('neck', 3.0), south: F('neck', 3.0), east: F('neck', 3.0), west: F('neck', 3.0),
  up: SH('boneDark'), down: SH('boneDark')
});
// 颅骨 4.6 × 4.2 × 4.25：正面画额缝/菱形颧面，侧面画颞骨与眼窝凹陷，顶面画人字缝
cube('skull', 'cranium', [-2.30, 24.30, -2.05], [2.30, 28.50, 2.20], {
  north: F('skullFront', 7.0), south: F('skullBack', 5.0), east: F('skullSide', 5.0, { front: 'right' }),
  west: F('skullSide', 5.0, { front: 'left' }), up: F('skullTop', 5.0), down: SH('boneDark')
});
// 红瞳眼片：凸出颅面 0.37 的斜挑薄片（参考板的眼是「挑起来」的，不是平的）
cube('skull', 'eye_right', [0.60, 26.46, -2.44], [1.98, 27.20, -2.05], {
  north: F('eye', 15.0, { outer: 'l', seed: 401 }), south: SH('boneDark'), up: SH('boneDark'), down: SH('boneDark'),
  east: F('bone', 6.0), west: F('bone', 6.0)
});
cube('skull', 'eye_left', [-1.98, 26.46, -2.44], [-0.60, 27.20, -2.05], {
  north: F('eye', 15.0, { outer: 'r', seed: 403 }), south: SH('boneDark'), up: SH('boneDark'), down: SH('boneDark'),
  east: F('bone', 6.0), west: F('bone', 6.0)
});
// 眉骨：横过双眼上方、向前探出 0.73 的骨棱（压出怒眉的阴影）
cube('brow_ridge', 'brow', [-2.02, 27.36, -2.64], [2.02, 28.08, -1.95], {
  north: F('bone', 6.0), south: SH('boneDark'), up: F('boneTop', 6.0), down: SH('boneDark'),
  east: F('bone', 6.0), west: F('bone', 6.0)
});
// 吻部：三段递降的窄楔（参考板吻部只有 2.1 宽，越前越窄越黑）
cube('muzzle_base', 'muzzle_1', [-1.16, 24.85, -3.70], [1.16, 26.45, -2.05], {
  north: SH('boneLight'), south: SH('boneDark'), up: F('muzzleTop', 5.0), down: F('muzzleUnder', 4.0),
  east: F('muzzleSide', 6.0, { front: 'right' }), west: F('muzzleSide', 6.0, { front: 'left' })
});
cube('muzzle_base', 'muzzle_2', [-1.00, 24.48, -5.00], [1.00, 26.00, -3.60], {
  north: SH('boneLight'), south: SH('boneDark'), up: F('muzzleTop', 5.0), down: F('muzzleUnder', 4.0),
  east: F('muzzleSide', 6.0, { front: 'right' }), west: F('muzzleSide', 6.0, { front: 'left' })
});
cube('muzzle_tip', 'muzzle_3', [-0.86, 24.18, -6.05], [0.86, 25.60, -4.95], {
  north: F('noseFront', 7.0), south: SH('boneDark'), up: F('muzzleTop', 5.0), down: F('muzzleUnder', 4.0),
  east: F('muzzleSide', 6.0, { front: 'right' }), west: F('muzzleSide', 6.0, { front: 'left' })
});
// 鼻子：吻尖正面那块更黑的鼻垫
cube('muzzle_tip', 'nose', [-0.50, 24.55, -6.12], [0.50, 25.25, -5.95], {
  north: F('nosePad', 10.0), south: SH('boneDark'), up: SH('boneDark'), down: SH('boneDark'),
  east: SH('boneDark'), west: SH('boneDark')
});
// 下颌：铰链在 y=25.3（嘴缝），下收成方下巴
cube('jaw', 'jaw', [-0.95, 23.50, -5.30], [0.95, 24.98, -2.30], {
  north: F('jawFront', 5.0), south: SH('boneDark'), up: SH('boneDark'), down: F('jawUnder', 5.0),
  east: F('jawSide', 5.0), west: F('jawSide', 5.0)
});
cube('jaw', 'chin', [-0.78, 23.35, -5.60], [0.78, 24.05, -5.05], {
  north: F('jawFront', 6.0), south: SH('boneDark'), up: SH('boneDark'), down: F('jawUnder', 5.0),
  east: F('jawSide', 5.0), west: F('jawSide', 5.0)
});
cube('jaw', 'fang_right', [0.32, 23.95, -5.34], [0.60, 24.78, -5.10], {
  north: SH('boneLight'), south: SH('boneLight'), up: SH('boneLight'), down: SH('boneLight'),
  east: SH('boneLight'), west: SH('boneLight')
});
cube('jaw', 'fang_left', [-0.60, 23.95, -5.34], [-0.32, 24.78, -5.10], {
  north: SH('boneLight'), south: SH('boneLight'), up: SH('boneLight'), down: SH('boneLight'),
  east: SH('boneLight'), west: SH('boneLight')
});
// 耳朵：每侧两段（根段 + 尖端），向下 14°、向后 10~16°，尖端收细 —— 参考板是横伸的尖耳
cube('ear_right', 'ear_inner_right', [2.05, 25.76, -0.50], [3.32, 26.36, 0.88], {
  north: F('ear', 5.0), south: F('ear', 5.0), up: F('earTop', 5.0), down: F('earUnder', 5.0),
  east: F('earTip', 5.0), west: SH('boneDark')
}, { origin: [2.95, 28.50, 0.25], rotation: [0, -12, -27] });
cube('ear_right', 'ear_outer_right', [3.20, 25.20, -0.20], [4.62, 25.80, 1.02], {
  north: F('ear', 5.0), south: F('ear', 5.0), up: F('earTop', 5.0), down: F('earUnder', 5.0),
  east: F('earTip', 5.0), west: SH('boneDark')
}, { origin: [4.05, 26.05, 0.30], rotation: [0, -18, -36] });
cube('ear_left', 'ear_inner_left', [-3.32, 25.76, -0.50], [-2.05, 26.36, 0.88], {
  north: F('ear', 5.0), south: F('ear', 5.0), up: F('earTop', 5.0), down: F('earUnder', 5.0),
  west: F('earTip', 5.0), east: SH('boneDark')
}, { origin: [-2.95, 28.50, 0.25], rotation: [0, 12, 27] });
cube('ear_left', 'ear_outer_left', [-4.62, 25.20, -0.20], [-3.20, 25.80, 1.02], {
  north: F('ear', 5.0), south: F('ear', 5.0), up: F('earTop', 5.0), down: F('earUnder', 5.0),
  west: F('earTip', 5.0), east: SH('boneDark')
}, { origin: [-4.05, 26.05, 0.30], rotation: [0, 18, 36] });
// 后弯大角：5 段参数化螺旋 —— 先向外上方拔起，中段立起，角尖回钩向内
// （参考板：正面看「外张再内收」，侧面看「向后仰再挑起来」，总高只占 3.3 单位）
const HORN_SPEC = [
  { dir: [0.86, 0.42, 0.30], len: 1.65, w: 2.05, d: 1.95 },
  { dir: [0.80, 0.52, 0.28], len: 1.55, w: 1.75, d: 1.68 },
  { dir: [0.30, 0.92, 0.22], len: 1.25, w: 1.45, d: 1.40 },
  { dir: [-0.55, 0.82, 0.15], len: 1.15, w: 1.10, d: 1.04 },
  { dir: [-0.60, 0.78, 0.10], len: 1.00, w: 0.80, d: 0.76 }
];
const HORN_OVERLAP = 0.40;
function buildHorn(root) {
  const segs = [];
  let base = root.slice();
  for (let i = 0; i < HORN_SPEC.length; i++) {
    const s = HORN_SPEC[i];
    const g = hornSeg(base, s.dir, s.len, s.w, s.d);
    segs.push({ ...g, w: s.w, d: s.d });
    const next = HORN_SPEC[i + 1];
    if (next) {
      const n = Math.hypot(...next.dir);
      const d = next.dir.map((v) => v / n);
      base = g.next.map((v, k) => v - d[k] * HORN_OVERLAP);
    }
  }
  return segs;
}
const HORN_R = buildHorn([1.38, 28.40, 0.66]);
const HORN_L = HORN_R.map((s) => ({
  from: [-s.to[0], s.from[1], s.from[2]],
  to: [-s.from[0], s.to[1], s.to[2]],
  origin: [-s.origin[0], s.origin[1], s.origin[2]],
  rotation: [s.rotation[0], 0, -s.rotation[2]],
  w: s.w,
  d: s.d
}));
for (let i = 0; i < HORN_SPEC.length; i++) {
  const rs = HORN_R[i], ls = HORN_L[i];
  const common = {
    north: F('hornSide', 4.0, { seed: 131 + i * 7, root: i === 0, seg: i }),
    south: F('hornSide', 4.0, { seed: 151 + i * 7, root: i === 0, seg: i }),
    east: F('hornSide', 4.0, { seed: 167 + i * 7, root: i === 0, seg: i }),
    west: F('hornSide', 4.0, { seed: 181 + i * 7, root: i === 0, seg: i }),
    up: F('hornCap', 3.0, { seed: 191 + i }), down: F('hornCap', 3.0, { seed: 193 + i })
  };
  cube('horns_group', `horn_right_${i + 1}`, rs.from, rs.to, common, { origin: rs.origin, rotation: rs.rotation });
  cube('horns_group', `horn_left_${i + 1}`, ls.from, ls.to, common, { origin: ls.origin, rotation: ls.rotation });
}

// v5.9：头部等比放大 1.10（绕颈根 (0, 24.3, 0)）—— 参考板的头/角块面明显比 v5 初版更大，
// 正面才有「山羊头骨顶在西装上」的存在感。羊角不参与缩放（已占满高度预算）。
{
  const HEAD_BONES = new Set(['skull', 'brow_ridge', 'muzzle_base', 'muzzle_tip', 'jaw', 'ear_right', 'ear_left']);
  const cy = 24.3, k = 1.10;
  const sc = (v) => [v[0] * k, cy + (v[1] - cy) * k, v[2] * k];
  for (const cb of CUBES) {
    if (!HEAD_BONES.has(cb.bone)) continue;
    cb.from = sc(cb.from);
    cb.to = sc(cb.to);
    if (cb.origin) cb.origin = sc(cb.origin);
    if (cb.pivot) cb.pivot = sc(cb.pivot);
  }
}

// ============================== 肩甲 + 手臂 + 白手套 ==============================
// 肩甲：参考板最抢眼的是那块「方肩」，比袖子再外扩 0.15，肩宽（含肩甲）10.8 单位
for (const side of ['right', 'left']) {
  const sgn = side === 'right' ? 1 : -1;
  const xa = sgn * 2.70, xb = sgn * 5.78;
  const outerFace = side === 'right' ? 'east' : 'west';
  const innerFace = side === 'right' ? 'west' : 'east';
  cube(`shoulder_${side}`, `pauldron_${side}`,
    [Math.min(xa, xb), 20.30, -2.62], [Math.max(xa, xb), 22.85, 2.62], {
      north: F('suitSide', 3.0), south: F('suitSide', 3.0),
      [outerFace]: F('suitSide', 3.0), [innerFace]: F('suitBack', 3.0),
      up: F('suitTop', 3.0), down: SH('suitDark')
    });
}
for (const side of ['right', 'left']) {
  const sgn = side === 'right' ? 1 : -1;
  const armBone = `${side}_arm`;
  const foreBone = `${side}_forearm`;
  const box = (x0, x1) => [Math.min(sgn * x0, sgn * x1), Math.max(sgn * x0, sgn * x1)];
  // 上臂袖（略粗，参考板的袖子在上臂有鼓起）
  let xs = box(3.46, 5.38);
  cube(armBone, `sleeve_upper_${side}`, [xs[0], 17.90, -2.02], [xs[1], 21.90, 2.02], {
    north: F('suitSide', 3.0), south: F('suitSide', 3.0), east: F('suitSide', 3.0), west: F('suitSide', 3.0),
    up: SH('suitDark'), down: SH('suitDark')
  });
  // 前臂袖（略收）+ 袖口外扩一圈
  xs = box(3.52, 5.32);
  cube(foreBone, `sleeve_fore_${side}`, [xs[0], 13.05, -1.92], [xs[1], 18.05, 1.92], {
    north: F('suitSide', 3.0), south: F('suitSide', 3.0), east: F('suitSide', 3.0), west: F('suitSide', 3.0),
    up: SH('suitDark'), down: SH('suitDark')
  });
  xs = box(3.42, 5.42);
  cube(foreBone, `cuff_${side}`, [xs[0], 12.30, -2.04], [xs[1], 13.25, 2.04], {
    north: F('suitCuff', 3.5), south: F('suitCuff', 3.5), east: F('suitCuff', 3.5), west: F('suitCuff', 3.5),
    up: SH('suitDark'), down: SH('shirtDark')
  });
  // 白手套：掌背 + 四指（指缝画在贴图上）+ 内侧拇指
  xs = box(3.52, 5.48);
  cube(foreBone, `glove_${side}`, [xs[0], 9.95, -2.02], [xs[1], 12.35, 1.96], {
    north: F('glove', 7.0, { gaps: 3, gapTop: 0.34, cuff: true }), south: F('glove', 7.0, { gaps: 2, gapTop: 0.40 }),
    up: F('gloveCuff', 7.0), down: SH('gloveDark'),
    east: F('gloveSide', 6.0), west: F('gloveSide', 6.0)
  });
  const fingerW = 0.375, fingerGap = 0.09;
  for (let f = 0; f < 4; f++) {
    const x0 = 3.60 + f * (fingerW + fingerGap);
    xs = box(x0, x0 + fingerW);
    cube(foreBone, `finger_${side}_${f + 1}`, [xs[0], 8.50, -1.90], [xs[1], 10.06, 1.50], {
      north: F('gloveFinger', 9.0), south: SH('gloveDark'), up: SH('gloveMid'),
      down: F('gloveTip', 9.0), east: F('gloveSide', 8.0), west: F('gloveSide', 8.0)
    });
  }
  xs = box(3.14, 3.66);
  cube(foreBone, `thumb_${side}`, [xs[0], 9.85, -2.30], [xs[1], 11.55, -1.62], {
    north: F('gloveFinger', 8.0), south: F('glove', 7.0, { gaps: 0 }), up: F('gloveCuff', 7.0),
    down: F('gloveTip', 8.0), east: F('gloveSide', 7.0), west: F('gloveSide', 7.0)
  });
}

// ============================== 腿 + 皮鞋 ==============================
for (const side of ['right', 'left']) {
  const sgn = side === 'right' ? 1 : -1;
  const legBone = `${side}_leg`;
  const shinBone = `${side}_shin`;
  const box = (x0, x1) => [Math.min(sgn * x0, sgn * x1), Math.max(sgn * x0, sgn * x1)];
  let xs = box(0.85, 3.15);
  cube(legBone, `thigh_${side}`, [xs[0], 6.00, -1.75], [xs[1], 12.30, 1.75], {
    north: F('trouser', 3.5, { crease: true }), south: F('trouser', 3.0), east: F('trouser', 3.0),
    west: F('trouser', 3.0), up: SH('trouser'), down: SH('trouser')
  });
  xs = box(0.95, 3.05);
  cube(shinBone, `shin_${side}`, [xs[0], 2.70, -1.65], [xs[1], 6.30, 1.65], {
    north: F('trouser', 3.5, { crease: true }), south: F('trouser', 3.0), east: F('trouser', 3.0),
    west: F('trouser', 3.0), up: SH('trouser'), down: SH('trouser')
  });
  // 皮鞋：鞋帮 + 前伸的鞋头 + 鞋底（参考板是黑漆皮、有一点鞋尖）
  xs = box(0.82, 3.18);
  cube(shinBone, `boot_${side}`, [xs[0], 1.18, -2.00], [xs[1], 2.98, 1.96], {
    north: F('shoe', 4.0), south: F('shoe', 4.0), east: F('shoe', 4.0), west: F('shoe', 4.0),
    up: SH('shoeDark'), down: SH('sole')
  });
  xs = box(0.80, 3.20);
  cube(shinBone, `toe_${side}`, [xs[0], 0.52, -3.72], [xs[1], 1.55, 1.98], {
    north: F('shoe', 4.0), south: F('shoe', 4.0), east: F('shoe', 4.0), west: F('shoe', 4.0),
    up: F('shoe', 4.0), down: SH('sole')
  });
  cube(shinBone, `sole_${side}`, [xs[0], 0.00, -3.78], [xs[1], 0.56, 2.04], {
    north: F('sole', 4.0), south: F('sole', 4.0), east: F('sole', 4.0), west: F('sole', 4.0),
    up: SH('sole'), down: F('soleTread', 4.0)
  });
}

// ============================== 燕尾外套下摆（bone robe） ==============================
// 两层：上段贴胯、下段外扩；前中开衩（露黑裤）、背后开缝（露尾巴），燕尾比前摆短
const SKIRT = [
  // 上段 y 10.20~15.45
  { name: 'skirt_up_front_right', o: 'f', x: [1.02, 3.30], y: [10.20, 15.45], z: [-2.90, -2.32] },
  { name: 'skirt_up_front_left', o: 'f', x: [-3.30, -1.02], y: [10.20, 15.45], z: [-2.90, -2.32] },
  { name: 'skirt_up_side_right', o: 'e', x: [3.18, 3.56], y: [10.20, 15.45], z: [-2.90, 2.92] },
  { name: 'skirt_up_side_left', o: 'w', x: [-3.56, -3.18], y: [10.20, 15.45], z: [-2.90, 2.92] },
  { name: 'skirt_up_back_right', o: 'b', x: [0.42, 3.28], y: [10.20, 15.45], z: [2.34, 2.94] },
  { name: 'skirt_up_back_left', o: 'b', x: [-3.28, -0.42], y: [10.20, 15.45], z: [2.34, 2.94] },
  // 下段 y 4.35~10.25（外扩）
  { name: 'skirt_lo_front_right', o: 'f', x: [1.34, 3.98], y: [4.10, 10.25], z: [-3.62, -2.66] },
  { name: 'skirt_lo_front_left', o: 'f', x: [-3.98, -1.34], y: [4.10, 10.25], z: [-3.62, -2.66] },
  { name: 'skirt_lo_side_right', o: 'e', x: [3.34, 3.80], y: [4.10, 10.25], z: [-3.54, 3.70] },
  { name: 'skirt_lo_side_left', o: 'w', x: [-3.80, -3.34], y: [4.10, 10.25], z: [-3.54, 3.70] },
  // 燕尾：比前摆短（到 6.40），中缝留 0.46 给尾巴
  { name: 'skirt_lo_back_right', o: 'b', x: [0.50, 3.98], y: [6.10, 10.25], z: [3.24, 4.06] },
  { name: 'skirt_lo_back_left', o: 'b', x: [-3.98, -0.50], y: [6.10, 10.25], z: [3.24, 4.06] }
];
for (const s of SKIRT) {
  const lo = [s.x[0], s.y[0], s.z[0]], hi = [s.x[1], s.y[1], s.z[1]];
  const faces = { up: SH('suitDark'), down: SH('suitDark') };
  if (s.o === 'f') {
    const inward = s.x[0] >= 0 ? 'l' : 'r'; // 面向中缝的那一列
    faces.north = F('skirtFront', 3.0, { edge: true, bevel: true, vEdge: inward });
    faces.south = F('skirtLining', 3.0);
    faces.east = F('skirtSide', 3.0);
    faces.west = F('skirtSide', 3.0);
  } else if (s.o === 'b') {
    faces.south = F('skirtBack', 3.0, { fold: 0.5 });
    faces.north = F('skirtLining', 3.0);
    faces.east = F('skirtSide', 3.0);
    faces.west = F('skirtSide', 3.0);
  } else if (s.o === 'e') {
    faces.east = F('skirtSide', 3.0, { edge: true });
    faces.west = F('skirtLining', 3.0);
    faces.north = F('skirtSide', 3.0);
    faces.south = F('skirtSide', 3.0);
  } else {
    faces.west = F('skirtSide', 3.0, { edge: true });
    faces.east = F('skirtLining', 3.0);
    faces.north = F('skirtSide', 3.0);
    faces.south = F('skirtSide', 3.0);
  }
  cube('robe', s.name, lo, hi, faces);
}

// ---------------------------------------------------------------- 画笔注册表（v5）
// 每个材质一支画笔；同一个绘制函数按语义拆成多个键（正/背/侧、翻领左右、马甲带链…），
// 参数里带 seed / fold / edge / chain / buttons，避免贴图出现大面积纯色。
const PAINTERS = {
  // -- 骨头（头骨 / 眉骨 / 下颚 / 耳朵 / 角）
  bone: (c, r, o) => pBone(c, r, o),
  claw: pClaw,
  skullTop: pSkullTop,
  boneTop: (c, r, o) => { pBone(c, r, o); for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y, C.bonePeak); },
  skullFront: pSkullFront,
  skullSide: pSkullSide,
  skullBack: pSkullBack,
  muzzleSide: pMuzzleSide,
  muzzleTop: pMuzzleTop,
  muzzleUnder: pMuzzleUnder,
  noseFront: pNoseFront,
  nosePad: (c, r, o) => {
    pBone(c, r, { seed: 411, base: mix(C.boneDark, rgb('#000000'), 0.35) });
    for (let y = 0; y < Math.max(1, Math.round(r.h * 0.55)); y++) {
      for (let x = Math.round(r.w * 0.22); x < Math.round(r.w * 0.78); x++) put(c, r, r.x + x, r.y + y, rgb('#040405'));
    }
  },
  jawSide: pJawSide,
  jawFront: pJawFront,
  jawUnder: pJawUnder,
  ear: pEar,
  earTop: (c, r, o) => pBone(c, r, { ...o, seed: (o.seed ?? 0) + 11 }),
  earUnder: (c, r, o) => pBone(c, r, { ...o, base: mix(C.boneMid, C.boneDark, 0.5) }),
  earTip: (c, r, o) => pBone(c, r, { ...o, base: mix(C.boneMid, C.boneLight, 0.35) }),
  hornSide: pHornSide,
  hornCap: pHornCap,
  eye: pEye,
  neck: pNeck,
  // -- 黑西装
  suitFront: (c, r, o) => pSuit(c, r, { seed: 301, ...o }),
  suitBack: (c, r, o) => pSuit(c, r, { seed: 303, fold: 0.5, ...o }),
  suitSide: (c, r, o) => pSuit(c, r, { seed: 305, ...o }),
  suitTop: (c, r, o) => pSuit(c, r, { seed: 307, ...o }),
  suitCuff: (c, r, o) => pSuit(c, r, { seed: 309, edge: true, ...o }),
  lapelR: (c, r, o) => pLapel(c, r, { seed: 311, inner: 'l', ...o }),
  lapelL: (c, r, o) => pLapel(c, r, { seed: 313, inner: 'r', ...o }),
  lapelEdge: (c, r, o) => pSuit(c, r, { seed: 315, ...o }),
  // -- 下摆（燕尾）
  skirtFront: (c, r, o) => pSuit(c, r, { seed: 331, ...o }),
  skirtBack: (c, r, o) => pSuit(c, r, { seed: 333, ...o }),
  skirtSide: (c, r, o) => pSuit(c, r, { seed: 335, ...o }),
  skirtLining: (c, r, o) => pSuit(c, r, { seed: 337, base: mix(C.suitDark, C.suitMid, 0.55), ...o }),
  // -- 马甲 / 衬衫 / 领带 / 口袋巾
  vestTop: (c, r, o) => pVest(c, r, { seed: 321, ...o }),
  vestMid: (c, r, o) => pVest(c, r, { seed: 322, ...o }),
  vestChain: (c, r, o) => pVest(c, r, { seed: 323, chain: true, studs: true, ...o }),
  vestButtons: (c, r, o) => pVest(c, r, { seed: 324, buttons: true, ...o }),
  vestLow: (c, r, o) => pVest(c, r, { seed: 325, buttons: true, ...o }),
  vestSide: (c, r, o) => pVest(c, r, { seed: 326, ...o }),
  shirt: (c, r, o) => pShirt(c, r, { seed: 331, ...o }),
  shirtV: (c, r, o) => pShirt(c, r, { seed: 333, vneck: true, ...o }),
  tie: (c, r, o) => pTie(c, r, { seed: 341, taper: true, ...o }),
  tieKnot: (c, r, o) => pTie(c, r, { seed: 343, ...o }),
  tieSide: (c, r, o) => pTie(c, r, { seed: 345, ...o }),
  // -- 手套
  glove: (c, r, o) => pGlove(c, r, { seed: 351, ...o }),
  gloveSide: (c, r, o) => pGlove(c, r, { seed: 353, gaps: 0, ...o }),
  gloveFinger: (c, r, o) => pGlove(c, r, { seed: 355, gaps: 1, gapTop: 0.55, ...o }),
  gloveCuff: (c, r, o) => pGlove(c, r, { seed: 357, gaps: 0, cuff: true, ...o }),
  gloveTip: (c, r, o) => {
    pGlove(c, r, { seed: 359, gaps: 0, ...o });
    for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.gloveDark);
  },
  // -- 裤子 / 鞋 / 金饰 / 尾巴
  trouser: (c, r, o) => pTrouser(c, r, { seed: 361, ...o }),
  shoe: (c, r, o) => pShoe(c, r, { seed: 371, ...o }),
  sole: (c, r, o) => pSole(c, r, { seed: 381, ...o }),
  soleTread: (c, r, o) => pSole(c, r, { seed: 383, tread: true, ...o }),
  gold: pGold,
  tail: pTail,
  tailTip: (c, r, o) => {
    pTail(c, r, { seed: 393, ...o });
    for (let x = 0; x < r.w; x++) put(c, r, r.x + x, r.y + r.h - 1, C.furHigh);
  },
  // -- 皮革腰带
  leather: pBelt
};

// ---------------------------------------------------------------- 构建：UV 分配 + 绘制
function buildTexture() {
  const canvas = new Canvas(TEX_W, TEX_H, [...C.bg, 255]);
  const sheet = new Sheet(TEX_W, TEX_H);
  const glow = [];

  // 共享小矩形（隐藏面）
  const shared = {};
  for (const [name, col] of Object.entries(SHARED_DEFS)) {
    const rect = sheet.alloc(4, 4);
    shared[name] = rect;
    for (let y = 0; y < rect.h; y++) for (let x = 0; x < rect.w; x++) put(canvas, rect, rect.x + x, rect.y + y, col);
  }

  // 收集所有面的请求
  const requests = [];
  for (const cb of CUBES) {
    const size = [cb.to[0] - cb.from[0], cb.to[1] - cb.from[1], cb.to[2] - cb.from[2]];
    for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
      const spec = cb.faces[face];
      if (!spec) throw new Error(`立方体 ${cb.name} 缺少 ${face} 面`);
      if (spec.sh) continue;
      const s = spec.s;
      let w, h;
      if (face === 'north' || face === 'south') { w = size[0] * s; h = size[1] * s; }
      else if (face === 'east' || face === 'west') { w = size[2] * s; h = size[1] * s; }
      else { w = size[0] * s; h = size[2] * s; }
      requests.push({ cube: cb, face, w: Math.max(1, Math.round(w)), h: Math.max(1, Math.round(h)), spec });
    }
  }
  requests.sort((a, b) => (b.h - a.h) || (b.w - a.w) || a.cube.name.localeCompare(b.cube.name) || a.face.localeCompare(b.face));
  if (process.env.GOAT_DEBUG) {
    const area = requests.reduce((s, q) => s + (q.w + 1) * (q.h + 1), 0);
    console.log(`[debug] 面请求 ${requests.length} 个，含 padding 面积 ${area} / ${TEX_W * TEX_H}`);
    const byCube = new Map();
    for (const q of requests) byCube.set(q.cube.name, (byCube.get(q.cube.name) ?? 0) + (q.w + 1) * (q.h + 1));
    for (const [k, v] of [...byCube.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)) console.log(`  ${k}: ${v}`);
  }

  const faceRects = new Map();
  for (const req of requests) {
    const rect = sheet.alloc(req.w, req.h);
    faceRects.set(`${req.cube.name}:${req.face}`, rect);
    const painter = PAINTERS[req.spec.p];
    if (!painter) throw new Error(`未知画笔: ${req.spec.p}`);
    painter(canvas, rect, { ...(req.spec.o ?? {}), glow });
  }

  // 把 UV 写回立方体（bbmodel 的 faces.uv = [u1,v1,u2,v2]）
  for (const cb of CUBES) {
    cb.uv = {};
    for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
      const spec = cb.faces[face];
      const rect = spec.sh ? shared[spec.sh] : faceRects.get(`${cb.name}:${face}`);
      if (!rect) throw new Error(`UV 缺失: ${cb.name}.${face}`);
      cb.uv[face] = [rect.x, rect.y, rect.x + rect.w, rect.y + rect.h];
    }
  }
  return { canvas, glow, shared };
}

// ---------------------------------------------------------------- 发光遮罩
function buildGlowmask(glow) {
  const c = new Canvas(TEX_W, TEX_H, [0, 0, 0, 0]);
  for (const g of glow) c.set(g.x, g.y, [g.c[0], g.c[1], g.c[2], 255]);
  return c.toPng();
}

// ---------------------------------------------------------------- bbmodel 组装
const uuidOf = (key) => {
  const h = createHash('sha1').update(`galboss/black_goat_servant/${key}`).digest('hex');
  const s = h.slice(0, 32).split('');
  s[12] = '4';
  s[16] = ((parseInt(s[16], 16) & 0x3) | 0x8).toString(16);
  const hex = s.join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
};

const r4 = (v) => Math.round(v * 10000) / 10000;

function buildBbmodel(texturePng) {
  const elements = CUBES.map((cb) => {
    const el = {
      name: cb.name,
      box_uv: false,
      render_order: 'default',
      locked: false,
      export: true,
      scope: 0,
      allow_mirror_modeling: true,
      from: cb.from.map(r4),
      to: cb.to.map(r4),
      autouv: 0,
      color: 0,
      origin: cb.origin.map(r4),
      faces: {},
      type: 'cube',
      uuid: uuidOf(`cube/${cb.name}`)
    };
    for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
      el.faces[face] = { uv: cb.uv[face].map(r4), texture: 0 };
    }
    if (cb.rotation) el.rotation = cb.rotation.map(r4);
    return el;
  });

  const groups = BONES.map((b) => ({
    name: b.name,
    uuid: uuidOf(`bone/${b.name}`),
    export: true,
    locked: false,
    scope: 0,
    selected: false,
    visibility: true,
    _static: { properties: {}, temp_data: {} },
    origin: b.pivot,
    rotation: [0, 0, 0],
    color: 0,
    children: [],
    reset: false,
    shade: true,
    mirror_uv: false,
    autouv: 0,
    isOpen: false,
    primary_selected: false
  }));
  const groupByBone = new Map(groups.map((g) => [g.name, g]));

  // outliner：按骨骼层级建树，立方体 UUID 字符串挂到所属骨骼组下
  const childrenOf = (boneName) => [
    ...BONES.filter((b) => b.parent === boneName).map((b) => ({
      uuid: groupByBone.get(b.name).uuid,
      isOpen: false,
      children: childrenOf(b.name)
    })),
    ...CUBES.filter((cb) => cb.bone === boneName).map((cb) => uuidOf(`cube/${cb.name}`))
  ];
  const outliner = [{
    uuid: groupByBone.get('root').uuid,
    isOpen: true,
    children: childrenOf('root')
  }];

  return {
    meta: { format_version: '5.0', model_format: 'geckolib_model', box_uv: true },
    name: 'black_goat_servant',
    model_identifier: 'black_goat_servant',
    visible_box: [4, 4, 4],
    variable_placeholders: '',
    multi_file_ruleset: '',
    variable_placeholder_buttons: [],
    timeline_setups: [],
    unhandled_root_fields: {},
    geckolib_modid: 'galboss',
    geckolib_filepath_cache: {},
    ai_used: false,
    ai_agents: 'dsh-goat-model',
    resolution: { width: TEX_W, height: TEX_H },
    elements,
    groups,
    outliner,
    textures: [{
      name: 'black_goat_servant.png',
      folder: '',
      namespace: '',
      id: '0',
      group: '',
      scope: 0,
      width: TEX_W,
      height: TEX_H,
      uv_width: TEX_W,
      uv_height: TEX_H,
      particle: false,
      use_as_default: false,
      layers_enabled: false,
      sync_to_project: '',
      file_format: 'png',
      render_mode: 'default',
      render_sides: 'auto',
      wrap_mode: 'limited',
      pbr_channel: 'color',
      fps: 7,
      frame_time: 1,
      frame_order_type: 'loop',
      frame_order: '',
      frame_interpolate: false,
      visible: true,
      internal: true,
      saved: true,
      uuid: uuidOf('texture/main'),
      source: `data:image/png;base64,${texturePng.toString('base64')}`
    }],
    geckolib_model_type: 'Entity'
  };
}

// ---------------------------------------------------------------- 主流程：生成
function generate() {
  const { canvas, glow } = buildTexture();
  const texturePng = canvas.toPng();
  const glowPng = buildGlowmask(glow);
  const bb = buildBbmodel(texturePng);

  mkdirSync(dirname(OUT_BBMODEL), { recursive: true });
  writeFileSync(OUT_BBMODEL, `${JSON.stringify(bb, null, 1)}\n`);
  mkdirSync(dirname(OUT_GLOW), { recursive: true });
  writeFileSync(OUT_GLOW, glowPng);

  console.log(`写出 ${OUT_BBMODEL.replace(ROOT + '\\', '')}（${(readFileSync(OUT_BBMODEL).length / 1024).toFixed(1)} KB）`);
  console.log(`写出 textures/entity/black_goat_servant_glowmask.png（${glow.length} 个发光像素）`);
  console.log(`骨骼: ${BONES.length}  立方体: ${CUBES.length}  贴图: ${TEX_W}x${TEX_H}`);
  const heights = CUBES.map((c) => c.to[1]);
  console.log(`最高点(未含 cube 旋转): ${Math.max(...heights)}  最低点: ${Math.min(...CUBES.map((c) => c.from[1]))}`);
  const names = new Set(CUBES.map((c) => c.name));
  console.log(`立方体名字唯一: ${names.size === CUBES.length ? '是' : '否！'}`);
}

// ---------------------------------------------------------------- PNG 解码（自检用）
function decodePng(buf) {
  let pos = 8, ihdr = null;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') ihdr = { width: data.readUInt32BE(0), height: data.readUInt32BE(4), bitDepth: data[8], colorType: data[9], interlace: data[12] };
    else if (type === 'IDAT') idat.push(Buffer.from(data));
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (!ihdr) throw new Error('PNG 缺少 IHDR');
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType];
  if (!channels) throw new Error(`不支持的 PNG colorType=${ihdr.colorType}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = ihdr.width * channels;
  const pixels = Buffer.alloc(ihdr.height * stride);
  for (let y = 0; y < ihdr.height; y++) {
    const filter = raw[y * (stride + 1)];
    const src = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    const dst = pixels.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? pixels.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? dst[x - channels] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= channels ? prev[x - channels] : 0;
      let v = src[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      dst[x] = v & 0xff;
    }
  }
  return { width: ihdr.width, height: ihdr.height, channels, pixels };
}

// ---------------------------------------------------------------- 主流程：自检
// 骨架 v2（27 根）。坐标是 Blockbench 空间，importer 会对 X 取反，下面按 geo 空间比较。
const EXPECT_BONES = [
  ['root', null, [0, 0, 0]],
  ['body', 'root', [0, 12, 0]],
  ['robe_placket', 'body', [0, 22.8, -3.2]],
  ['belt', 'body', [0, 13.95, 0]],
  ['shoulder_right', 'body', [4.0, 22.75, 0]],
  ['shoulder_left', 'body', [-4.0, 22.75, 0]],
  ['head', 'body', [0, 24, 0]],
  ['skull', 'head', [0, 24, 0]],
  ['brow_ridge', 'head', [0, 30.6, -3.2]],
  ['muzzle_base', 'head', [0, 27.8, -3.45]],
  ['muzzle_tip', 'muzzle_base', [0, 27.4, -5.8]],
  ['jaw', 'head', [0, 25.3, -3.0]],
  ['horns_group', 'head', [0, 28.0, 1.85]],
  ['ears_group', 'head', [0, 28.5, 0.25]],
  ['ear_right', 'ears_group', [2.95, 28.5, 0.25]],
  ['ear_left', 'ears_group', [-2.95, 28.5, 0.25]],
  ['right_arm', 'body', [5, 22, 0]],
  ['right_forearm', 'right_arm', [5, 18, 0]],
  ['right_claw_anchor', 'right_forearm', [6.9, 8.0, 0]],
  ['left_arm', 'body', [-5, 22, 0]],
  ['left_forearm', 'left_arm', [-5, 18, 0]],
  ['left_claw_anchor', 'left_forearm', [-6.9, 8.0, 0]],
  ['right_leg', 'body', [2, 12, 0]],
  ['right_shin', 'right_leg', [2, 6, 0]],
  ['left_leg', 'body', [-2, 12, 0]],
  ['left_shin', 'left_leg', [-2, 6, 0]],
  ['robe', 'body', [0, 12, 0]]
];
// 头部子树：做「头部特写 geo」时按这张表整棵裁下来（v2 之后头部几何分散在 10 根骨骼里）
const HEAD_SUBTREE = ['head', 'skull', 'brow_ridge', 'muzzle_base', 'muzzle_tip', 'jaw', 'horns_group', 'ears_group', 'ear_right', 'ear_left'];

function verify() {
  const geoPath = join(RES, 'geo', 'black_goat_servant.geo.json');
  const texPath = join(RES, 'textures', 'entity', 'black_goat_servant.png');
  const geo = JSON.parse(readFileSync(geoPath, 'utf8'))['minecraft:geometry'][0];
  const tex = decodePng(readFileSync(texPath));
  let fail = 0;
  const check = (ok, label, detail = '') => {
    console.log(`${ok ? '  OK ' : '  FAIL'} ${label}${detail ? `  ${detail}` : ''}`);
    if (!ok) fail++;
  };

  console.log('\n== geo 骨骼 ==');
  check(geo.description.identifier === 'geometry.black_goat_servant', 'identifier', geo.description.identifier);
  check(geo.description.texture_width === TEX_W && geo.description.texture_height === TEX_H, 'texture_width/height=256',
    `${geo.description.texture_width}x${geo.description.texture_height}`);
  check(geo.bones.length === EXPECT_BONES.length, `骨骼数量 ${EXPECT_BONES.length}`, String(geo.bones.length));
  const byName = new Map(geo.bones.map((b) => [b.name, b]));
  for (const [name, parent, pivot] of EXPECT_BONES) {
    const b = byName.get(name);
    if (!b) { check(false, `骨骼 ${name} 存在`); continue; }
    // 契约 §2 写的是 Blockbench 坐标；importer 会对 X 取反，这里按 geo 空间比较
    const want = [-pivot[0], pivot[1], pivot[2]];
    const pOk = b.pivot.every((v, i) => Math.abs(v - want[i]) < 0.01);
    const parOk = (b.parent ?? null) === parent;
    check(pOk && parOk, `骨骼 ${name}`, `parent=${b.parent ?? '-'} geo pivot=[${b.pivot}]（bb ${JSON.stringify(pivot)}）`);
  }
  const extra = geo.bones.filter((b) => !EXPECT_BONES.some(([n]) => n === b.name));
  check(extra.length === 0, '无多余骨骼', extra.map((b) => b.name).join(',') || '-');
  // 爪锚点是**故意**的空骨骼（挂黑焰粒子用），单列出来免得被当成漏挂几何
  const anchorsOk = ['right_claw_anchor', 'left_claw_anchor'].every((n) => byName.get(n) && !(byName.get(n).cubes ?? []).length);
  check(anchorsOk, '两个 claw_anchor 存在且为空骨骼', 'right_claw_anchor / left_claw_anchor');

  console.log('\n== 立方体 ==');
  let cubeCount = 0;
  for (const b of geo.bones) cubeCount += (b.cubes ?? []).length;
  check(cubeCount >= CUBE_MIN && cubeCount <= CUBE_MAX, `立方体数 ${CUBE_MIN}~${CUBE_MAX}`, String(cubeCount));
  const allCubes = geo.bones.flatMap((b) => (b.cubes ?? []).map((c) => ({ bone: b.name, cube: c })));
  const missingFaces = allCubes.filter(({ cube }) => ['north', 'south', 'east', 'west', 'up', 'down'].some((f) => !cube.uv?.[f]));
  check(missingFaces.length === 0, '每个立方体 6 面都有 UV', missingFaces.map((m) => m.cube.name ?? m.bone).join(',') || '-');

  // 真实包围盒（含 cube rotation）
  const rad = (d) => (d * Math.PI) / 180;
  const mul = (a, b) => a.map((row, i) => b[0].map((_, j) => row.reduce((s, v, k) => s + v * b[k][j], 0)));
  const apply = (m, p) => [
    m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3],
    m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3],
    m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3]
  ];
  const I4 = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
  const T = (t) => [[1, 0, 0, t[0]], [0, 1, 0, t[1]], [0, 0, 1, t[2]], [0, 0, 0, 1]];
  const R = ([rx, ry, rz]) => {
    const [a, b, c] = [rad(rx), rad(ry), rad(rz)];
    const Rx = [[1, 0, 0, 0], [0, Math.cos(a), -Math.sin(a), 0], [0, Math.sin(a), Math.cos(a), 0], [0, 0, 0, 1]];
    const Ry = [[Math.cos(b), 0, Math.sin(b), 0], [0, 1, 0, 0], [-Math.sin(b), 0, Math.cos(b), 0], [0, 0, 0, 1]];
    const Rz = [[Math.cos(c), -Math.sin(c), 0, 0], [Math.sin(c), Math.cos(c), 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
    return mul(mul(Rx, Ry), Rz);
  };
  const aroundPivot = (pivot, rot) => mul(mul(T(pivot), rot), T(pivot.map((v) => -v)));
  let lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const { cube } of allCubes) {
    const m = cube.rotation ? aroundPivot(cube.pivot ?? [0, 0, 0], R(cube.rotation)) : I4;
    for (const dx of [0, cube.size[0]]) for (const dy of [0, cube.size[1]]) for (const dz of [0, cube.size[2]]) {
      const p = apply(m, [cube.origin[0] + dx, cube.origin[1] + dy, cube.origin[2] + dz]);
      for (let i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p[i]); hi[i] = Math.max(hi[i], p[i]); }
    }
  }
  const size = hi.map((v, i) => Math.round((v - lo[i]) * 100) / 100);
  check(size[1] >= 30.8 && size[1] <= 32.2, '模型总高 30.8~32.2', `高=${size[1]}  宽=${size[0]}  深=${size[2]}  y:[${lo[1]},${hi[1]}]`);
  check(Math.abs(lo[1]) < 0.01, '脚底 y=0', String(lo[1]));

  console.log('\n== 贴图 ==');
  check(tex.width === TEX_W && tex.height === TEX_H, '贴图 256x256', `${tex.width}x${tex.height}`);
  let holes = 0;
  const holeList = [];
  for (const { cube, bone } of allCubes) {
    for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
      const uv = cube.uv[face];
      const ua = Math.min(uv.uv[0], uv.uv[0] + uv.uv_size[0]);
      const ub = Math.max(uv.uv[0], uv.uv[0] + uv.uv_size[0]);
      const va = Math.min(uv.uv[1], uv.uv[1] + uv.uv_size[1]);
      const vb = Math.max(uv.uv[1], uv.uv[1] + uv.uv_size[1]);
      for (let y = Math.floor(va); y < Math.ceil(vb); y++) {
        for (let x = Math.floor(ua); x < Math.ceil(ub); x++) {
          const i = (y * tex.width + x) * tex.channels;
          const a = tex.channels === 4 ? tex.pixels[i + 3] : 255;
          if (a !== 255) { holes++; if (holeList.length < 5) holeList.push(`${bone}/${face}@${x},${y}`); }
        }
      }
    }
  }
  check(holes === 0, '所有立方体 6 面 100% 不透明', holes ? `${holes} 个透明像素: ${holeList.join(' ')}` : '0 透明像素');

  console.log('\n== 发光遮罩 ==');
  const glowPath = join(RES, 'textures', 'entity', 'black_goat_servant_glowmask.png');
  const glow = decodePng(readFileSync(glowPath));
  let lit = 0;
  for (let i = 0; i < glow.width * glow.height; i++) if (glow.pixels[i * glow.channels + 3] > 0) lit++;
  check(glow.width === TEX_W && glow.height === TEX_H, 'glowmask 256x256', `${glow.width}x${glow.height}`);
  check(lit > 0 && lit < glow.width * glow.height * 0.05, 'glowmask 只点亮少量像素', `${lit} 像素（${((lit / (glow.width * glow.height)) * 100).toFixed(2)}%）`);

  // 头部特写用 geo（只保留 head 子树），供 pose_tool 放大渲染
  // v2 之后头部几何散在 10 根骨骼里，必须整棵裁下来，只留 head 会得到空模型。
  mkdirSync(PREVIEW, { recursive: true });
  const headGeo = JSON.parse(JSON.stringify(geo));
  headGeo.bones = headGeo.bones.filter((b) => HEAD_SUBTREE.includes(b.name));
  delete headGeo.bones.find((b) => b.name === 'head').parent;
  writeFileSync(join(PREVIEW, 'goat_head.geo.json'), `${JSON.stringify({ format_version: '1.12.0', 'minecraft:geometry': [headGeo] }, null, 2)}\n`);
  console.log(`\n写出 _preview/goat_head.geo.json（头部特写用，${headGeo.bones.length} 根骨骼）`);

  // 贴图放大预览 + 面部特写（自己看贴图用）
  const zoom = 4;
  const big = new Canvas(tex.width * zoom, tex.height * zoom, [0, 0, 0, 255]);
  for (let y = 0; y < tex.height; y++) for (let x = 0; x < tex.width; x++) {
    const i = (y * tex.width + x) * tex.channels;
    const a = tex.channels === 4 ? tex.pixels[i + 3] : 255;
    const col = [tex.pixels[i], tex.pixels[i + 1], tex.pixels[i + 2], a];
    for (let dy = 0; dy < zoom; dy++) for (let dx = 0; dx < zoom; dx++) big.set(x * zoom + dx, y * zoom + dy, col);
  }
  writeFileSync(join(PREVIEW, 'goat_texture_x4.png'), big.toPng());

  // 颅骨正面 UV 区放大 8 倍，方便肉眼核对金瞳（颅骨 = skull 骨骼下 6.0×6.0×6.6 的那个立方体）
  const skullCube = allCubes.find(({ cube }) => cube.name === 'cranium');
  console.log(fail === 0 ? '\n== 全部检查通过 ==' : `\n== ${fail} 项检查失败 ==`);
  if (skullCube) {
    const uv = skullCube.cube.uv.north;
    const ua = Math.min(uv.uv[0], uv.uv[0] + uv.uv_size[0]), ub = Math.max(uv.uv[0], uv.uv[0] + uv.uv_size[0]);
    const va = Math.min(uv.uv[1], uv.uv[1] + uv.uv_size[1]), vb = Math.max(uv.uv[1], uv.uv[1] + uv.uv_size[1]);
    const z = 8;
    const face = new Canvas((ub - ua) * z, (vb - va) * z, [0, 0, 0, 255]);
    for (let y = va; y < vb; y++) for (let x = ua; x < ub; x++) {
      const i = (y * tex.width + x) * tex.channels;
      const col = [tex.pixels[i], tex.pixels[i + 1], tex.pixels[i + 2], 255];
      for (let dy = 0; dy < z; dy++) for (let dx = 0; dx < z; dx++) face.set((x - ua) * z + dx, (y - va) * z + dy, col);
    }
    writeFileSync(join(PREVIEW, 'goat_face_skull.png'), face.toPng());
    console.log(`写出 _preview/goat_texture_x4.png 与 _preview/goat_face_skull.png`);
  }
  if (fail) process.exit(1);
}

// ---------------------------------------------------------------- 游戏空间 + 带贴图离线渲染
// 为什么要这一步：Blockbench 导出的 bedrock geo 是「compile 空间」，GeckoLib 载入时会做一次
// X 镜像（GeckoLib 4.4.2 BakedModelFactory.constructCube：origin.x=-(x+w)、pivot.x*=-1、
// rot=(-x,-y,z)；constructBone 同理）。对没有 cube rotation 的模型，两者只差一个左右镜像
// （对称模型看不出来）；但羊角有 cube rotation，直接把 geo 当模型空间渲染会把角画歪/画断。
// 这里按 GeckoLib 的规则还原成游戏空间，pose_tool 与自带渲染器都吃这一份。
const M_I4 = () => [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
const rad = (d) => (d * Math.PI) / 180;
const M_MUL = (a, b) => a.map((row, i) => b[0].map((_, j) => row.reduce((s, v, k) => s + v * b[k][j], 0)));
const M_APPLY = (m, p) => [
  m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3],
  m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3],
  m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3]
];
const M_T = (t) => [[1, 0, 0, t[0]], [0, 1, 0, t[1]], [0, 0, 1, t[2]], [0, 0, 0, 1]];
const M_R = ([rx, ry, rz]) => {
  const [a, b, c] = [rad(rx), rad(ry), rad(rz)];
  const Rx = [[1, 0, 0, 0], [0, Math.cos(a), -Math.sin(a), 0], [0, Math.sin(a), Math.cos(a), 0], [0, 0, 0, 1]];
  const Ry = [[Math.cos(b), 0, Math.sin(b), 0], [0, 1, 0, 0], [-Math.sin(b), 0, Math.cos(b), 0], [0, 0, 0, 1]];
  const Rz = [[Math.cos(c), -Math.sin(c), 0, 0], [Math.sin(c), Math.cos(c), 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
  return M_MUL(M_MUL(Rx, Ry), Rz);
};
const M_AROUND = (pivot, rot) => M_MUL(M_MUL(M_T(pivot), rot), M_T(pivot.map((v) => -v)));

/** geo（compile 空间）→ 游戏空间（等价于 GeckoLib 载入时的 X 镜像）。 */
function toGameSpace(geo) {
  const g = JSON.parse(JSON.stringify(geo));
  for (const b of g.bones) {
    b.pivot = [-b.pivot[0], b.pivot[1], b.pivot[2]];
    if (b.rotation) b.rotation = [-b.rotation[0], -b.rotation[1], b.rotation[2]];
    for (const c of b.cubes ?? []) {
      const ox = c.origin[0], sx = c.size[0];
      c.origin = [-ox - sx, c.origin[1], c.origin[2]];
      if (c.pivot) c.pivot = [-c.pivot[0], c.pivot[1], c.pivot[2]];
      if (c.rotation) c.rotation = [-c.rotation[0], -c.rotation[1], c.rotation[2]];
    }
  }
  return g;
}

/** 把 geo 限制到某根骨骼（头部特写用）。 */
function subsetGeo(geo, boneName) {
  // v2：骨骼是三层树，只留同名骨会得到空的头。这里按 parent 关系递归收集整棵子树，
  // 再把子树顶端的 parent 摘掉（否则父子引用悬空）。
  const g = JSON.parse(JSON.stringify(geo));
  const keep = new Set([boneName]);
  for (let grew = true; grew;) {
    grew = false;
    for (const b of g.bones) {
      if (b.parent && keep.has(b.parent) && !keep.has(b.name)) { keep.add(b.name); grew = true; }
    }
  }
  g.bones = g.bones.filter((b) => keep.has(b.name));
  const rootOfSubset = g.bones.find((b) => b.name === boneName);
  if (rootOfSubset) delete rootOfSubset.parent;
  return g;
}

const VIEW_CAMS = {
  front: { right: [1, 0, 0], up: [0, 1, 0], view: [0, 0, 1] },
  side: { right: [0, 0, -1], up: [0, 1, 0], view: [-1, 0, 0] },
  three: { right: [0.86, 0, -0.5], up: [0, 1, 0], view: [0.5, 0, 0.86] }
};
const RENDER_LIGHT = (() => { const v = [-0.35, 0.62, -0.7]; const l = Math.hypot(...v); return v.map((x) => x / l); })();
/** 逐面明暗系数（近似 Minecraft 实体渲染：上最亮、侧面次之、底面最暗），
 *  再叠一点点方向光让相邻面有区分。v4 原来正面只有 0.42，黑西装在预览里全糊成一块。 */
function faceLight(n) {
  const f = Math.abs(n[1]);           // 1 = 水平面
  let base;
  if (n[1] > 0.5) base = 1.00;        // up
  else if (n[1] < -0.5) base = 0.60;  // down
  else if (Math.abs(n[2]) > 0.5) base = 0.88; // north/south
  else base = 0.72;                   // east/west
  const dir = 0.10 * Math.max(0, -dot3(n, RENDER_LIGHT));
  return Math.min(1.12, base + dir + 0.04 * f);
}
const dot3 = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

const FACE_CORNERS = {
  north: (o, s) => [[o[0] + s[0], o[1], o[2]], [o[0], o[1], o[2]], [o[0], o[1] + s[1], o[2]], [o[0] + s[0], o[1] + s[1], o[2]]],
  south: (o, s) => [[o[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0], o[1] + s[1], o[2] + s[2]]],
  west: (o, s) => [[o[0], o[1], o[2] + s[2]], [o[0], o[1], o[2]], [o[0], o[1] + s[1], o[2]], [o[0], o[1] + s[1], o[2] + s[2]]],
  east: (o, s) => [[o[0] + s[0], o[1], o[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2]]],
  up: (o, s) => [[o[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2]], [o[0], o[1] + s[1], o[2]]],
  down: (o, s) => [[o[0], o[1], o[2]], [o[0] + s[0], o[1], o[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0], o[1], o[2] + s[2]]]
};

/** 面内某点 → 贴图 UV（像素）。约定与 MC 贴图一致：
 *  north 的 u 朝 -X、south 的 u 朝 +X、east 的 u 朝 -Z、west 的 u 朝 +Z；v 一律向下。 */
function faceUvAt(face, p, o, s, uv) {
  const ua = Math.min(uv.uv[0], uv.uv[0] + uv.uv_size[0]);
  const va = Math.min(uv.uv[1], uv.uv[1] + uv.uv_size[1]);
  const du = Math.abs(uv.uv_size[0]), dv = Math.abs(uv.uv_size[1]);
  const x0 = o[0], y0 = o[1], z0 = o[2], x1 = o[0] + s[0], y1 = o[1] + s[1], z1 = o[2] + s[2];
  const fx = s[0] ? (p[0] - x0) / s[0] : 0;
  const fy = s[1] ? (y1 - p[1]) / s[1] : 0;
  const fz = s[2] ? (p[2] - z0) / s[2] : 0;
  switch (face) {
    case 'north': return [ua + (1 - fx) * du, va + fy * dv];
    case 'south': return [ua + fx * du, va + fy * dv];
    case 'east': return [ua + (1 - fz) * du, va + fy * dv];
    case 'west': return [ua + fz * du, va + fy * dv];
    case 'up': return [ua + (1 - fx) * du, va + fz * dv];
    default: return [ua + fx * du, va + (1 - fz) * dv];
  }
}

/** 带贴图的软件光栅器（正交、2x 超采样、最近邻采样、逐面 lambert）。 */
function renderTextured(geo, tex, outPath, view, opts = {}) {
  const size = opts.size ?? 900;
  const SS = 2;
  const px = size * SS;
  const cam = VIEW_CAMS[view];
  const byName = new Map(geo.bones.map((b) => [b.name, b]));
  const worldCache = new Map();
  const resolve = (bone, depth = 0) => {
    if (worldCache.has(bone.name)) return worldCache.get(bone.name);
    const pivot = bone.pivot ?? [0, 0, 0];
    const rot = M_R(bone.rotation ?? [0, 0, 0]);
    const local = M_AROUND(pivot, rot);
    const parent = bone.parent ? byName.get(bone.parent) : null;
    const m = parent && depth < 64 ? M_MUL(resolve(parent, depth + 1), local) : local;
    worldCache.set(bone.name, m);
    return m;
  };
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const toCam = (p) => [dot(p, cam.right), dot(p, cam.up), dot(p, cam.view)];

  const quads = [];
  for (const bone of geo.bones) {
    if (!bone.cubes) continue;
    const bm = resolve(bone);
    for (const cube of bone.cubes) {
      const cm = cube.rotation ? M_MUL(bm, M_AROUND(cube.pivot ?? [0, 0, 0], M_R(cube.rotation))) : bm;
      const o = cube.origin, s = cube.size;
      for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
        const uv = cube.uv?.[face];
        if (!uv) continue;
        const pts = FACE_CORNERS[face](o, s).map((p) => M_APPLY(cm, p));
        const uvs = FACE_CORNERS[face](o, s).map((p) => faceUvAt(face, p, o, s, uv));
        const e1 = [pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]];
        const e2 = [pts[3][0] - pts[0][0], pts[3][1] - pts[0][1], pts[3][2] - pts[0][2]];
        const n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
        const nl = Math.hypot(...n) || 1;
        quads.push({ pts, uvs, n: n.map((v) => v / nl) });
      }
    }
  }

  // 自动取景
  const proj = quads.flatMap((q) => q.pts.map(toCam));
  let mnx = Infinity, mxx = -Infinity, mny = Infinity, mxy = -Infinity;
  for (const p of proj) { mnx = Math.min(mnx, p[0]); mxx = Math.max(mxx, p[0]); mny = Math.min(mny, p[1]); mxy = Math.max(mxy, p[1]); }
  const spanX = Math.max(0.001, mxx - mnx), spanY = Math.max(0.001, mxy - mny);
  const scale = Math.min(px / (spanX * 1.12), px / (spanY * 1.12));
  const cx = (mnx + mxx) / 2, cy = (mny + mxy) / 2;

  const color = Buffer.alloc(px * px * 4);
  const depth = new Float32Array(px * px).fill(Infinity);
  for (let y = 0; y < px; y++) for (let x = 0; x < px; x++) {
    const i = (y * px + x) * 4;
    color[i] = 20; color[i + 1] = 22; color[i + 2] = 30; color[i + 3] = 255;
  }
  const sample = (u, v) => {
    const x = Math.min(tex.width - 1, Math.max(0, Math.floor(u)));
    const y = Math.min(tex.height - 1, Math.max(0, Math.floor(v)));
    const i = (y * tex.width + x) * tex.channels;
    const a = tex.channels === 4 ? tex.pixels[i + 3] : 255;
    return a < 16 ? null : [tex.pixels[i], tex.pixels[i + 1], tex.pixels[i + 2]];
  };

  for (const q of quads) {
    const nCam = [dot(q.n, cam.right), dot(q.n, cam.up), dot(q.n, cam.view)];
    if (nCam[2] > 0) continue; // 背面剔除
    const lit = faceLight(q.n);
    const scr = q.pts.map((p) => {
      const c = toCam(p);
      return [px / 2 + (c[0] - cx) * scale, px / 2 - (c[1] - cy) * scale, c[2]];
    });
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      const [a, b, c] = tri.map((i) => scr[i]);
      const [ua, ub, uc] = tri.map((i) => q.uvs[i]);
      const minX = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0])));
      const maxX = Math.min(px - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
      const minY = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1])));
      const maxY = Math.min(px - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
      const den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
      if (Math.abs(den) < 1e-9) continue;
      for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
        const w0 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / den;
        const w1 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / den;
        const w2 = 1 - w0 - w1;
        if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
        const z = w0 * a[2] + w1 * b[2] + w2 * c[2];
        const o = y * px + x;
        if (z >= depth[o]) continue;
        const u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0];
        const v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
        const col = sample(u, v);
        if (!col) continue;
        depth[o] = z;
        const i = o * 4;
        color[i] = Math.min(255, col[0] * lit);
        color[i + 1] = Math.min(255, col[1] * lit);
        color[i + 2] = Math.min(255, col[2] * lit);
      }
    }
  }
  // 2x 超采样降采样
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let r = 0, g = 0, b = 0;
    for (let dy = 0; dy < SS; dy++) for (let dx = 0; dx < SS; dx++) {
      const i = ((y * SS + dy) * px + (x * SS + dx)) * 4;
      r += color[i]; g += color[i + 1]; b += color[i + 2];
    }
    const i = (y * size + x) * 4;
    out[i] = r / (SS * SS); out[i + 1] = g / (SS * SS); out[i + 2] = b / (SS * SS); out[i + 3] = 255;
  }
  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, encodePngRgba(size, size, out));
  console.log(`写出 ${outPath.replace(ROOT + '\\', '')}`);
}

/** 独立 PNG 编码（RGBA8），避免依赖 pose_tool 内部实现。 */
function encodePngRgba(w, h, rgba) {
  const crcTable = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
    return t;
  })();
  const crc32 = (buf) => { let c = -1; for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))
  ]);
}

/** 预览：导出游戏空间 geo（给 pose_tool 用）+ 自带贴图渲染出 final 图。 */
function preview() {
  const geoPath = join(RES, 'geo', 'black_goat_servant.geo.json');
  const texPath = join(RES, 'textures', 'entity', 'black_goat_servant.png');
  const raw = JSON.parse(readFileSync(geoPath, 'utf8'))['minecraft:geometry'][0];
  const geo = toGameSpace(raw);
  const tex = decodePng(readFileSync(texPath));
  mkdirSync(PREVIEW, { recursive: true });
  writeFileSync(join(PREVIEW, 'goat_game.geo.json'), `${JSON.stringify({ format_version: '1.12.0', 'minecraft:geometry': [geo] }, null, 2)}\n`);
  writeFileSync(join(PREVIEW, 'goat_head.geo.json'), `${JSON.stringify({ format_version: '1.12.0', 'minecraft:geometry': [subsetGeo(geo, 'head')] }, null, 2)}\n`);
  console.log('写出 _preview/goat_game.geo.json（游戏空间，供 pose_tool 渲染/穿模体检）');
  for (const view of ['front', 'side', 'three']) {
    renderTextured(geo, tex, join(PREVIEW, `goat_final2_${view}.png`), view, { size: 900 });
  }
  renderTextured(subsetGeo(geo, 'head'), tex, join(PREVIEW, 'goat_final2_head.png'), 'three', { size: 800 });
  renderTextured(subsetGeo(geo, 'head'), tex, join(PREVIEW, 'goat_final2_head_front.png'), 'front', { size: 800 });
}

// ---------------------------------------------------------------- 入口
const mode = process.argv[2];
if (mode === '--verify') verify();
else if (mode === '--preview') preview();
else generate();
