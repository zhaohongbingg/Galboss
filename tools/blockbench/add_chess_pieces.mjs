import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { W } from './nbt_write.mjs';

/**
 * 在「棋局竞技场」结构（66x14x66）中摆放国际象棋棋子。
 * 棋盘：x=1..64, z=1..64 共 8x8 格，每格 8x8 方块；y=0 为地板，y=1..12 为内部空间。
 * 中心 2x2 格（col 3..4, row 3..4）留空给贝阿朵莉切与玩家战斗。
 *
 * 用法：node add_chess_pieces.mjs [chess_arena.nbt]
 */

const nbtFile = process.argv[2] || 'src/main/resources/data/galboss/structures/chess_arena.nbt';
const FLOOR_TOP = 1;

const raw = zlib.gunzipSync(fs.readFileSync(nbtFile));
const t = new R(raw, true).root();

const bs = (s) => ({ t: 8, v: s });
const bi = (v) => ({ t: 3, v });

const [sx, sy, sz] = t.size.v.map((n) => n.v);

// ---------------- palette ----------------
const palKey = (pe) => {
  let k = pe.v.Name.v;
  if (pe.v.Properties) {
    const props = Object.fromEntries(Object.entries(pe.v.Properties.v).map(([p, v]) => [p, v.v]));
    k += '|' + Object.keys(props).sort().map((n) => `${n}=${props[n]}`).join(',');
  }
  return k;
};
const palIndex = new Map();
t.palette.v.forEach((pe, i) => palIndex.set(palKey(pe), i));

function getBlockState(name, props = null) {
  let key = name;
  if (props) {
    key += '|' + Object.keys(props).sort().map((n) => `${n}=${props[n]}`).join(',');
  }
  let idx = palIndex.get(key);
  if (idx === undefined) {
    idx = t.palette.v.length;
    const entry = { Name: bs(name) };
    if (props) {
      const propComp = {};
      for (const [k, v] of Object.entries(props)) propComp[k] = bs(v);
      entry.Properties = { t: 10, v: propComp };
    }
    t.palette.v.push({ t: 10, v: entry });
    palIndex.set(key, idx);
  }
  return idx;
}

const AIR_IDX = getBlockState('minecraft:air');
const CHEST_SOUTH = getBlockState('minecraft:chest', { facing: 'south', type: 'single', waterlogged: 'false' });
const CHEST_NORTH = getBlockState('minecraft:chest', { facing: 'north', type: 'single', waterlogged: 'false' });
const END_LOOT_TABLE = 'minecraft:chests/end_city_treasure';

const MATS = {
  white: {
    main: getBlockState('minecraft:quartz_block'),
    pillar: getBlockState('minecraft:quartz_pillar'),
    trim: getBlockState('minecraft:smooth_quartz'),
    accent: getBlockState('minecraft:gold_block'),
  },
  black: {
    main: getBlockState('minecraft:polished_blackstone'),
    pillar: getBlockState('minecraft:polished_deepslate'),
    trim: getBlockState('minecraft:chiseled_polished_blackstone'),
    accent: getBlockState('minecraft:gilded_blackstone'),
  },
};

// 先建当前方块表（保留 y=0 地板、外墙、以及非棋盘区域），内部 y=1..12 清回空气再摆棋子
const blockMap = new Map();
for (const b of t.blocks.v) {
  const [x, y, z] = b.v.pos.v.map((n) => n.v);
  const isInside = x >= 1 && x <= sx - 2 && z >= 1 && z <= sz - 2 && y >= FLOOR_TOP && y < sy - 1;
  if (isInside) {
    blockMap.set(`${x},${y},${z}`, AIR_IDX);
  } else {
    blockMap.set(`${x},${y},${z}`, b.v.state.v);
  }
}

function setLocal(x, y, z, state) {
  if (x < 1 || x >= sx - 1 || z < 1 || z >= sz - 1 || y < FLOOR_TOP || y >= sy) return;
  blockMap.set(`${x},${y},${z}`, state);
}

// ---------------- 棋子体素生成（6x6 占地，居中于 8x8 棋格） ----------------
function fillRect(ox, oy, oz, x0, x1, y, z0, z1, state, chamfer = false) {
  for (let x = x0; x <= x1; x++) {
    for (let z = z0; z <= z1; z++) {
      if (chamfer && (x === x0 || x === x1) && (z === z0 || z === z1)) continue;
      setLocal(ox + x, oy + y, oz + z, state);
    }
  }
}

