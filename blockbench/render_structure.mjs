import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { Canvas } from './png_write.mjs';

// 把结构 .nbt 画成预览图（不用进游戏）：
//   1) 每一层的俯视图横向拼成一张长图
//   2) 沿 Z 投影的剖面图（看楼层高度与楼梯）
// 用法：node render_structure.mjs <结构.nbt> <输出前缀> [每格像素=2]

const nbtFile = process.argv[2];
const outPrefix = process.argv[3] || '_preview/structure';
const SCALE = Number(process.argv[4] || 2);

const root = new R(zlib.gunzipSync(fs.readFileSync(nbtFile))).root();
const [sx, sy, sz] = root.size;
const palette = root.palette;

// 方块 → 颜色（按名字里的关键字，够用就行）
const RULES = [
  [/glass/, [140, 200, 235, 200]],
  [/black_concrete/, [72, 72, 80]],
  [/white_concrete/, [232, 233, 235]],
  [/light_gray_concrete/, [160, 161, 167]],
  [/quartz/, [235, 232, 225]],
  [/smooth_stone/, [158, 158, 158]],
  [/andesite/, [132, 134, 133]],
  [/brick/, [150, 88, 72]],
  [/bookshelf/, [110, 78, 50]],
  [/lectern|loom/, [168, 128, 76]],
  [/door|trapdoor|stairs|slab|plank|log/, [150, 110, 70]],
  [/leaves|grass_block|vine/, [70, 130, 60]],
  [/dirt/, [110, 80, 60]],
  [/cauldron/, [90, 120, 170]],
  [/flower_pot/, [186, 124, 92]],
  [/sea_lantern|lantern|glowstone/, [250, 240, 180]],
  [/redstone_block/, [200, 30, 30]],
  [/stone/, [130, 130, 130]],
];
const colorOf = (name) => {
  for (const [re, c] of RULES) if (re.test(name)) return c;
  return [230, 60, 230];   // 未映射 → 洋红，提醒我补规则
};

// 网格：grid[y][z][x] = palette 下标（-1 = 空气）
const grid = new Map();
for (const b of root.blocks) {
  const [x, y, z] = b.pos;
  let layer = grid.get(y);
  if (!layer) { layer = new Map(); grid.set(y, layer); }
  layer.set(z * sx + x, b.state);
}
const airIdx = new Set();
palette.forEach((p, i) => { if (p.Name.endsWith('air')) airIdx.add(i); });

// ---------------- 1. 各层俯视图（网格拼图） ----------------
const COLUMNS = Number(process.argv[5] || 6);
const GAP = 2;
const layers = [...grid.keys()].sort((a, b) => a - b);
const rows = Math.ceil(layers.length / COLUMNS);
const cellW = sx * SCALE, cellH = sz * SCALE;
const canvas = new Canvas(COLUMNS * (cellW + GAP) + GAP, rows * (cellH + GAP) + GAP, [20, 20, 26, 255]);
layers.forEach((y, li) => {
  const layer = grid.get(y);
  const ox = GAP + (li % COLUMNS) * (cellW + GAP);
  const oy = GAP + Math.floor(li / COLUMNS) * (cellH + GAP);
  for (let x = 0; x < sx; x++) {
    for (let z = 0; z < sz; z++) {
      const st = layer.get(z * sx + x);
      if (st === undefined || airIdx.has(st)) continue;
      const c = colorOf(palette[st].Name);
      for (let dy = 0; dy < SCALE; dy++) for (let dx = 0; dx < SCALE; dx++) canvas.set(ox + x * SCALE + dx, oy + z * SCALE + dy, c);
    }
  }
});
const layersFile = `${outPrefix}_layers.png`;
fs.mkdirSync(outPrefix.replace(/[^/\\]*$/, '') || '.', { recursive: true });
fs.writeFileSync(layersFile, canvas.toPng());

// ---------------- 2. 剖面：在 z 方向切几刀看楼层 ----------------
// 取固定 z 的 x-y 切面（不是"最外层墙面投影"，那样只能看到外墙）
const CUTS = [Math.floor(sz / 2), Math.floor(sz / 4), Math.floor(sz * 3 / 4)];
const SECTION_SCALE = Math.max(2, SCALE);
const section = new Canvas(CUTS.length * (sx * SECTION_SCALE + 4) + 4, sy * SECTION_SCALE + 8, [20, 20, 26, 255]);
CUTS.forEach((cz, ci) => {
  const ox = 4 + ci * (sx * SECTION_SCALE + 4);
  for (let x = 0; x < sx; x++) {
    for (let y = 0; y < sy; y++) {
      const layer = grid.get(y);
      if (!layer) continue;
      const st = layer.get(cz * sx + x);
      if (st === undefined || airIdx.has(st)) continue;
      const c = colorOf(palette[st].Name);
      for (let dy = 0; dy < SECTION_SCALE; dy++) {
        for (let dx = 0; dx < SECTION_SCALE; dx++) {
          section.set(ox + x * SECTION_SCALE + dx, 4 + (sy - 1 - y) * SECTION_SCALE + dy, c);
        }
      }
    }
  }
});
const sectionFile = `${outPrefix}_section.png`;
fs.writeFileSync(sectionFile, section.toPng());

console.log(`结构 ${sx} x ${sy} x ${sz}，非空层 ${layers.length}`);
console.log(`  各层俯视图 ${layersFile}  (${canvas.w} x ${canvas.h})`);
console.log(`  剖面图     ${sectionFile}  (${section.w} x ${section.h})`);
console.log(`层序号（俯视图从左到右）：y = ${layers.join(', ')}`);
