import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 礼的手枪。**几何手建、贴图自产** —— 不再使用 TACZ 的模型/贴图（那套不能借用）。
//
// 和武士刀同一套路：脚本生成 geo + 贴图，然后由 geo_to_item_model.mjs 转成原版物品模型。
//
// 设计基准（握把中心 = 模型原点）：
//   枪管沿 -Z（角色正面），握把中心 (0,0,0)，整把 13（Z）x 10.6（Y）x 2.3（X）模型单位。
//   比例照实枪（格洛克 186x139x33mm）折算：长:高 ≈ 1.35，套筒比机匣略宽，握把最窄。
//   握把后倾 22.5°（真枪握把后倾角），这样侧影才像手枪而不是一根棍。
//
// ⚠ 一个必须遵守的约定：**几何变换只能写在 cube 上，不能写在骨骼上**。
//   geo_to_item_model.mjs 是把 cube 直接拍平的（不解析骨骼层级），原版模型也没有骨骼概念，
//   所以骨骼上的 rotation 会被整段丢掉。握把后倾因此写成 grip 那几块 cube 自己的 rotation。
//
// 用法: node make_gun_geo.mjs
//   产出 geo/rei_gun.geo.json（texture 128x128）
//        textures/entity/rei_gun.png（128x128 色板图，脚本按下面 PALETTE 画出来）

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const RES = path.join(HERE, '..', 'src', 'main', 'resources', 'assets', 'galboss');
const OUT_GEO = path.join(RES, 'geo', 'rei_gun.geo.json');
const OUT_TEX = path.join(RES, 'textures', 'entity', 'rei_gun.png');

// ---------------------------------------------------------------- 色板贴图
// 128x128，切成 16x16 个 8x8 的小色块；每个面取色块正中 2x2 的小矩形
// （README 的约定：采样区被拉伸到整面，纯色不变形；取正中是避免相邻色块串色）。
const TEX = 128;
const CELL = 8;
const PER_ROW = TEX / CELL;

const PALETTE = {
  slideTop: '#6E747A',   // 套筒顶面（受光最亮）
  slideSide: '#565C62',  // 套筒侧面
  slideDark: '#3A3F44',  // 套筒底面
  serration: '#25292D',  // 套筒后部的防滑纹环
  barrel: '#31363B',     // 枪管 / 枪口
  bore: '#0C0E10',       // 枪口内孔
  frame: '#484D52',      // 机匣
  frameDark: '#33373B',  // 机匣底面 / 扳机护圈后段
  grip: '#26292C',       // 握把本体
  gripPanel: '#35393D',  // 握把侧板
  floor: '#1B1D20',      // 弹匣底板
  guard: '#3D4247',      // 扳机护圈
  trigger: '#17191B',    // 扳机
  sight: '#141618',      // 准星
  ejection: '#0F1113'    // 抛壳口
};

const NAMES = Object.keys(PALETTE);
const UV = {};
NAMES.forEach((name, i) => {
  UV[name] = { uv: [(i % PER_ROW) * CELL + 3, Math.floor(i / PER_ROW) * CELL + 3], size: [2, 2] };
});

const hex = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];

// ---- 极简 PNG 编码（8bit RGBA、无隔行）----
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

function buildTexture() {
  const px = Buffer.alloc(TEX * TEX * 4); // 默认全透明
  NAMES.forEach((name, i) => {
    const [r, g, b] = hex(PALETTE[name]);
    const cx = (i % PER_ROW) * CELL;
    const cy = Math.floor(i / PER_ROW) * CELL;
    for (let y = 0; y < CELL; y++) {
      for (let x = 0; x < CELL; x++) {
        const d = ((cy + y) * TEX + cx + x) * 4;
        px[d] = r;
        px[d + 1] = g;
        px[d + 2] = b;
        px[d + 3] = 255;
      }
    }
  });
  return px;
}

