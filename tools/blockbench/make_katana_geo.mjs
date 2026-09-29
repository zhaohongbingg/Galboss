import fs from 'node:fs';
import path from 'node:path';

// 郁子的武士刀。几何手建，贴图用 SlashBlade 的 blade.png（64x128）。
//
// 比例取自实战参照刀（nanosaber/model.obj，用 analyze_obj.mjs 量的）：
//   blade 组 长 333 / 宽 10 / 厚 1.1  ->  宽:长 = 1:33
//   Y 中心线自刀尖 -8 升至刀根 +10    ->  反り(弯曲)抬升约 5.4%
//   X -24~+42 段厚度 7.8              ->  茎 + 鎺，不是刀身
// 本脚本按同一比例缩放：刀身长 16.8 单位时，反り总抬升 ~1.1 单位（5.4% ↔ 2.5°×3 段）。
//
// OBJ 是三角面网格，Bedrock geo 只支持立方体，无法直接转，所以按比例重建成
// 分段旋转的方块链 —— 这样刀身能真的弯，而不是一根直棍。
//
// 和枪一样：**握把中心 = 模型原点**，摆放到手由渲染层负责（见 IkukoKatanaLayer）。

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const TEX_SRC = path.join(HERE, 'ref', 'blade.png');
const OUT_GEO = path.join(HERE, '..', 'src', 'main', 'resources', 'assets', 'galboss', 'geo', 'ikuko_katana.geo.json');
const OUT_TEX = path.join(HERE, '..', 'src', 'main', 'resources', 'assets', 'galboss', 'textures', 'entity', 'ikuko_katana.png');

// blade.png 里挑出来的色块（纯色/近纯色小区，拉伸不影响观感）
const UV = {
  bladeFace: { uv: [0, 0], size: [8, 8] },     // 金色刀面
  bladeEdge: { uv: [0, 0], size: [4, 4] },     // 刃口高光
  bladeSpine: { uv: [24, 24], size: [8, 8] },  // 暗脊
  tsuba: { uv: [0, 72], size: [8, 8] },        // 刀镡
  tsuka: { uv: [0, 80], size: [8, 8] },        // 柄
  kashira: { uv: [0, 88], size: [8, 8] }       // 柄头
};

// ---- 刀身：4 段链式，每段绕 X 抬 2.5°，累积出反り（刀尖朝 -Z）--------------------
const BLADE_START = -3.2;   // 鎺/刀镡侧
const SEG_LEN = 4.2;
const SEG_COUNT = 4;
const SEG_TILT = 1.5;

const bones = [];
bones.push({
  name: 'katana_root',
  pivot: [0, 1, 0],
  cubes: [
    // 鎺（刀镡前的箍）
    { origin: [-0.42, 0.45, -3.9], size: [0.84, 1.2, 0.75], uv: 'tsuba' },
    // 刀镡
    { origin: [-1.0, 0.0, -3.1], size: [2.0, 1.5, 0.7], uv: 'tsuba' },
    // 柄
    { origin: [-0.38, 0.1, -2.4], size: [0.76, 1.3, 4.6], uv: 'tsuka' },
    // 柄头
    { origin: [-0.44, 0.1, 2.2], size: [0.88, 1.3, 0.6], uv: 'kashira' },
    // 柄卷：三道略宽的环，深浅交错，做出缠绳感
    { origin: [-0.42, 0.06, -1.5], size: [0.84, 1.38, 0.35], uv: 'kashira' },
    { origin: [-0.42, 0.06, 0.0], size: [0.84, 1.38, 0.35], uv: 'kashira' },
    { origin: [-0.42, 0.06, 1.4], size: [0.84, 1.38, 0.35], uv: 'kashira' }
  ]
});

for (let i = 0; i < SEG_COUNT; i++) {
  const zStart = BLADE_START - SEG_LEN * i;
  const zEnd = zStart - SEG_LEN;
  const last = i === SEG_COUNT - 1;
  bones.push({
    name: `katana_blade_${i}`,
    parent: i === 0 ? 'katana_root' : `katana_blade_${i - 1}`,
    pivot: [0, 1, zStart],
    rotation: [SEG_TILT, 0, 0],   // 绕 X 正角 = 刀尖上抬
    cubes: [
      // 刀面（薄：厚 0.6，高 1.1）
      { origin: [-0.3, 0.5, zEnd], size: [0.6, 1.1, SEG_LEN], uv: 'bladeFace', up: 'bladeEdge' },
      // 脊（比刀面低一点、暗色，形成镐筋）
      { origin: [-0.3, 1.1, zEnd], size: [0.6, 0.5, SEG_LEN], uv: 'bladeSpine' },
      // 切先：末段收锋
      ...(last
        ? [
            { origin: [-0.26, 0.62, zEnd - 1.3], size: [0.52, 0.86, 1.3], uv: 'bladeFace', up: 'bladeEdge' },
            { origin: [-0.2, 0.78, zEnd - 2.0], size: [0.4, 0.54, 0.7], uv: 'bladeFace', up: 'bladeEdge' }
          ]
        : [])
    ]
  });
}

function face(rect) {
  return { uv: rect.uv.slice(), uv_size: rect.size.slice() };
}

function toCube(c) {
  const uv = {};
  const map = c;
  ['north', 'east', 'south', 'west', 'up', 'down'].forEach((f) => {
    uv[f] = face(UV[map[f] || map.uv]);
  });
  return { origin: c.origin.slice(), size: c.size.slice(), uv };
}

const geo = {
  format_version: '1.12.0',
  'minecraft:geometry': [
    {
      description: {
        identifier: 'geometry.ikuko_katana',
        texture_width: 64,
        texture_height: 128,
        visible_bounds_width: 2,
        visible_bounds_height: 3,
        visible_bounds_offset: [0, 1, -8]
      },
      bones: bones.map((b) => {
        const out = { name: b.name, pivot: b.pivot, cubes: b.cubes.map(toCube) };
        if (b.parent) out.parent = b.parent;
        if (b.rotation) out.rotation = b.rotation;
        return out;
      })
    }
  ]
};

fs.mkdirSync(path.dirname(OUT_GEO), { recursive: true });
fs.writeFileSync(OUT_GEO, JSON.stringify(geo, null, 2));
fs.copyFileSync(TEX_SRC, OUT_TEX);

const total = bones.reduce((n, b) => n + b.cubes.length, 0);
// 链式骨骼的倾角是累积的：第 i 段的实际倾角是 (i+1)*SEG_TILT
const rise = Array.from({ length: SEG_COUNT }, (_, i) =>
  SEG_LEN * Math.sin(((i + 1) * SEG_TILT * Math.PI) / 180)
).reduce((a, b) => a + b, 0);
console.log(`写出 ${path.relative(path.join(HERE, '..'), OUT_GEO)}`);
console.log(`贴图 ${path.relative(path.join(HERE, '..'), OUT_TEX)} (64x128)`);
console.log(`骨骼 ${bones.length}（刀身 ${SEG_COUNT} 段链式）/ 立方体 ${total}`);
console.log(`刀身长 ${(SEG_LEN * SEG_COUNT).toFixed(1)} 单位，反り抬升 ${rise.toFixed(2)} 单位，握把中心在原点`);
