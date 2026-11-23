import { AIR, forEachChunk } from './world_lib.mjs';

// 扫描存档 region 里的实体（chunk NBT 的 entities 列表），按 id 过滤打印。
// 用法：node _scan_entities.mjs <存档目录> [id 前缀，例如 galboss:]

const worldDir = process.argv[2];
const prefix = process.argv[3] || 'galboss:';
if (!worldDir) {
  console.error('用法: node _scan_entities.mjs <存档目录> [id 前缀]');
  process.exit(1);
}

let chunks = 0, hits = 0;
const byType = new Map();
forEachChunk(worldDir, (nbt, cx0, cz0) => {
  chunks++;
  for (const e of nbt.entities || []) {
    const id = e.id;
    if (!id || !id.startsWith(prefix)) continue;
    hits++;
    byType.set(id, (byType.get(id) || 0) + 1);
    const p = e.Pos || [];
    console.log(`${id} @ [${p.map((v) => Number(v.toFixed ? v.toFixed(1) : v)).join(', ')}]  chunk ${cx0},${cz0}`);
  }
});
console.log(`\n扫描 ${chunks} chunk，命中 ${hits} 个实体`);
for (const [id, n] of [...byType.entries()].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(4)} ${id}`);