// ---------------------------------------------------------------- 几何
// 每块写成 { from, to, faces }；faces 里 'all' 是默认色块，其余键按面覆盖。
// 坐标：+Y 上、-Z 枪口方向、X 左右（右为 +X，与原版模型空间一致）。
// 后倾绕**握把中心**转，这样旋转前后「握把中心 = 原点」都精确成立，
// 转物品模型时的握把参数才能就写 "0,0,0"（见 geo_to_item_model.mjs 的约束【3】）。
const GRIP_PIVOT = [0, 0, 0];
const GRIP_RAKE = [22.5, 0, 0];   // 绕 X 正角 = 握把下端往后（+Z）倒

// 前端加长量：套筒 / 防尘盖 / 枪管 / 枪口 / 前准星一起往前伸，
// 机匣位置不动，所以握把中心仍在原点。
// 加长 2 之后全长 : 全高 ≈ 15.5 : 11.5 ≈ 1.34，正好是实枪（格洛克 186:139）的比例。
const NOSE = 2.0;
const SLIDE_FRONT = -7.2 - NOSE;      // 套筒前端
const MUZZLE = SLIDE_FRONT - 1.1;     // 枪管伸出套筒 1.1 个单位

const CUBES = [
  // ---- 握把（带后倾，绕着握把顶端转）----
  {
    bone: 'gun_grip',
    // 顶面故意伸进机匣里（后倾后顶端还会往里缩一点），避免握把和机匣之间留缝；
    // 上下对称是为了让「握把中心 = 原点」在旋转前后都成立
    from: [-1.05, -3.4, -1.75], to: [1.05, 3.4, 1.75],
    rotate: true,
    faces: { all: 'grip', east: 'gripPanel', west: 'gripPanel', south: 'gripPanel' }
  },
  { // 弹匣底板
    bone: 'gun_grip',
    from: [-1.0, -3.8, -2.0], to: [1.0, -3.4, 2.0],
    rotate: true,
    faces: { all: 'floor' }
  },
  // ---- 机匣 / 套筒 / 枪管 ----
  { bone: 'gun_root', from: [-1.0, 3.0, -4.0], to: [1.0, 5.0, 5.0], faces: { all: 'frame', down: 'frameDark' } },
  // 前端机匣（防尘盖主体）：机匣本体只到 z=-4，这一段把套筒下方 y 3..5 的空腔填实，
  // 前端面比套筒前端缩进 0.4，像真枪那样让套筒微微外探一点
  { bone: 'gun_root', from: [-1.0, 3.0, SLIDE_FRONT + 0.4], to: [1.0, 5.0, -4.0], faces: { all: 'frame', down: 'frameDark' } },
  // 前端下缘的导轨唇（比前端机匣窄一点、低 0.6，做出真枪防尘盖那圈台阶）。
  // 前端比上面那块缩 0.1，免得两个朝外的面共面打架；尾端顶到护圈前柱（z=-3.6），别留缺口
  { bone: 'gun_root', from: [-0.88, 2.4, SLIDE_FRONT + 0.5], to: [0.88, 3.0, -3.6], faces: { all: 'frame', down: 'frameDark' } },
  { bone: 'gun_root', from: [-1.15, 5.0, SLIDE_FRONT], to: [1.15, 7.1, 5.0], faces: { all: 'slideSide', up: 'slideTop', down: 'slideDark' } },
  { bone: 'gun_root', from: [-0.6, 5.4, MUZZLE], to: [0.6, 6.6, SLIDE_FRONT], faces: { all: 'barrel', up: 'slideTop' } },
  { bone: 'gun_root', from: [-0.34, 5.65, MUZZLE - 0.15], to: [0.34, 6.35, MUZZLE], faces: { all: 'bore' } },
  // 套筒后部两道防滑纹（比套筒略宽一点点的环）
  { bone: 'gun_root', from: [-1.2, 5.2, 1.6], to: [1.2, 7.05, 1.95], faces: { all: 'serration' } },
  { bone: 'gun_root', from: [-1.2, 5.2, 2.5], to: [1.2, 7.05, 2.85], faces: { all: 'serration' } },
  // 抛壳口（贴在套筒右侧的薄片，纯色窗口效果）
  { bone: 'gun_root', from: [1.15, 5.6, -2.4], to: [1.22, 6.8, -0.6], faces: { all: 'ejection' } },
  // ---- 准星 ----
  { bone: 'gun_root', from: [-0.28, 7.1, SLIDE_FRONT + 0.8], to: [0.28, 7.7, SLIDE_FRONT + 1.5], faces: { all: 'sight' } },
  { bone: 'gun_root', from: [-0.95, 7.1, 3.6], to: [0.95, 7.55, 4.6], faces: { all: 'sight' } },
  // ---- 扳机护圈（前柱 / 底 / 后柱，围成环；两柱顶端顶到机匣底 y=3，别留缝）----
  { bone: 'gun_root', from: [-0.7, 0.6, -3.6], to: [0.7, 3.0, -3.0], faces: { all: 'guard' } },
  { bone: 'gun_root', from: [-0.7, 0.2, -3.6], to: [0.7, 0.6, 0.2], faces: { all: 'guard' } },
  { bone: 'gun_root', from: [-0.7, 0.6, -0.4], to: [0.7, 3.0, 0.2], faces: { all: 'guard', down: 'frameDark' } },
  // ---- 扳机（上端接进机匣里，别当悬空薄片）----
  { bone: 'gun_root', from: [-0.3, 1.0, -2.2], to: [0.3, 3.0, -1.6], faces: { all: 'trigger' } }
];

