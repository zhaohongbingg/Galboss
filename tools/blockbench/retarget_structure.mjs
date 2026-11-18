import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { W } from './nbt_write.mjs';

// 把结构 template 的调色板整体换名（方块 id 变体），其余内容原样保留。
// 用途：chess_arena 里原本是原版 stone / polished_deepslate / 混凝土，
// 换成 galboss 的「硬度 -1」竞技场方块，结构放在哪都拆不掉。
// 用法：node retarget_structure.mjs <输入.nbt> <输出.nbt> <旧id=新id,旧id=新id,...>

const [input, output, mapArg] = process.argv.slice(2);
if (!input || !output || !mapArg) {
  console.error('用法: node retarget_structure.mjs <输入.nbt> <输出.nbt> <旧id=新id,...>');
  process.exit(1);
}

const MAP = Object.fromEntries(mapArg.split(',').filter(Boolean).map(pair => pair.split('=')));

const raw = zlib.gunzipSync(fs.readFileSync(input));
const root = new R(raw, true).root();

let changed = 0;
for (const entry of root.palette.v) {
  const name = entry.v.Name;
  const next = MAP[name.v];
  if (next) {
    console.log(`  ${name.v} -> ${next}`);
    name.v = next;
    changed++;
  }
}

// 采样几个非空气方块，方便实机核对落点
const samples = root.blocks.v.slice(0, 6).map(b => {
  const pos = b.v.pos.v.map(n => n.v).join(',');
  const state = b.v.state.v;
  return `${pos}=${root.palette.v[state].v.Name.v}`;
});
console.log('样本:', samples.join('  '));

// 按原顺序写回：size / entities / blocks / palette / DataVersion
const w = new W();
w.u8(10);
w.str('');
for (const key of ['size', 'entities', 'blocks', 'palette', 'DataVersion']) {
  const node = root[key];
  if (!node) continue;
  w.tag(node.t, key);
  w.value(node);
}
w.end();

fs.mkdirSync(output.replace(/[\\/][^\\/]+$/, ''), { recursive: true });
fs.writeFileSync(output, zlib.gzipSync(w.build(), { level: 9 }));
console.log(`替换 ${changed} 个调色板条目，写出 ${output}`);
