import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { R } from './world_lib.mjs';

// 扫描「实体存档」（1.17+ 的 <world>/entities/r.x.z.mca，格式与 region 相同，
// 但 chunk NBT 里的实体列表叫 Entities），按 id 前缀过滤打印位置。
// 用法：node _scan_saved_entities.mjs <world/entities 目录> [id 前缀]

const dir = process.argv[2];
const prefix = process.argv[3] || 'galboss:';
if (!dir) {
  console.error('用法: node _scan_saved_entities.mjs <world/entities 目录> [id 前缀]');
  process.exit(1);
}

let chunks = 0, hits = 0;
const byType = new Map();
for (const file of fs.readdirSync(dir)) {
  if (!file.endsWith('.mca')) continue;
  const raw = fs.readFileSync(path.join(dir, file));
  if (raw.length < 8192) continue;
  for (let i = 0; i < 1024; i++) {
    const off = (raw[i * 4] << 16) | (raw[i * 4 + 1] << 8) | raw[i * 4 + 2];
    if (!off) continue;
    const s = off * 4096, len = raw.readUInt32BE(s), comp = raw[s + 4];
    const body = raw.subarray(s + 5, s + 4 + len);
    let bytes;
    try { bytes = comp === 1 ? zlib.gunzipSync(body) : zlib.inflateSync(body); } catch { continue; }
    let nbt;
    try { nbt = new R(bytes).root(); } catch { continue; }
    chunks++;
    for (const e of nbt.Entities || []) {
      const id = e.id;
      if (!id || !id.startsWith(prefix)) continue;
      hits++;
      byType.set(id, (byType.get(id) || 0) + 1);
      const p = (e.Pos || []).map((v) => Number(v.toFixed ? v.toFixed(1) : v));
      console.log(`${id} @ [${p.join(', ')}]`);
    }
  }
}
console.log(`\n扫描 ${chunks} 个实体 chunk，命中 ${hits}`);
for (const [id, n] of [...byType.entries()].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(4)} ${id}`);
