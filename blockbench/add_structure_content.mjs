import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { W } from './nbt_write.mjs';

// 往教学楼结构里塞「战利品箱子」和「待机 boss 实体」。可重复运行（每次先清掉旧的）。
//
// 用法：node add_structure_content.mjs <结构.nbt> [--no-chests] [--no-bosses]
//
// 布局（从结构量出来的）：6 层楼，每层 18 间教室 = 3 翼 × 6 间。
//   - 楼板在 y 0/5/10/15/20/25，玩家站的地面一层是 y 1/6/11/16/21/26（桌子也在这一层）
//   - 教室空腔在 y 2/3（每层 15x17 左右）
//   - 中间翼的教室中心 x≈69，6 间的中心 z = 30/48/66/84/102/120

const LAYER_STEP = 5;          // 每层楼高 5
const GROUND_OFFSET = 1;       // 玩家层 = 楼板 + 1
const LOOT_TABLE = 'minecraft:chests/end_city_treasure';   // 末地城宝箱
const BOSS_WING_CX = 69;       // 中间翼
const BOSS_ROOM_CZ = 66;       // 6 间里正中间那间
const ROOM_MIN = 100, ROOM_MAX = 900;

const BOSSES = [
  { id: 'galboss:tadasugawa_rei', name: '学生 · 礼', color: 'yellow' },
  { id: 'galboss:reizein_tohka', name: '会长 · 桐香', color: 'gold' },
  { id: 'galboss:onabuta_ikuko', name: '大田生 · 郁子', color: 'red' },
];

const nbtFile = process.argv[2];
const wantChests = !process.argv.includes('--no-chests');
const wantBosses = !process.argv.includes('--no-bosses');
if (!nbtFile) { console.error('用法: node add_structure_content.mjs <结构.nbt>'); process.exit(1); }

// ---------------- 读（typed 保留原始类型，方便原样写回） ----------------
const raw = zlib.gunzipSync(fs.readFileSync(nbtFile));
const t = new R(raw, true).root();          // 字段值是 {t, v}
const flat = new R(raw).root();             // 同一份数据读成普通值，方便算几何
const [sx, sy, sz] = flat.size;

const num = (node) => node.v;
const filled = new Set();
for (const b of flat.blocks) filled.add(b.pos.join(','));
const open = (x, y, z) => x >= 0 && z >= 0 && x < sx && z < sz && !filled.has(`${x},${y},${z}`);

// ---------------- 找教室：每层玩家层上的连通空腔 ----------------
function roomsAt(y) {
  const seen = new Set();
  const out = [];
  for (let x = 0; x < sx; x++) {
    for (let z = 0; z < sz; z++) {
      if (seen.has(x + ',' + z) || !open(x, y, z)) continue;
      seen.add(x + ',' + z);
      const stack = [[x, z]];
      const cells = [];
      let minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9, touch = false;
      while (stack.length) {
        const [cx, cz] = stack.pop();
        cells.push([cx, cz]);
        if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
        if (cz < minZ) minZ = cz; if (cz > maxZ) maxZ = cz;
        if (cx === 0 || cz === 0 || cx === sx - 1 || cz === sz - 1) touch = true;
        for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          const nx = cx + dx, nz = cz + dz, k = nx + ',' + nz;
          if (seen.has(k) || !open(nx, y, nz)) continue;
          seen.add(k);
          stack.push([nx, nz]);
        }
      }
      if (touch || cells.length < ROOM_MIN || cells.length > ROOM_MAX) continue;
      out.push({ y, minX, maxX, minZ, maxZ, cells, cx: Math.round((minX + maxX) / 2), cz: Math.round((minZ + maxZ) / 2) });
    }
  }
  return out;
}

const rooms = [];
for (let y = GROUND_OFFSET; y < sy; y += LAYER_STEP) rooms.push(...roomsAt(y));
const byFloor = new Map();
for (const r of rooms) {
  if (!byFloor.has(r.y)) byFloor.set(r.y, 0);
  byFloor.set(r.y, byFloor.get(r.y) + 1);
}
console.log(`教室：${rooms.length} 间（${[...byFloor.entries()].map(([y, n]) => `y${y}:${n}间`).join(' ')}）`);

// 房间成员集合，用于「就近找空格」
const roomKey = (r) => new Set(r.cells.map(([x, z]) => x + ',' + z));
function nearestFree(r, x, z) {
  const keys = roomKey(r);
  if (keys.has(x + ',' + z)) return [x, z];
  let best = null, bestD = 1e9;
  for (const [cx, cz] of r.cells) {
    const d = (cx - x) * (cx - x) + (cz - z) * (cz - z);
    if (d < bestD) { bestD = d; best = [cx, cz]; }
  }
  return best;
}

// ---------------- 清掉旧的箱子 / boss（保证可重复运行） ----------------
const oldBlocks = t.blocks.v.length;
const oldEntities = (t.entities?.v || []).length;
const isChest = (stateIdx) => {
  const name = flat.palette[stateIdx].Name;
  return name === 'minecraft:chest' || name === 'minecraft:trapped_chest';
};
const bossIds = new Set(BOSSES.map(b => b.id));
t.blocks.v = t.blocks.v.filter(b => !isChest(num(b.v.state)));
if (t.entities) t.entities.v = t.entities.v.filter(e => !bossIds.has(e.v.nbt?.v?.id?.v));
let removed = oldBlocks - t.blocks.v.length;

