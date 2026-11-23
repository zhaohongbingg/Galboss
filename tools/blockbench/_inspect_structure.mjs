import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';

// 结构模板（.nbt）只读检查器：尺寸 / 调色板计数 / 逐层直方图 / 竖列剖面。
// 用法：node _inspect_structure.mjs <path/to/xxx.nbt>

const file = process.argv[2];
if (!file) {
  console.error('用法: node _inspect_structure.mjs <xxx.nbt>');
  process.exit(1);
}
const raw = zlib.gunzipSync(fs.readFileSync(file));
const t = new R(raw, false).root();
const [sx, sy, sz] = t.size;
console.log(`size ${sx} x ${sy} x ${sz}`);
console.log(`palette ${t.palette.length} entries, blocks ${t.blocks.length}, entities ${(t.entities || []).length}, DataVersion ${t.DataVersion}`);

const pal = t.palette.map(p => {
  let k = p.Name;
  if (p.Properties) k += '[' + Object.entries(p.Properties).map(([a, b]) => `${a}=${b}`).join(',') + ']';
  return k;
});
const counts = new Array(pal.length).fill(0);
for (const b of t.blocks) counts[b.state]++;

console.log('\n=== palette counts ===');
pal.forEach((p, i) => console.log(`${String(i).padStart(3)} ${String(counts[i]).padStart(6)}  ${p}`));

const layers = new Map();
for (const b of t.blocks) {
  const y = b.pos[1];
  if (!layers.has(y)) layers.set(y, new Map());
  const m = layers.get(y);
  const name = pal[b.state];
  m.set(name, (m.get(name) || 0) + 1);
}
console.log('\n=== layers (y : total, top blocks) ===');
for (const y of [...layers.keys()].sort((a, b) => a - b)) {
  const m = layers.get(y);
  const total = [...m.values()].reduce((a, b) => a + b, 0);
  const top = [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
    .map(([k, v]) => `${k.replace('minecraft:', '')}x${v}`).join(' ');
  console.log(`y=${String(y).padStart(2)}  n=${String(total).padStart(5)}  ${top}`);
}

const at = new Map();
for (const b of t.blocks) at.set(`${b.pos[0]},${b.pos[1]},${b.pos[2]}`, pal[b.state]);
const col = (x, z) => {
  const out = [];
  for (let y = 0; y < sy; y++) out.push(`${y}:${(at.get(`${x},${y},${z}`) || 'air').replace('minecraft:', '')}`);
  return out.join(' ');
};
const midX = Math.floor(sx / 2), midZ = Math.floor(sz / 2);
console.log('\n=== columns ===');
console.log(`center(${midX},${midZ}): ${col(midX, midZ)}`);
console.log(`edge(0,0): ${col(0, 0)}`);
console.log(`edge(${sx - 1},${sz - 1}): ${col(sx - 1, sz - 1)}`);

if ((t.entities || []).length) {
  console.log('\n=== entities ===');
  for (const e of t.entities) console.log(`${e.nbt.id?.replace?.('minecraft:', '')} @ ${e.pos.join(',')}`);
}
