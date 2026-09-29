import zlib from 'node:zlib';

// 极简 PNG 写入器（RGBA8 / 无滤波），够画方块平面图用。

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

export class Canvas {
  constructor(w, h, bg = [12, 12, 16, 255]) {
    this.w = w;
    this.h = h;
    this.px = Buffer.alloc(w * h * 4);
    for (let i = 0; i < w * h; i++) {
      this.px[i * 4] = bg[0]; this.px[i * 4 + 1] = bg[1];
      this.px[i * 4 + 2] = bg[2]; this.px[i * 4 + 3] = bg[3];
    }
  }

  set(x, y, [r, g, b, a = 255]) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const o = (y * this.w + x) * 4;
    if (a >= 255) {
      this.px[o] = r; this.px[o + 1] = g; this.px[o + 2] = b; this.px[o + 3] = 255;
      return;
    }
    // 简单 alpha 混合
    const t = a / 255;
    this.px[o] = Math.round(this.px[o] * (1 - t) + r * t);
    this.px[o + 1] = Math.round(this.px[o + 1] * (1 - t) + g * t);
    this.px[o + 2] = Math.round(this.px[o + 2] * (1 - t) + b * t);
  }

  /** 按 scale 放大画一个小格（方块图用，1 格 = scale×scale 像素）。 */
  cell(x, y, scale, color) {
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) this.set(x * scale + dx, y * scale + dy, color);
  }

  rect(x, y, w, h, color) {
    for (let dy = 0; dy < h; dy++) for (let dx = 0; dx < w; dx++) this.set(x + dx, y + dy, color);
  }

  toPng() {
    const raw = Buffer.alloc((this.w * 4 + 1) * this.h);
    for (let y = 0; y < this.h; y++) {
      raw[y * (this.w * 4 + 1)] = 0;
      this.px.copy(raw, y * (this.w * 4 + 1) + 1, y * this.w * 4, (y + 1) * this.w * 4);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(this.w, 0);
    ihdr.writeUInt32BE(this.h, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    return Buffer.concat([
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      chunk('IHDR', ihdr),
      chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
      chunk('IEND', Buffer.alloc(0))
    ]);
  }
}