// ---------------------------------------------------------------- 组装 geo
const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

function toCube(c) {
  const uv = {};
  FACES.forEach((f) => {
    const name = c.faces[f] || c.faces.all;
    uv[f] = { uv: UV[name].uv.slice(), uv_size: UV[name].size.slice() };
  });
  const out = { origin: c.from.slice(), size: c.to.map((v, i) => v - c.from[i]), uv };
  if (c.rotate) {
    out.pivot = GRIP_PIVOT.slice();
    out.rotation = GRIP_RAKE.slice();
  }
  return out;
}

const bones = [
  { name: 'gun_root', pivot: [0, 0, 0], cubes: [] },
  { name: 'gun_grip', parent: 'gun_root', pivot: GRIP_PIVOT.slice(), cubes: [] }
];
for (const c of CUBES) {
  bones.find((b) => b.name === c.bone).cubes.push(toCube(c));
}

const geo = {
  format_version: '1.12.0',
  'minecraft:geometry': [
    {
      description: {
        identifier: 'geometry.rei_gun',
        texture_width: TEX,
        texture_height: TEX,
        visible_bounds_width: 3,
        visible_bounds_height: 3,
        visible_bounds_offset: [0, 0.02, -0.15]
      },
      bones
    }
  ]
};

fs.mkdirSync(path.dirname(OUT_GEO), { recursive: true });
fs.mkdirSync(path.dirname(OUT_TEX), { recursive: true });
fs.writeFileSync(OUT_GEO, JSON.stringify(geo, null, 2));
fs.writeFileSync(OUT_TEX, encodePng(TEX, TEX, buildTexture()));

const total = bones.reduce((n, b) => n + b.cubes.length, 0);
const lo = [0, 1, 2].map((i) => Math.min(...CUBES.map((c) => c.from[i])));
const hi = [0, 1, 2].map((i) => Math.max(...CUBES.map((c) => c.to[i])));
console.log(`写出 ${path.relative(path.join(HERE, '..'), OUT_GEO)}`);
console.log(`贴图 ${path.relative(path.join(HERE, '..'), OUT_TEX)} (${TEX}x${TEX} 色板，${NAMES.length} 色块)`);
console.log(`骨骼 ${bones.length} / 立方体 ${total}（其中握把后倾 ${CUBES.filter((c) => c.rotate).length} 块）`);
console.log(`包围盒 ${JSON.stringify(lo)} .. ${JSON.stringify(hi)}  尺寸 ${hi.map((v, i) => +(v - lo[i]).toFixed(1))}`);
console.log(`握把中心在原点，枪口朝 -Z；转物品模型请用: TEX_SIZE=128 node geo_to_item_model.mjs rei_gun.geo.json rei_gun.json rei_gun 0.6 "0,0,0"`);