// ---------------- 调色板：找/建箱子条目 ----------------
function paletteIndex(name, props) {
  const want = name + '|' + Object.keys(props).sort().map(k => k + '=' + props[k]).join(',');
  for (let i = 0; i < t.palette.v.length; i++) {
    const e = t.palette.v[i].v;
    const n = e.Name.v;
    const p = e.Properties ? Object.keys(e.Properties.v).sort().map(k => k + '=' + e.Properties.v[k].v).join(',') : '';
    if (n + '|' + p === want) return i;
  }
  t.palette.v.push({ t: 10, v: {
    Name: { t: 8, v: name },
    Properties: { t: 10, v: Object.fromEntries(Object.keys(props).sort().map(k => [k, { t: 8, v: props[k] }])) },
  } });
  return t.palette.v.length - 1;
}

const bs = (v) => ({ t: 8, v });
const bi = (v) => ({ t: 3, v });
const bd = (v) => ({ t: 6, v });

// ---------------- 放箱子 ----------------
let chestCount = 0;
const chestSpots = [];
if (wantChests) {
  for (const r of rooms) {
    // 挨着房间最北那一行、取最靠西的一格：贴墙、不挡门
    let pick = null;
    for (const [x, z] of r.cells) {
      if (!pick || z < pick[1] || (z === pick[1] && x < pick[0])) pick = [x, z];
    }
    const [bx, bz] = pick;
    // 正面朝向房间中心
    const dx = r.cx - bx, dz = r.cz - bz;
    const facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 'east' : 'west') : (dz > 0 ? 'south' : 'north');
    const state = paletteIndex('minecraft:chest', { facing, type: 'single', waterlogged: 'false' });
    t.blocks.v.push({ t: 10, v: {
      pos: { t: 9, v: [bi(bx), bi(r.y), bi(bz)] },
      state: bi(state),
      nbt: { t: 10, v: { id: bs('minecraft:chest'), LootTable: bs(LOOT_TABLE) } },
    } });
    chestSpots.push([r.y, bx, bz]);
    chestCount++;
  }
}
console.log(`箱子 ${chestCount} 个（战利品表 ${LOOT_TABLE.replace('minecraft:', '')}），清掉旧箱子 ${removed} 个`);

// ---------------- 放 boss：最高层中间翼正中间那间 ----------------
const bossSpots = [];
if (wantBosses) {
  const topY = Math.max(...[...byFloor.keys()]);
  const floor = rooms.filter(r => r.y === topY);
  const target = floor.find(r => Math.abs(r.cx - BOSS_WING_CX) <= 8 && Math.abs(r.cz - BOSS_ROOM_CZ) <= 8);
  if (!target) {
    console.error(`找不到目标教室（y=${topY} 中心 x≈${BOSS_WING_CX} z≈${BOSS_ROOM_CZ}），列一下这层的教室：`);
    for (const r of floor) console.error(`  y${r.y} x${r.minX}..${r.maxX} z${r.minZ}..${r.maxZ} 中心(${r.cx},${r.cz})`);
    process.exit(1);
  }
  console.log(`boss 房间：y=${target.y}  x${target.minX}..${target.maxX}  z${target.minZ}..${target.maxZ}  中心(${target.cx},${target.cz})`);

  if (!t.entities) t.entities = { t: 9, v: [] };
  const zOffsets = [-3, 0, 3];
  BOSSES.forEach((boss, i) => {
    const [bx, bz] = nearestFree(target, target.cx, target.cz + zOffsets[i]);
    t.entities.v.push({ t: 10, v: {
      pos: { t: 9, v: [bd(bx + 0.5), bd(target.y), bd(bz + 0.5)] },
      blockPos: { t: 9, v: [bi(bx), bi(target.y), bi(bz)] },
      nbt: { t: 10, v: {
        id: bs(boss.id),
        // 头顶名字不写进结构：由实体代码按语言文件拼（待机 =「右键挑战 · 角色名」，开战后自动撤掉）。
        // 写死在结构里的话，英文客户端会看到中文名。
        PersistenceRequired: { t: 1, v: 1 },
        // 待机标记：必须由玩家右键才开战，被打不会自动反击
        FsChallengeOnUse: { t: 1, v: 1 },
        Tags: { t: 9, v: [bs('galboss_fs_boss'), bs('galboss_idle')] },
        Rotation: { t: 9, v: [{ t: 5, v: 180 }, { t: 5, v: 0 }] },
      } },
    } });
    bossSpots.push([target.y, bx, bz]);
    console.log(`  ${boss.name}（${boss.id}） @ (${bx}, ${target.y}, ${bz})  面向北(z-)`);
  });
}

// ---------------- 写回 ----------------
const w = new W();
w.u8(10); w.str('');
w.tag(9, 'size'); w.value(t.size);
w.tag(9, 'entities');
w.u8(10); w.i32(t.entities.v.length);
for (const e of t.entities.v) w.value(e);
w.tag(9, 'blocks');
w.u8(10); w.i32(t.blocks.v.length);
for (const b of t.blocks.v) w.value(b);
w.tag(9, 'palette');
w.u8(10); w.i32(t.palette.v.length);
for (const p of t.palette.v) w.value(p);
w.tag(t.DataVersion.t, 'DataVersion'); w.value(t.DataVersion);
w.end();

fs.writeFileSync(nbtFile, zlib.gzipSync(w.build(), { level: 9 }));
console.log(`写回 ${nbtFile}`);
console.log(`  方块 ${oldBlocks} -> ${t.blocks.v.length}，实体 ${oldEntities} -> ${t.entities.v.length}，调色板 ${t.palette.v.length} 种，${(fs.statSync(nbtFile).size / 1024 / 1024).toFixed(2)} MB`);
