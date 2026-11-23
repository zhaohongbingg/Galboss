import { AIR, forEachBlock } from './world_lib.mjs';

// 打印一片区域的地形「高度图 + 顶面方块」，用来判断结构相对周围地面的高低。
// 用法：node _terrain_probe.mjs <存档目录> <x1> <z1> <x2> <z2> [yMin] [yMax]

const [worldDir, x1, z1, x2, z2, yMinS, yMaxS] = process.argv.slice(2);
const [X1, Z1, X2, Z2] = [x1, z1, x2, z2].map(Number);
const YMIN = Number(yMinS || 60), YMAX = Number(yMaxS || 160);
if (!z2) {
  console.error('用法: node _terrain_probe.mjs <存档目录> <x1> <z1> <x2> <z2> [yMin] [yMax]');
  process.exit(1);
}

const top = new Map();   // "x,z" -> {y, name}
forEachBlock(worldDir, { x: X1, y: YMIN, z: Z1 }, { x: X2, y: YMAX, z: Z2 }, (x, y, z, pe) => {
  if (AIR.has(pe.Name)) return;
  const k = x + ',' + z;
  const cur = top.get(k);
  if (!cur || y > cur.y) top.set(k, { y, name: pe.Name });
});

const name = (n) => n.replace('minecraft:', '').replace('galboss:', 'g:').slice(0, 10);

console.log(`区域 x ${X1}..${X2}  z ${Z1}..${Z2}  y ${YMIN}..${YMAX}`);
console.log('\n=== 每列顶面 y（行=z，列=x，两位十六进制偏移自 YMIN，"." = 空） ===');
const xs = [], zs = [];
for (let x = X1; x <= X2; x++) xs.push(x);
for (let z = Z1; z <= Z2; z++) zs.push(z);
let header = '      ';
for (let i = 0; i < xs.length; i += 10) header += String(xs[i]).padStart(10);
console.log(header);
for (const z of zs) {
  let line = String(z).padStart(5) + ' ';
  for (const x of xs) {
    const t = top.get(x + ',' + z);
    line += t ? (t.y - YMIN).toString(16).padStart(2, '0').slice(-2) : '..';
  }
  console.log(line);
}

// 顶面方块种类统计
const kinds = new Map();
for (const t of top.values()) kinds.set(name(t.name), (kinds.get(name(t.name)) || 0) + 1);
console.log('\n=== 顶面方块统计 ===');
[...kinds.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12).forEach(([n, c]) => console.log(`${String(c).padStart(5)} ${n}`));

// 沿 z 中线、x 方向打印竖剖面的非空气方块（合并连续同名）
const zc = Math.round((Z1 + Z2) / 2);
const col = new Map();
forEachBlock(worldDir, { x: X1, y: YMIN, z: zc }, { x: X2, y: YMAX, z: zc }, (x, y, z, pe) => {
  if (AIR.has(pe.Name)) return;
  if (!col.has(x)) col.set(x, []);
  col.get(x).push(`${y}:${name(pe.Name)}`);
});
console.log(`\n=== z=${zc} 剖面（x: 由低到高的非空气方块） ===`);
for (const x of xs) {
  const list = col.get(x) || [];
  console.log(`${String(x).padStart(4)}  ${list.join('  ')}`);
}
