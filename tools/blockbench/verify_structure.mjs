import fs from 'node:fs';
import zlib from 'node:zlib';
import { AIR, R, forEachBlock } from './world_lib.mjs';
import { checkDoors } from './fix_doors.mjs';

// 校验结构文件：读回来做结构自检，并和存档里同一区域逐格对比。
// 用法：node verify_structure.mjs <存档目录> <结构.nbt> <x1> <y1> <z1> <x2> <y2> <z2>

const [worldDir, nbtFile, x1, y1, z1, x2, y2, z2] = process.argv.slice(2);
const [X1, Y1, Z1, X2, Y2, Z2] = [x1, y1, z1, x2, y2, z2].map(Number);
if (!z2) { console.error('用法: node verify_structure.mjs <存档> <结构.nbt> <x1> <y1> <z1> <x2> <y2> <z2>'); process.exit(1); }

const lo = { x: Math.min(X1, X2), y: Math.min(Y1, Y2), z: Math.min(Z1, Z2) };
const hi = { x: Math.max(X1, X2), y: Math.max(Y1, Y2), z: Math.max(Z1, Z2) };

function key(entry) {
  let k = entry.Name;
  if (entry.Properties && Object.keys(entry.Properties).length) {
    k += '|' + Object.keys(entry.Properties).sort().map(p => p + '=' + entry.Properties[p]).join(',');
  }
  return k;
}

// ---------------- 读结构 ----------------
const root = new R(zlib.gunzipSync(fs.readFileSync(nbtFile))).root();
const size = root.size;
const palette = root.palette;
const blocks = root.blocks;

console.log(`结构 size = ${size.join(' x ')}，palette ${palette.length} 种，blocks ${blocks.length}，DataVersion ${root.DataVersion}`);
console.log(`实体 ${(root.entities || []).length}，带 nbt 的方块 ${blocks.filter(b => b.nbt).length}`);

let bad = 0;
const seen = new Set();
for (const b of blocks) {
  const [x, y, z] = b.pos;
  if (x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]) { bad++; if (bad < 4) console.log(`  ✗ pos 越界 ${b.pos}`); continue; }
  if (b.state < 0 || b.state >= palette.length) { bad++; if (bad < 4) console.log(`  ✗ state 越界 ${b.state}`); continue; }
  const k = x + ',' + y + ',' + z;
  if (seen.has(k)) { bad++; if (bad < 4) console.log(`  ✗ 重复坐标 ${k}`); }
  seen.add(k);
}
console.log(bad === 0 ? '结构自检 ✓（pos 全在范围内、state 全合法、无重复坐标）' : `结构自检发现 ${bad} 处问题 ✗`);

// ---------------- 门的几何复检（结构里的门应该都「开门靠墙」） ----------------
const doorStats = checkDoors(blocks, palette);
console.log(`门前检：共 ${doorStats.total}，装反 ${doorStats.flipped}，` +
  `两侧都有墙 ${doorStats.both}，两侧都没墙 ${doorStats.none}，` +
  `上下半不一致 ${doorStats.mismatch}，开着的 ${doorStats.open}`);
console.log(doorStats.flipped === 0 && doorStats.mismatch === 0 && doorStats.open === 0
  ? '门几何 ✓ 全部开门靠墙、上下半一致、都是关着的'
  : '门几何 ✗ 还有问题');

// ---------------- 箱子 / boss 核对 ----------------
const isChest = (n) => n === 'minecraft:chest' || n === 'minecraft:trapped_chest';
const chests = blocks.filter(b => isChest(palette[b.state].Name));
const loot = new Map();
let noLoot = 0;
for (const c of chests) {
  const table = c.nbt?.LootTable;
  if (!table) { noLoot++; continue; }
  loot.set(table, (loot.get(table) || 0) + 1);
}
console.log(`箱子 ${chests.length} 个，没绑战利品表的 ${noLoot} 个` +
  (loot.size ? '，战利品表：' + [...loot.entries()].map(([k, v]) => `${k}×${v}`).join('  ') : ''));

const ents = root.entities || [];
const entKinds = new Map();
for (const e of ents) {
  const id = e.nbt?.id ?? '(缺 id)';
  entKinds.set(id, (entKinds.get(id) || 0) + 1);
}
console.log(`结构自带实体 ${ents.length} 个：` +
  ([...entKinds.entries()].map(([k, v]) => `${k}×${v}`).join('  ') || '无'));
for (const e of ents.slice(0, 4)) {
  const n = e.nbt || {};
  console.log(`  ${n.id} @ ${JSON.stringify(e.pos.map(v => +v.toFixed(1)))}  待机标记=${n.FsChallengeOnUse ?? 0}  名字=${n.CustomName ?? '(无)'}`);
}

// ---------------- 和存档逐格对比 ----------------
// 门（有意翻正）和箱子（新增）默认跳过；加 --strict 要求完全一致
const STRICT = process.argv.includes('--strict') || process.argv.includes('--check-doors');
const skip = (name) => !STRICT && (name.endsWith('_door') || isChest(name));

const fromWorld = new Map();
forEachBlock(worldDir, lo, hi, (x, y, z, entry) => {
  if (AIR.has(entry.Name) || entry.Name === 'minecraft:redstone_block' || skip(entry.Name)) return;
  fromWorld.set(`${x},${y},${z}`, key(entry));
});

const fromStruct = new Map();
for (const b of blocks) {
  const entry = palette[b.state];
  if (skip(entry.Name)) continue;
  fromStruct.set(`${b.pos[0] + lo.x},${b.pos[1] + lo.y},${b.pos[2] + lo.z}`, key(entry));
}

let onlyWorld = 0, onlyStruct = 0, mismatch = 0;
for (const [k, v] of fromWorld) {
  const s = fromStruct.get(k);
  if (s === undefined) onlyWorld++;
  else if (s !== v) { mismatch++; if (mismatch <= 3) console.log(`  ✗ ${k}\n     存档 ${v}\n     结构 ${s}`); }
}
for (const k of fromStruct.keys()) if (!fromWorld.has(k)) onlyStruct++;

console.log(`\n对比（已剔除红石标记）：`);
console.log(`  存档 ${fromWorld.size} 格 ／ 结构 ${fromStruct.size} 格`);
console.log(`  只存在于存档 ${onlyWorld}，只存在于结构 ${onlyStruct}，方块不一致 ${mismatch}`);
console.log(onlyWorld + onlyStruct + mismatch === 0 ? '逐格对比 ✓ 完全一致' : '逐格对比 ✗ 有差异，见上');