function buildPiece(col, row, type, color, facing = 1) {
  const m = MATS[color];
  const ox = 1 + col * 8 + 1; // 8x8 格内留 1 格边距，居中放 6x6
  const oz = 1 + row * 8 + 1;
  const oy = FLOOR_TOP;
  const fz = (z) => (facing >= 0 ? z : 5 - z);

  // 共用底座：y=0 6x6 切角，y=1 4x4
  fillRect(ox, oy, oz, 0, 5, 0, 0, 5, m.trim, true);
  fillRect(ox, oy, oz, 1, 4, 1, 1, 4, m.main, false);

  if (type === 'pawn') {
    fillRect(ox, oy, oz, 2, 3, 2, 2, 3, m.pillar);
    fillRect(ox, oy, oz, 2, 3, 3, 2, 3, m.pillar);
    fillRect(ox, oy, oz, 1, 4, 4, 1, 4, m.main, true);
    fillRect(ox, oy, oz, 2, 3, 5, 2, 3, m.trim);
  } else if (type === 'rook') {
    for (let y = 2; y <= 5; y++) {
      fillRect(ox, oy, oz, 1, 4, y, 1, 4, m.pillar, true);
    }
    fillRect(ox, oy, oz, 0, 5, 6, 0, 5, m.main, true);
    // 城垛四角
    for (const [cx, cz] of [[0, 0], [1, 0], [0, 1], [4, 0], [5, 0], [5, 1], [0, 4], [0, 5], [1, 5], [4, 5], [5, 4], [5, 5]]) {
      setLocal(ox + cx, oy + 7, oz + cz, m.trim);
    }
    for (const [cx, cz] of [[0, 0], [5, 0], [0, 5], [5, 5]]) {
      setLocal(ox + cx, oy + 7, oz + cz, m.accent);
    }
  } else if (type === 'knight') {
    // 马身 + 拱颈 + 向前探出的马头 + 双耳
    for (let y = 2; y <= 3; y++) {
      for (let x = 2; x <= 3; x++) {
        for (let z = 1; z <= 4; z++) setLocal(ox + x, oy + y, oz + fz(z), m.main);
      }
    }
    for (let y = 4; y <= 5; y++) {
      for (let x = 2; x <= 3; x++) {
        for (let z = 1; z <= 3; z++) setLocal(ox + x, oy + y, oz + fz(z), m.pillar);
      }
      // 鬃毛点缀
      setLocal(ox + 2, oy + y, oz + fz(1), m.accent);
      setLocal(ox + 3, oy + y, oz + fz(1), m.accent);
    }
    // 马头与吻部（朝向 +facing）
    for (let y = 6; y <= 7; y++) {
      for (let x = 2; x <= 3; x++) {
        for (let z = 2; z <= 4; z++) setLocal(ox + x, oy + y, oz + fz(z), m.main);
      }
    }
    setLocal(ox + 2, oy + 6, oz + fz(5), m.trim);
    setLocal(ox + 3, oy + 6, oz + fz(5), m.trim);
    // 双耳
    setLocal(ox + 1, oy + 8, oz + fz(2), m.accent);
    setLocal(ox + 4, oy + 8, oz + fz(2), m.accent);
  } else if (type === 'bishop') {
    for (let y = 2; y <= 4; y++) {
      fillRect(ox, oy, oz, 2, 3, y, 2, 3, m.pillar);
    }
    fillRect(ox, oy, oz, 1, 4, 5, 1, 4, m.accent, true);
    fillRect(ox, oy, oz, 1, 4, 6, 1, 4, m.main, true);
    fillRect(ox, oy, oz, 1, 4, 7, 1, 4, m.main, true);
    // 主教帽斜切口
    setLocal(ox + 4, oy + 7, oz + fz(3), AIR_IDX);
    setLocal(ox + 4, oy + 7, oz + fz(4), AIR_IDX);
    fillRect(ox, oy, oz, 2, 3, 8, 2, 3, m.main);
    fillRect(ox, oy, oz, 2, 3, 9, 2, 3, m.accent);
  } else if (type === 'queen') {
    for (let y = 2; y <= 5; y++) {
      fillRect(ox, oy, oz, 2, 3, y, 2, 3, m.pillar);
    }
    fillRect(ox, oy, oz, 1, 4, 6, 1, 4, m.accent, true);
    fillRect(ox, oy, oz, 1, 4, 7, 1, 4, m.main, true);
    fillRect(ox, oy, oz, 1, 4, 8, 1, 4, m.main, true);
    // 王冠尖角 + 宝珠
    for (const [cx, cz] of [[0, 0], [5, 0], [0, 5], [5, 5], [2, 0], [3, 0], [2, 5], [3, 5], [0, 2], [0, 3], [5, 2], [5, 3]]) {
      setLocal(ox + cx, oy + 9, oz + cz, m.accent);
    }
    fillRect(ox, oy, oz, 2, 3, 9, 2, 3, m.main);
    fillRect(ox, oy, oz, 2, 3, 10, 2, 3, m.accent);
  } else if (type === 'king') {
    for (let y = 2; y <= 5; y++) {
      fillRect(ox, oy, oz, 1, 4, y, 1, 4, m.pillar, true);
    }
    fillRect(ox, oy, oz, 1, 4, 6, 1, 4, m.accent, true);
    fillRect(ox, oy, oz, 1, 4, 7, 1, 4, m.main, true);
    fillRect(ox, oy, oz, 1, 4, 8, 1, 4, m.main, true);
    // 十字王冠（y=9..11）
    fillRect(ox, oy, oz, 2, 3, 9, 2, 3, m.accent);
    fillRect(ox, oy, oz, 1, 4, 10, 2, 3, m.accent);
    fillRect(ox, oy, oz, 2, 3, 11, 2, 3, m.accent);
  }
}

