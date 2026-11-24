import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { Canvas } from './png_write.mjs';

/**
 * 为 chess_arena.nbt 生成两张拼在一起的预览图：
 *   左侧：棋盘俯视图（标出棋子位置与中央 Boss 区）
 *   右侧：3D 等距体素透视图（看清每枚棋子的立体造型）
 */

const nbtFile = process.argv[2] || 'src/main/resources/data/galboss/structures/chess_arena.nbt';
const outFile = process.argv[3] || 'chess_arena_preview.png';

const root = new R(zlib.gunzipSync(fs.readFileSync(nbtFile))).root();
const [sx, sy, sz] = root.size;
const palette = root.palette;

const COLORS = [
  [/quartz_block|smooth_quartz/, [238, 236, 230]],
  [/quartz_pillar/, [216, 212, 204]],
  [/gold_block/, [242, 198, 56]],
  [/gilded_blackstone/, [168, 128, 48]],
  [/chiseled_polished_blackstone/, [54, 52, 62]],
  [/polished_blackstone/, [38, 36, 44]],
  [/polished_deepslate/, [68, 70, 78]],
  [/arena_white/, [215, 215, 218]],
  [/arena_black/, [46, 46, 54]],
  [/arena_gray/, [125, 125, 132]],
  [/arena_deepslate/, [72, 74, 82]],
  [/arena_stone/, [145, 145, 150]],
];

function colorOf(name) {
  for (const [re, c] of COLORS) if (re.test(name)) return c;
  return [220, 60, 220];
}

const airIdx = new Set();
palette.forEach((p, i) => { if (p.Name.endsWith('air')) airIdx.add(i); });

const grid = new Map();
const nonAir = [];
for (const b of root.blocks) {
  if (airIdx.has(b.state)) continue;
  const [x, y, z] = b.pos;
  grid.set(`${x},${y},${z}`, b.state);
  nonAir.push({ x, y, z, state: b.state });
}

const W = 980, H = 500;
const canvas = new Canvas(W, H, [22, 24, 30, 255]);

// 1. 左侧：俯视图（最高非空气层）
const TOP_OX = 20, TOP_OY = 40, TOP_SCALE = 6;
for (let x = 0; x < sx; x++) {
  for (let z = 0; z < sz; z++) {
    let topState = null;
    let topY = -1;
    for (let y = sy - 1; y >= 0; y--) {
      const st = grid.get(`${x},${y},${z}`);
      if (st !== undefined) { topState = st; topY = y; break; }
    }
    if (topState === null) continue;
    const base = colorOf(palette[topState].Name);
    const shade = topY > 0 ? Math.min(1.18, 0.85 + topY * 0.03) : 1.0;
    const c = base.map((v) => Math.min(255, Math.round(v * shade)));
    for (let dy = 0; dy < TOP_SCALE; dy++) {
      for (let dx = 0; dx < TOP_SCALE; dx++) {
        canvas.set(TOP_OX + x * TOP_SCALE + dx, TOP_OY + z * TOP_SCALE + dy, c);
      }
    }
  }
}

// 标记中心 Boss 点 (32.5, 32.5)
const bx = TOP_OX + Math.round(32.5 * TOP_SCALE);
const bz = TOP_OY + Math.round(32.5 * TOP_SCALE);
for (let d = -4; d <= 4; d++) {
  canvas.set(bx + d, bz, [230, 50, 70]);
  canvas.set(bx, bz + d, [230, 50, 70]);
}

// 2. 右侧：3D 等距体素渲染（painter's algorithm: 从后往前 x+z 升序, y 升序）
nonAir.sort((a, b) => (a.x + a.z) - (b.x + b.z) || a.y - b.y);

const ISO_OX = 690, ISO_OY = 175;
const TX = 3.6, TY = 1.8, TZ = 4.2;

function fillPoly(pts, c) {
  let minY = Infinity, maxY = -Infinity;
  for (const [, y] of pts) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
  for (let y = Math.floor(minY); y <= Math.ceil(maxY); y++) {
    const xs = [];
    for (let i = 0; i < pts.length; i++) {
      const [x1, y1] = pts[i], [x2, y2] = pts[(i + 1) % pts.length];
      if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
        xs.push(x1 + ((y - y1) * (x2 - x1)) / (y2 - y1));
      }
    }
    xs.sort((a, b) => a - b);
    for (let i = 0; i < xs.length; i += 2) {
      const xa = Math.floor(xs[i]), xb = Math.ceil(xs[i + 1]);
      for (let x = xa; x <= xb; x++) canvas.set(x, y, c);
    }
  }
}

for (const { x, y, z, state } of nonAir) {
  // 跳过最外圈低矮围墙的前半边以免遮挡，保留后半边作背景
  if (y >= 1 && (x === sx - 1 || z === sz - 1)) continue;
  const rx = x - sx / 2, rz = z - sz / 2;
  const px = ISO_OX + (rx - rz) * TX;
  const py = ISO_OY + (rx + rz) * TY - y * TZ;
  const base = colorOf(palette[state].Name);
  const topC = base;
  const leftC = base.map((v) => Math.round(v * 0.78));
  const rightC = base.map((v) => Math.round(v * 0.62));

  // 顶面
  fillPoly([
    [px, py - TY],
    [px + TX, py],
    [px, py + TY],
    [px - TX, py],
  ], topC);

  if (y > 0) {
    // 左面
    fillPoly([
      [px - TX, py],
      [px, py + TY],
      [px, py + TY + TZ],
      [px - TX, py + TZ],
    ], leftC);
    // 右面
    fillPoly([
      [px, py + TY],
      [px + TX, py],
      [px + TX, py + TZ],
      [px, py + TY + TZ],
    ], rightC);
  }
}

fs.writeFileSync(outFile, canvas.toPng());
console.log(`已生成预览图: ${outFile} (${W}x${H})`);
