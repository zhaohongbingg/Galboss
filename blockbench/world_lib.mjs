import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 存档 region 解析库（1.18+ Anvil + NBT，只读）。

/**
 * NBT 读取器。
 * typed=false（默认）→ 直接给 JS 值，适合读 sections/palette；
 * typed=true → 每个值包成 {t, v} 保留原始类型，适合要把整棵子树原样写回去
 * （方块实体数据：告示牌文字、箱子内容之类）。
 */
export class R {
  constructor(b, typed = false) { this.b = b; this.p = 0; this.typed = typed; }
  /** NBT 的 byte 是<b>有符号</b>的：section.Y 这种负值必须做符号扩展，否则 -4 会读成 252。 */
  u8() { const v = this.b[this.p++]; return v > 127 ? v - 256 : v; }
  i16() { const v = this.b.readInt16BE(this.p); this.p += 2; return v; }
  i32() { const v = this.b.readInt32BE(this.p); this.p += 4; return v; }
  i64() { const v = this.b.readBigInt64BE(this.p); this.p += 8; return v; }
  f32() { const v = this.b.readFloatBE(this.p); this.p += 4; return v; }
  f64() { const v = this.b.readDoubleBE(this.p); this.p += 8; return v; }
  str() { const l = this.b.readUInt16BE(this.p); this.p += 2; const s = this.b.toString('utf8', this.p, this.p + l); this.p += l; return s; }
  pay(t) {
    const v = this.payRaw(t);
    return this.typed ? { t, v } : v;
  }
  payRaw(t) {
    switch (t) {
      case 1: return this.u8();
      case 2: return this.i16();
      case 3: return this.i32();
      case 4: return this.i64();
      case 5: return this.f32();
      case 6: return this.f64();
      case 7: { const l = this.i32(); const v = this.b.subarray(this.p, this.p + l); this.p += l; return v; }
      case 8: return this.str();
      case 9: { const et = this.u8(); const l = this.i32(); const a = []; for (let i = 0; i < l; i++) a.push(this.pay(et)); return a; }
      case 10: { const o = {}; for (;;) { const tt = this.u8(); if (!tt) break; const name = this.str(); o[name] = this.pay(tt); } return o; }
      case 11: { const l = this.i32(); const v = []; for (let i = 0; i < l; i++) v.push(this.i32()); return v; }
      case 12: { const l = this.i32(); const v = []; for (let i = 0; i < l; i++) v.push(this.i64()); return v; }
      default: throw new Error('NBT ' + t);
    }
  }
  /** 根 compound 本身不包装（typed 模式下它的字段值才带 {t,v}）。 */
  root() { this.u8(); this.str(); return this.payRaw(10); }
}

export const AIR = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']);

/** 解包 1.16+ 的 block_states.data，返回 4096 个 palette 下标（y,z,x 顺序）。 */
export function unpack(data, palLen) {
  const bpe = palLen === 1 ? 0 : Math.max(4, Math.ceil(Math.log2(palLen)));
  const out = new Int32Array(4096);
  if (!bpe) return out.fill(0);
  const per = Math.floor(64 / bpe), mask = (1n << BigInt(bpe)) - 1n;
  let i = 0;
  for (const long of data) {
    let v = BigInt.asUintN(64, long);
    for (let s = 0; s < per && i < 4096; s++) { out[i++] = Number(v & mask); v >>= BigInt(bpe); }
  }
  return out;
}

/**
 * 遍历存档所有已生成 chunk，回调 (chunkNbt, chunkX0, chunkZ0, 解压后的NBT字节)。
 *
 * 第 4 个参数是解压后的 NBT 字节，需要「保留类型」再解析一遍时（读方块实体）可以直接
 * {@code new R(bytes, true).root()}，不用重新解压。
 */
export function forEachChunk(worldDir, cb) {
  for (const file of fs.readdirSync(path.join(worldDir, 'region'))) {
    if (!file.endsWith('.mca')) continue;
    const raw = fs.readFileSync(path.join(worldDir, 'region', file));
    if (raw.length < 8192) continue;
    const [rx, rz] = file.match(/r\.(-?\d+)\.(-?\d+)\.mca/).slice(1).map(Number);
    for (let cz = 0; cz < 32; cz++) for (let cx = 0; cx < 32; cx++) {
      const e = (cz * 32 + cx) * 4;
      const off = (raw[e] << 16) | (raw[e + 1] << 8) | raw[e + 2];
      if (!off) continue;
      const s = off * 4096, len = raw.readUInt32BE(s), comp = raw[s + 4];
      const body = raw.subarray(s + 5, s + 4 + len);
      let bytes, nbt;
      try {
        bytes = comp === 1 ? zlib.gunzipSync(body) : zlib.inflateSync(body);
        nbt = new R(bytes).root();
      } catch { continue; }
      cb(nbt, rx * 512 + cx * 16, rz * 512 + cz * 16, bytes);
    }
  }
}

/** 遍历一个盒子范围内的方块，回调 (x, y, z, paletteEntry)。 */
export function forEachBlock(worldDir, lo, hi, cb) {
  forEachChunk(worldDir, (nbt, cx0, cz0) => {
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
        cb(x, y, z, pal[u ? u[i] : 0]);
      }
    }
  });
}
