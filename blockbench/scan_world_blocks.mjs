import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 扫描存档 region 文件里指定方块（默认红石）出现的位置。
// 用法：node scan_world_blocks.mjs <存档目录> [方块id，默认 minecraft:redstone_block]
//
// Anvil region 格式（1.18+）：
//   8KiB 头 = 4096B 定位表（每 chunk 4B：3B 扇区偏移 + 1B 扇区数）+ 4096B 时间戳
//   chunk = 4B 长度 + 1B 压缩类型（1=gzip，2=zlib）+ 压缩的 NBT
//   NBT 里 sections[].block_states = { palette:[{Name,...}], data:打包的 long 数组 }
//   1.16 起 palette 条目不跨 long 边界打包。

const worldDir = process.argv[2];
const targetId = process.argv[3] || 'minecraft:redstone_block';
if (!worldDir) {
  console.error('用法: node scan_world_blocks.mjs <存档目录> [方块id]');
  process.exit(1);
}

// ---------------- NBT 读取器（只读，够用即可） ----------------
class NbtReader {
  constructor(buf) { this.b = buf; this.p = 0; }
  u8() { const v = this.b[this.p++]; return v > 127 ? v - 256 : v; }
  i16() { const v = this.b.readInt16BE(this.p); this.p += 2; return v; }
  i32() { const v = this.b.readInt32BE(this.p); this.p += 4; return v; }
  i64() { const v = this.b.readBigInt64BE(this.p); this.p += 8; return v; }
  f32() { const v = this.b.readFloatBE(this.p); this.p += 4; return v; }
  f64() { const v = this.b.readDoubleBE(this.p); this.p += 8; return v; }
  str() { const len = this.b.readUInt16BE(this.p); this.p += 2; const s = this.b.toString('utf8', this.p, this.p + len); this.p += len; return s; }
  payload(type) {
    switch (type) {
      case 1: return this.u8();
      case 2: return this.i16();
      case 3: return this.i32();
      case 4: return this.i64();
      case 5: return this.f32();
      case 6: return this.f64();
      case 7: { const len = this.i32(); const v = this.b.subarray(this.p, this.p + len); this.p += len; return v; }
      case 8: return this.str();
      case 9: {
        const et = this.u8(); const len = this.i32();
        const arr = [];
        for (let i = 0; i < len; i++) arr.push(this.payload(et));
        return arr;
      }
      case 10: {
        const obj = {};
        for (;;) {
          const t = this.u8();
          if (t === 0) break;
          const name = this.str();
          obj[name] = this.payload(t);
        }
        return obj;
      }
      case 11: { const len = this.i32(); const v = []; for (let i = 0; i < len; i++) v.push(this.i32()); return v; }
      case 12: { const len = this.i32(); const v = []; for (let i = 0; i < len; i++) v.push(this.i64()); return v; }
      default: throw new Error('未知 NBT 类型 ' + type);
    }
  }
  root() {
    const t = this.u8();
    if (t !== 10) throw new Error('根不是 compound');
    this.str();
    return this.payload(10);
  }
}

/** 解包 1.16+ 的 block_states.data（条目不跨 long），返回每格的 palette 下标（y,z,x 顺序）。 */
function unpackPalette(data, palLen) {
  const bpe = palLen === 1 ? 0 : Math.max(4, Math.ceil(Math.log2(palLen)));
  const out = new Int32Array(4096);
  if (bpe === 0) { out.fill(0); return out; }
  const perLong = Math.floor(64 / bpe);
  const mask = (1n << BigInt(bpe)) - 1n;
  let i = 0;
  for (const long of data) {
    let v = BigInt.asUintN(64, long);
    for (let s = 0; s < perLong && i < 4096; s++) {
      out[i++] = Number(v & mask);
      v >>= BigInt(bpe);
    }
  }
  return out;
}

// ---------------- 扫描 ----------------
const hits = [];
let chunksScanned = 0;

for (const file of fs.readdirSync(path.join(worldDir, 'region'))) {
  if (!file.endsWith('.mca')) continue;
  const raw = fs.readFileSync(path.join(worldDir, 'region', file));
  if (raw.length < 8192) continue;
  const [rx, rz] = file.match(/r\.(-?\d+)\.(-?\d+)\.mca/).slice(1).map(Number);

  for (let cz = 0; cz < 32; cz++) {
    for (let cx = 0; cx < 32; cx++) {
      const entry = (cz * 32 + cx) * 4;
      const offset = (raw[entry] << 16) | (raw[entry + 1] << 8) | raw[entry + 2];
      if (offset === 0) continue;
      const secStart = offset * 4096;
      const len = raw.readUInt32BE(secStart);
      const comp = raw[secStart + 4];
      const body = raw.subarray(secStart + 5, secStart + 4 + len);
      let nbt;
      try {
        nbt = new NbtReader(comp === 1 ? zlib.gunzipSync(body) : zlib.inflateSync(body)).root();
      } catch { continue; }
      chunksScanned++;

      for (const section of nbt.sections || []) {
        const states = section.block_states;
        if (!states || !states.palette) continue;
        const idx = states.palette.findIndex(e => e.Name === targetId);
        if (idx < 0) continue;
        if (!states.data) continue; // 整段全是一种方块的情况
        const unpacked = unpackPalette(states.data, states.palette.length);
        const yBase = section.Y * 16;
        for (let i = 0; i < 4096; i++) {
          if (unpacked[i] !== idx) continue;
          const y = i >> 8;
          const z = (i >> 4) & 15;
          const x = i & 15;
          hits.push({
            x: rx * 512 + cx * 16 + x,
            y: yBase + y,
            z: rz * 512 + cz * 16 + z,
          });
        }
      }
    }
  }
}

console.log(`扫描 ${chunksScanned} 个 chunk，方块 ${targetId} 命中 ${hits.length} 个`);

if (hits.length) {
  const xs = hits.map(h => h.x), ys = hits.map(h => h.y), zs = hits.map(h => h.z);
  console.log(`x ${Math.min(...xs)}..${Math.max(...xs)}  y ${Math.min(...ys)}..${Math.max(...ys)}  z ${Math.min(...zs)}..${Math.max(...zs)}`);
  // 按 y 分组看一圈是不是都在同一层
  const byY = new Map();
  for (const h of hits) byY.set(h.y, (byY.get(h.y) || 0) + 1);
  console.log('按 y 分层：', [...byY.entries()].sort((a, b) => a[0] - b[0]).map(([y, n]) => `y${y}×${n}`).join('  '));
  fs.writeFileSync(path.join(import.meta.dirname, '_redstone_hits.json'), JSON.stringify(hits));
  console.log('明细已写 _redstone_hits.json');
  // 打印头 20 个，便于人肉核对位置
  console.log(hits.slice(0, 20).map(h => `${h.x} ${h.y} ${h.z}`).join('\n'));
}
