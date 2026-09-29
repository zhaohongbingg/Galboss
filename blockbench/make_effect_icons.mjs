import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 自定义状态效果的 HUD 图标。
//
// 原版按 `assets/<modid>/textures/mob_effect/<效果注册名>.png` 找图标，缺了就是紫黑方块。
// 目前需要的是桐香「禁足令」用的 confinement.png（一把锁）。
// 用法：node make_effect_icons.mjs

const OUT = path.join('..', 'src', 'main', 'resources', 'assets', 'galboss', 'textures', 'mob_effect');
const SIZE = 16;

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

function encodePng(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

/** 禁足：一把锁（锁环钢铁色 + 锁体用效果主色 + 锁孔）。 */
function confinementIcon() {
  const px = Buffer.alloc(SIZE * SIZE * 4); // 默认全透明
  const put = (x, y, hex) => {
    if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
    const o = (y * SIZE + x) * 4;
    px[o] = (hex >> 16) & 0xff;
    px[o + 1] = (hex >> 8) & 0xff;
    px[o + 2] = hex & 0xff;
    px[o + 3] = 255;
  };

  const steel = 0xb8c2dd;
  const body = 0x3a3f8f;   // 与 ConfinementEffect 的 color 一致
  const edge = 0x212a5e;
  const hole = 0x121636;

  // 锁环：上半圈，圆心 (8, 8.5)，外半径 3 / 内半径 2
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      const dx = x + 0.5 - 8.0;
      const dy = y + 0.5 - 8.5;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (dy <= 0 && d >= 2.0 && d < 3.0) {
        put(x, y, steel);
      }
    }
  }
  // 锁环两端往下接一截立柱，把环和锁体连起来
  for (let y = 7; y <= 8; y++) {
    put(5, y, steel);
    put(10, y, steel);
  }

  // 锁体：x 4..11 y 9..15，外圈描边
  for (let y = 9; y <= 15; y++) {
    for (let x = 4; x <= 11; x++) {
      const border = x === 4 || x === 11 || y === 9 || y === 15;
      put(x, y, border ? edge : body);
    }
  }

  // 锁孔：上面一个方孔 + 下面一竖
  for (let y = 11; y <= 12; y++) {
    put(7, y, hole);
    put(8, y, hole);
  }
  for (let y = 13; y <= 14; y++) {
    put(7, y, hole);
    put(8, y, hole);
  }

  return px;
}

const icons = { confinement: confinementIcon() };

for (const [name, pixels] of Object.entries(icons)) {
  const file = path.join(OUT, name + '.png');
  fs.writeFileSync(file, encodePng(SIZE, SIZE, pixels));
  console.log(`写出 ${file} (${SIZE}x${SIZE})`);
}
