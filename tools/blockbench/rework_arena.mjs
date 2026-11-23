import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { W } from './nbt_write.mjs';

// 把「棋局竞技场」从「6 层石基座上的高台」改成「下沉 1 格的棋盘坑 + 1 格石沿」（一次性改造）：
//
//   旧布局（66 x 20 x 66）            新布局（66 x 14 x 66）
//   y 0..5   arena_stone 基座          y 0   棋盘 64x64 + 外圈灰包边（坑底）
//   y 6      棋盘 + 外圈灰包边         y 1   外圈灰包边 → 抬 1 格 = 石沿
//   y 7..8   深板岩墙（四角开口）      y 2..3 深板岩墙
//   y 9      白墙帽                   y 4   白墙帽
//   y 10..19 air                      y 5..13 air
//
// 结果：棋盘面比石沿低 1 格（用户要的「低周围一格」），底部不再有石台。
//
// 用法：node rework_arena.mjs <chess_arena.nbt>
// 幂等保护：只认 20 高的旧模板，重复执行会报错退出。

const nbtFile = process.argv[2];
if (!nbtFile) {
  console.error('用法: node rework_arena.mjs <chess_arena.nbt>');
  process.exit(1);
}

const raw = zlib.gunzipSync(fs.readFileSync(nbtFile));
const t = new R(raw, true).root();

const bs = (s) => ({ t: 8, v: s });
const bi = (v) => ({ t: 3, v: v });
const bd = (v) => ({ t: 6, v: v });

const [sx, sy, sz] = t.size.v.map((n) => n.v);
if (sy !== 20) {
  console.error(`这个模板已经是 ${sx}x${sy}x${sz}，不是未改造的 66x20x66，停止。`);
  process.exit(1);
}

const OLD_BOARD_Y = 6;      // 旧模板的棋盘层
const SHIFT = OLD_BOARD_Y;  // 整体下移 6 格
const NEW_H = sy - SHIFT;   // 14
const NEW_BOARD_Y = 0;
const RIM_Y = 1;            // 石沿：比棋盘面高 1 格
const WALL_LIFT = 1;        // 墙整体再抬 1 格，站在石沿上

const nameOf = (pe) => pe.v.Name.v;
const palIndex = new Map();
t.palette.v.forEach((pe, i) => palIndex.set(nameOf(pe), i));
for (const need of ['minecraft:air', 'galboss:arena_gray']) {
  if (!palIndex.has(need)) {
    console.error(`palette 里缺 ${need}，停止。`);
    process.exit(1);
  }
}
const AIR = palIndex.get('minecraft:air');
const GRAY = palIndex.get('galboss:arena_gray');

// ---------------- blocks：删石基座、整体下移、丢掉旧 air ----------------
const kept = [];
let dropped = 0;
for (const b of t.blocks.v) {
  const p = b.v.pos.v;
  const y = p[1].v;
  if (y < OLD_BOARD_Y) { dropped++; continue; }              // 6 层 arena_stone
  if (b.v.state.v === AIR) continue;                          // 旧空气稍后统一重建
  const ny = y - SHIFT;
  kept.push({ x: p[0].v, y: ny >= RIM_Y ? ny + WALL_LIFT : ny, z: p[2].v, state: b.v.state.v });
}
console.log(`删掉基座 ${dropped} 格，保留真实方块 ${kept.length} 格（墙整体抬 ${WALL_LIFT} 格）`);

const tile = new Set(kept.map((b) => `${b.x},${b.y},${b.z}`));
const blocks = kept.map((b) => ({
  t: 10,
  v: {
    pos: { t: 9, v: [bi(b.x), bi(b.y), bi(b.z)] },
    state: { t: 3, v: b.state },
  },
}));

const push = (x, y, z, state) => {
  const k = `${x},${y},${z}`;
  if (tile.has(k)) return false;
  tile.add(k);
  blocks.push({ t: 10, v: { pos: { t: 9, v: [bi(x), bi(y), bi(z)] }, state: { t: 3, v: state } } });
  return true;
};

// ---------------- 石沿：外圈灰包边整圈抬到 y=1 ----------------
let rim = 0;
for (let z = 0; z < sz; z++) {
  for (let x = 0; x < sx; x++) {
    const edge = x === 0 || z === 0 || x === sx - 1 || z === sz - 1;
    if (edge && push(x, RIM_Y, z, GRAY)) rim++;
  }
}
console.log(`石沿：新增 ${rim} 格灰包边（y=${RIM_Y}）`);

// ---------------- air：整个 66x66 占地从 y=1 往上清空 ----------------
let air = 0;
for (let y = RIM_Y; y < NEW_H; y++) {
  for (let z = 0; z < sz; z++) {
    for (let x = 0; x < sx; x++) {
      if (push(x, y, z, AIR)) air++;
    }
  }
}
console.log(`空气：补 ${air} 格（y ${RIM_Y}..${NEW_H - 1}，整块占地）`);

t.blocks.v = blocks;
t.size.v = [bi(sx), bi(NEW_H), bi(sz)];

// ---------------- entities：贝阿朵莉切站在棋盘面上（脚部 y = 1） ----------------
for (const e of t.entities?.v || []) {
  const p = e.v?.pos?.v;
  if (p) p[1] = bd(NEW_BOARD_Y + 1);
  const bp = e.v?.blockPos?.v;
  if (bp) bp[1] = bi(NEW_BOARD_Y);
}
console.log(`实体：${(t.entities?.v || []).length} 个，脚部 y = ${NEW_BOARD_Y + 1}`);

// ---------------- 写回 ----------------
const w = new W();
w.u8(10); w.str('');
w.tag(9, 'size'); w.value(t.size);
w.tag(9, 'entities'); w.u8(10); w.i32(t.entities.v.length);
for (const e of t.entities.v) w.value(e);
w.tag(9, 'blocks'); w.u8(10); w.i32(t.blocks.v.length);
for (const b of t.blocks.v) w.value(b);
w.tag(9, 'palette'); w.u8(10); w.i32(t.palette.v.length);
for (const p of t.palette.v) w.value(p);
w.tag(t.DataVersion.t, 'DataVersion'); w.value(t.DataVersion);
w.end();

const out = w.build();
fs.writeFileSync(nbtFile, zlib.gzipSync(out, { level: 9 }));
console.log(`写出 ${nbtFile}  ${(fs.statSync(nbtFile).size / 1024).toFixed(1)} KB（未压缩 ${(out.length / 1024 / 1024).toFixed(2)} MB）`);
