import fs from 'node:fs';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';
import { W } from './nbt_write.mjs';

// 给「棋局竞技场」结构补内容（可重复运行）：
//   1. 场地内部地板以上灌空气 —— 模板里显式写 air，生成时才会把树木/地形清掉；
//   2. 正中央放一只贝阿朵莉切（结构生成时就站在那里）。
//
// 用法：node add_arena_content.mjs <chess_arena.nbt>
//
// 与 arena_struct.mcfunction 对应：模板 66x14x66（rework_arena.mjs 之后的下沉版），
// 局部 y=0 是棋盘地板层，y=1 石沿，y=2..4 围墙，中心 (32.5, 1.0, 32.5)。

const nbtFile = process.argv[2];
if (!nbtFile) {
  console.error('用法: node add_arena_content.mjs <chess_arena.nbt>');
  process.exit(1);
}

const FLOOR_TOP = 1;      // 地板顶面（实体脚部高度，局部坐标）
const HEADROOM = 12;      // 地板以上清空层数
const BOSS_ID = 'galboss:beatrice';

const raw = zlib.gunzipSync(fs.readFileSync(nbtFile));
const t = new R(raw, true).root();     // typed：每个字段是 {t, v}

const bs = (s) => ({ t: 8, v: s });
const bd = (v) => ({ t: 6, v });
const bi = (v) => ({ t: 3, v });
const bf = (v) => ({ t: 5, v });
const bb = (v) => ({ t: 1, v });

const [sx, sy, sz] = t.size.v.map(n => n.v);
console.log(`结构尺寸 ${sx} x ${sy} x ${sz}`);

// ---------------- palette ----------------
const nameOf = (pe) => pe.v.Name.v;
const propsOf = (pe) => pe.v.Properties
  ? Object.fromEntries(Object.entries(pe.v.Properties.v).map(([k, v]) => [k, v.v]))
  : null;
const palKey = (pe) => {
  let k = nameOf(pe);
  const props = propsOf(pe);
  if (props) k += '|' + Object.keys(props).sort().map(n => `${n}=${props[n]}`).join(',');
  return k;
};
const palIndex = new Map();
t.palette.v.forEach((pe, i) => palIndex.set(palKey(pe), i));
const AIR_NAME = 'minecraft:air';
let airIdx = palIndex.get(AIR_NAME);
if (airIdx === undefined) {
  airIdx = t.palette.v.length;
  t.palette.v.push({ t: 10, v: { Name: bs(AIR_NAME) } });
  palIndex.set(AIR_NAME, airIdx);
  console.log(`palette 里补 ${AIR_NAME}（下标 ${airIdx}）`);
} else {
  console.log(`${AIR_NAME} 已存在（下标 ${airIdx}）`);
}

// ---------------- blocks：清掉旧 air，重新灌内部空气 ----------------
const blocks = t.blocks.v.filter(b => b.v.state.v !== airIdx);   // 旧 air 条目全部丢弃（真实方块保留）
const tile = new Set();
for (const b of blocks) {
  const p = b.v.pos.v;
  tile.add(`${p[0].v},${p[1].v},${p[2].v}`);
}
let airAdded = 0;
for (let y = FLOOR_TOP; y < FLOOR_TOP + HEADROOM; y++) {
  for (let z = 1; z < sz - 1; z++) {
    for (let x = 1; x < sx - 1; x++) {
      const k = `${x},${y},${z}`;
      if (tile.has(k)) continue;               // 不覆盖真实方块（墙/地板）
      tile.add(k);
      blocks.push({ t: 10, v: {
        pos: { t: 9, v: [bi(x), bi(y), bi(z)] },
        state: { t: 3, v: airIdx },
      } });
      airAdded++;
    }
  }
}
t.blocks.v = blocks;
console.log(`内部空气：新增 ${airAdded} 格（y ${FLOOR_TOP}..${FLOOR_TOP + HEADROOM - 1}），方块总数 ${blocks.length}`);

// ---------------- entities：中心放贝阿朵莉切 ----------------
if (!t.entities) t.entities = { t: 9, v: [] };
const before = t.entities.v.length;
t.entities.v = t.entities.v.filter(e => e.v?.nbt?.v?.id?.v !== BOSS_ID);
const removed = before - t.entities.v.length;
const cx = sx / 2 - 0.5, cz = sz / 2 - 0.5;   // 棋盘中心：局部 1..64 的中点 = 32.5
t.entities.v.push({ t: 10, v: {
  pos: { t: 9, v: [bd(cx), bd(FLOOR_TOP), bd(cz)] },
  blockPos: { t: 9, v: [bi(Math.floor(cx)), bi(FLOOR_TOP), bi(Math.floor(cz))] },
  nbt: { t: 10, v: {
    id: bs(BOSS_ID),
    Rotation: { t: 9, v: [bf(0), bf(0)] },
    PersistenceRequired: bb(1),
    Tags: { t: 9, v: [bs('galboss_arena_boss')] },
  } },
} });
console.log(`实体：移除旧 ${removed} 个，放入 1 只 ${BOSS_ID} @ 局部 (${cx}, ${FLOOR_TOP}, ${cz})`);

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
w.end();   // 根 compound 的结束标记（漏了它 = NBT 读取直接失败，MC 报 "Loading NBT data"）

const out = w.build();
fs.writeFileSync(nbtFile, zlib.gzipSync(out, { level: 9 }));
console.log(`写出 ${nbtFile}  ${(fs.statSync(nbtFile).size / 1024).toFixed(1)} KB（未压缩 ${(out.length / 1024 / 1024).toFixed(2)} MB）`);