// 残局对峙布置：保留中央 2x2 格（col 3..4, row 3..4）给贝阿朵莉切与玩家战斗
const PIECES = [
  // 黑方（北侧，朝南 +Z）
  { col: 0, row: 0, type: 'rook', color: 'black', facing: 1 },
  { col: 4, row: 0, type: 'king', color: 'black', facing: 1 },
  { col: 3, row: 1, type: 'queen', color: 'black', facing: 1 },
  { col: 1, row: 2, type: 'knight', color: 'black', facing: 1 },
  // 白方（南侧，朝北 -Z）
  { col: 4, row: 7, type: 'king', color: 'white', facing: -1 },
  { col: 3, row: 6, type: 'queen', color: 'white', facing: -1 },
  { col: 5, row: 5, type: 'bishop', color: 'white', facing: -1 },
  { col: 2, row: 5, type: 'pawn', color: 'white', facing: -1 },
];

for (const p of PIECES) {
  buildPiece(p.col, p.row, p.type, p.color, p.facing);
}

// 将 4 个末地城宝藏等级的宝箱隐藏埋入棋子内部（双方王、后核心 y=FLOOR_TOP+1，外部完全不可见）
const CHEST_PIECES = [
  { col: 4, row: 0, state: CHEST_SOUTH }, // 黑王
  { col: 3, row: 1, state: CHEST_SOUTH }, // 黑后
  { col: 4, row: 7, state: CHEST_NORTH }, // 白王
  { col: 3, row: 6, state: CHEST_NORTH }, // 白后
];
const blockNbtMap = new Map();
for (const c of CHEST_PIECES) {
  const cx = 1 + c.col * 8 + 1 + 2; // 棋子 6x6 内部中心 (dx=2, dy=1, dz=2)
  const cy = FLOOR_TOP + 1;
  const cz = 1 + c.row * 8 + 1 + 2;
  setLocal(cx, cy, cz, c.state);
  blockNbtMap.set(`${cx},${cy},${cz}`, {
    t: 10,
    v: {
      id: bs('minecraft:chest'),
      LootTable: bs(END_LOOT_TABLE),
    },
  });
}

// 写回 blocks
const outBlocks = [];
for (const [key, state] of blockMap.entries()) {
  const [x, y, z] = key.split(',').map(Number);
  const blockComp = {
    pos: { t: 9, v: [bi(x), bi(y), bi(z)] },
    state: { t: 3, v: state },
  };
  const extraNbt = blockNbtMap.get(key);
  if (extraNbt) blockComp.nbt = extraNbt;
  outBlocks.push({ t: 10, v: blockComp });
}
t.blocks.v = outBlocks;

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
console.log(`已写入 ${PIECES.length} 枚国际象棋棋子与 ${CHEST_PIECES.length} 个隐藏末地宝箱到 ${nbtFile}（总方块 ${outBlocks.length}）`);
