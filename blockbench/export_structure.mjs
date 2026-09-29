import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { AIR, R, forEachChunk, unpack } from './world_lib.mjs';
import { W } from './nbt_write.mjs';
import { fixDoors } from './fix_doors.mjs';

// 把存档里的一块区域导出成原版结构文件（.nbt template）。
// 用法：node export_structure.mjs <存档目录> <x1> <y1> <z1> <x2> <y2> <z2> <输出.nbt> [选项]
//   选项 --strip=<方块id,方块id>  导出时把这些方块当空气（默认剔除红石标记）
//        --data-version=<n>       缺省用 3465（1.20.1）

const argv = process.argv.slice(2);
const worldDir = argv[0];
const [x1, y1, z1, x2, y2, z2] = argv.slice(1, 7).map(Number);
const outFile = argv[7];
if (!worldDir || !outFile || Number.isNaN(x2)) {
  console.error('用法: node export_structure.mjs <存档> <x1> <y1> <z1> <x2> <y2> <z2> <输出.nbt>');
  process.exit(1);
}

const stripArg = argv.find(a => a.startsWith('--strip='));
const strip = new Set((stripArg ? stripArg.slice(8) : 'minecraft:redstone_block').split(',').filter(Boolean));
const dvArg = argv.find(a => a.startsWith('--data-version='));
const dataVersion = dvArg ? Number(dvArg.split('=')[1]) : 3465;

const lo = { x: Math.min(x1, x2), y: Math.min(y1, y2), z: Math.min(z1, z2) };
const hi = { x: Math.max(x1, x2), y: Math.max(y1, y2), z: Math.max(z1, z2) };
const sizeX = hi.x - lo.x + 1, sizeY = hi.y - lo.y + 1, sizeZ = hi.z - lo.z + 1;

// palette 去重：Name + 排序后的 Properties
const palette = [];
const palIndex = new Map();
function palIndexFor(entry) {
  let key = entry.Name;
  if (entry.Properties) {
    key += '|' + Object.keys(entry.Properties).sort().map(k => k + '=' + entry.Properties[k]).join(',');
  }
  let i = palIndex.get(key);
  if (i === undefined) {
    i = palette.length;
    palette.push(entry);
    palIndex.set(key, i);
  }
  return i;
}

const blocks = [];
const types = new Map();
/** 方块实体（告示牌文字 / 箱子内容 / 刷怪笼…），key = 绝对坐标。 */
const blockEntities = new Map();
let stripped = 0;
let entityCount = 0;

forEachChunk(worldDir, (nbt, cx0, cz0, bytes) => {
  // 方块实体要保留原始类型才能原样写回，所以同一个 chunk 用 typed 模式再解一次
  if ((nbt.block_entities || []).length) {
    const typed = new R(bytes, true).root();
    for (const be of (typed.block_entities?.v || [])) {
      const f = be.v;
      const bx = f.x?.v, by = f.y?.v, bz = f.z?.v;
      if (bx === undefined) continue;
      if (bx < lo.x || bx > hi.x || by < lo.y || by > hi.y || bz < lo.z || bz > hi.z) continue;
      const data = {};
      for (const k of Object.keys(f)) if (k !== 'x' && k !== 'y' && k !== 'z') data[k] = f[k];
      blockEntities.set(`${bx},${by},${bz}`, data);
      entityCount++;
    }
  }

  if (cx0 + 16 <= lo.x || cx0 > hi.x || cz0 + 16 <= lo.z || cz0 > hi.z) return;
  for (const sec of nbt.sections || []) {
    const st = sec.block_states;
    if (!st || !st.palette) continue;
    const yb = sec.Y * 16;
    if (yb + 16 <= lo.y || yb > hi.y) continue;
    const pal = st.palette;
    const u = st.data ? unpack(st.data, pal.length) : null;
    for (let i = 0; i < 4096; i++) {
      const y = yb + (i >> 8);
      const z = cz0 + ((i >> 4) & 15);
      const x = cx0 + (i & 15);
      if (y < lo.y || y > hi.y || z < lo.z || z > hi.z || x < lo.x || x > hi.x) continue;
      const entry = pal[u ? u[i] : 0];
      if (AIR.has(entry.Name)) continue;
      if (strip.has(entry.Name)) { stripped++; continue; }
      blocks.push({ x: x - lo.x, y: y - lo.y, z: z - lo.z, state: palIndexFor(entry) });
      types.set(entry.Name, (types.get(entry.Name) || 0) + 1);
    }
  }
});

// ---------------- 门的开合方向修正 ----------------
// 有些门铰链装反了：开门时门板那一侧没有墙，门会悬在半空。这里按几何把铰链换到有墙的一侧，
// 并把门关回去（结构里的门应该是关着的）。加 --no-fix-doors 可以跳过。
let doorStats = null;
if (!argv.includes('--no-fix-doors')) {
  doorStats = fixDoors(blocks, palette, palIndexFor);
}

// ---------------- 写 NBT ----------------
const w = new W();
w.u8(10); w.str('');                       // 根 compound（无名）

w.list('size', 3, 3);
w.i32(sizeX); w.i32(sizeY); w.i32(sizeZ);

w.list('entities', 10, 0);                 // 不带实体

let writtenBe = 0;
w.list('blocks', 10, blocks.length);
for (const b of blocks) {
  w.list('pos', 3, 3);
  w.i32(b.x); w.i32(b.y); w.i32(b.z);
  w.int('state', b.state);
  const be = blockEntities.get(`${b.x + lo.x},${b.y + lo.y},${b.z + lo.z}`);
  if (be) {
    // 结构里方块实体数据挂在 blocks[].nbt 上，字段与存档里的 block_entities 一致（去掉绝对坐标）
    w.compound('nbt');
    for (const k of Object.keys(be)) { w.tag(be[k].t, k); w.value(be[k]); }
    w.end();
    writtenBe++;
  }
  w.end();
}

w.list('palette', 10, palette.length);
for (const e of palette) {
  w.string('Name', e.Name);
  if (e.Properties && Object.keys(e.Properties).length) {
    w.compound('Properties');
    for (const k of Object.keys(e.Properties).sort()) w.string(k, e.Properties[k]);
    w.end();
  }
  w.end();
}

w.int('DataVersion', dataVersion);
w.end();

const outPath = path.resolve(outFile);
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, zlib.gzipSync(w.build(), { level: 9 }));

console.log(`区域 x${lo.x}..${hi.x} y${lo.y}..${hi.y} z${lo.z}..${hi.z} = ${sizeX}x${sizeY}x${sizeZ}`);
console.log(`导出方块 ${blocks.length}（剔除 ${stripped} 个红石标记），调色板 ${palette.length} 种，DataVersion ${dataVersion}`);
console.log(`范围内方块实体 ${entityCount} 个，写进结构 ${writtenBe} 个`);
if (doorStats) {
  console.log(`门 ${doorStats.total} 个：铰链装反翻正 ${doorStats.flipped}，` +
    `本来就对 ${doorStats.ok}，两侧都有墙 ${doorStats.both}，两侧都没墙 ${doorStats.none}，` +
    `顺手关门 ${doorStats.closed}，上下半不一致 ${doorStats.mismatch}`);
}
console.log(`写出 ${outPath}  ${(fs.statSync(outPath).size / 1024 / 1024).toFixed(2)} MB`);
const top = [...types.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
console.log('方块 top8：' + top.map(([n, c]) => `${n}×${c}`).join('  '));
