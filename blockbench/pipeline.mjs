import fs from 'node:fs';
import path from 'node:path';
import { initialize, callTool, rpc } from './_mcp_client.mjs';
import { ANIMS, CHARACTER_ANIMS, CHARACTER_IDS, animsFor, overriddenNames } from './anims.mjs';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const BB_ROOT = 'd:/game/mc/bossmod/blockbench';
const MOD_ROOT = 'd:/game/mc/bossmod/src/main/resources/assets/galboss';

// ---------------------------------------------------------------- base rig (v2)
// 原版玩家比例 + 原版 64x64 皮肤 UV。v2 相对 v1 的三处结构性修正：
//
//   1. body / 双腿真正挂到 root 之下。v1 里 root 是**空骨骼**（子节点全被拍平到顶层），
//      所以转 root 没反应、转 body 会腰部裂开。修好之后：
//        · root  = 全身总闸（绕脚底旋转，用于整体前倾/后仰、受击、起跳）
//        · body  = 骨盆，现在**带动四肢一起动**，body.position 可以安全地做重心起伏
//   2. 手臂在肘部分两段、腿在膝部分两段。新增 right_forearm / right_shin 等骨骼，
//      分段只切 UV、不改变体积 —— 静止姿态与 v1 逐像素一致，只有转起来才看得出肘/膝。
//   3. weapon_anchor 从 right_arm 改挂到 right_hand 之下，跟着肘与腕一起动。
//      pivot 不变（[6,12,0] 是绑定姿态的绝对坐标），静止时武器位置与 v1 完全相同。
//
// 坐标约定（编辑空间，导出时 X 会被取反，与 v1 相同）：
//   +Y 向上，-Z 是角色正面（脸的贴图在 -Z 侧），角色右手在 +X 侧。
//   四肢下垂时 +X 旋转 = 向前摆；躯干/头朝上，+X 旋转 = 向后仰。
const BASE_GROUPS = [
  { name: 'root', parent: null, pivot: [0, 0, 0] },
  { name: 'body', parent: 'root', pivot: [0, 12, 0] },
  { name: 'head', parent: 'body', pivot: [0, 24, 0] },

  { name: 'right_arm', parent: 'body', pivot: [5, 22, 0] },
  { name: 'right_forearm', parent: 'right_arm', pivot: [5, 18, 0] },
  { name: 'right_hand', parent: 'right_forearm', pivot: [5, 12.5, 0] },
  // 武器挂点：pivot 就是手心。x 必须和 right_arm 的立方体同号（那些立方体在 x 4..8），
  // 写成 -6 会挂到身体另一侧 —— 详见 README「weapon_anchor 骨骼」。
  { name: 'weapon_anchor', parent: 'right_hand', pivot: [6, 12, 0] },

  { name: 'left_arm', parent: 'body', pivot: [-5, 22, 0] },
  { name: 'left_forearm', parent: 'left_arm', pivot: [-5, 18, 0] },
  { name: 'left_hand', parent: 'left_forearm', pivot: [-5, 12.5, 0] },

  { name: 'right_leg', parent: 'body', pivot: [2, 12, 0] },
  { name: 'right_shin', parent: 'right_leg', pivot: [2, 6, 0] },
  { name: 'right_foot', parent: 'right_shin', pivot: [2, 0, 0] },

  { name: 'left_leg', parent: 'body', pivot: [-2, 12, 0] },
  { name: 'left_shin', parent: 'left_leg', pivot: [-2, 6, 0] },
  { name: 'left_foot', parent: 'left_shin', pivot: [-2, 0, 0] }
];

// 把一条 uv 矩形按上下对半切开。四肢分「上段 / 下段」时，四个侧面各取一半。
//   splitV([44, 20, 4, 12]) -> upper [44,20,4,6] / lower [44,26,4,6]
function splitV(rect) {
  const [u, v, w, h] = rect;
  const half = h / 2;
  return { upper: [u, v, w, half], lower: [u, v + half, w, half] };
}

// 一条肢体的 6 面 uv 清单 -> 上下两段的 faces。端面（up/down）两段都给：
// 上段的 up / 下段的 down 是真正的端面，另外两个是肘 / 膝处的**断面** ——
// 静止时两段贴合互相遮住，弯下去才露出来，所以断面复用对侧端面的贴图即可。
function limbFaces(uv) {
  const upper = {};
  const lower = {};
  for (const f of ['north', 'south', 'east', 'west']) {
    const s = splitV(uv.sides[f]);
    upper[f] = s.upper;
    lower[f] = s.lower;
  }
  upper.up = uv.up;
  upper.down = uv.down;
  lower.up = uv.up;
  lower.down = uv.down;
  return { upper, lower };
}

// 原版皮肤上四条肢体的 uv（第一层 / 第二层各一套）。数值取自原版布局，不要改。
const LIMB_UV = {
  right_arm: { sides: { north: [44, 20, 4, 12], east: [40, 20, 4, 12], south: [52, 20, 4, 12], west: [48, 20, 4, 12] }, up: [44, 16, 4, 4], down: [48, 16, 4, 4] },
  left_arm: { sides: { north: [36, 52, 4, 12], east: [32, 52, 4, 12], south: [44, 52, 4, 12], west: [40, 52, 4, 12] }, up: [36, 48, 4, 4], down: [40, 48, 4, 4] },
  right_leg: { sides: { north: [4, 20, 4, 12], east: [0, 20, 4, 12], south: [12, 20, 4, 12], west: [8, 20, 4, 12] }, up: [4, 16, 4, 4], down: [8, 16, 4, 4] },
  left_leg: { sides: { north: [20, 52, 4, 12], east: [16, 52, 4, 12], south: [28, 52, 4, 12], west: [24, 52, 4, 12] }, up: [20, 48, 4, 4], down: [24, 48, 4, 4] },
  right_arm_overlay: { sides: { north: [44, 36, 4, 12], east: [40, 36, 4, 12], south: [52, 36, 4, 12], west: [48, 36, 4, 12] }, up: [44, 32, 4, 4], down: [48, 32, 4, 4] },
  left_arm_overlay: { sides: { north: [52, 52, 4, 12], east: [48, 52, 4, 12], south: [60, 52, 4, 12], west: [56, 52, 4, 12] }, up: [52, 48, 4, 4], down: [56, 48, 4, 4] },
  right_leg_overlay: { sides: { north: [4, 36, 4, 12], east: [0, 36, 4, 12], south: [12, 36, 4, 12], west: [8, 36, 4, 12] }, up: [4, 32, 4, 4], down: [8, 32, 4, 4] },
  left_leg_overlay: { sides: { north: [4, 52, 4, 12], east: [0, 52, 4, 12], south: [12, 52, 4, 12], west: [8, 52, 4, 12] }, up: [4, 48, 4, 4], down: [8, 48, 4, 4] }
};

// 每个面的 UV 按方块展开的标准公式算，uv 起点 (U,V)、盒子尺寸 (w,h,d)：
//   up    = (U+d,     V)   size (w, d)   <- 注意 (U+d,V) 是**顶面**
//   down  = (U+d+w,   V)   size (w, d)   <- (U+d+w,V) 才是**底面**
//   east  = (U,       V+d) size (d, h)
//   north = (U+d,     V+d) size (w, h)
//   west  = (U+d+w,   V+d) size (d, h)
//   south = (U+d+w+d, V+d) size (w, h)
// 验证方式：head 的 up=(8,0) 应该采到头发色 #4A9F89；head_overlay 的 up=(40,0) 是不透明的。
// 之前 up/down 反了，头顶采到脖子区、第二层头顶采到空白 —— 表现就是「光头」。
// 四条肢体的几何。上段从关节往下 6 像素，下段再接 6 像素；
// 立方体的 pivot 与对应骨骼的 pivot 一致，所以旋转就是绕肘 / 绕膝。
// 上段骨骼沿用老名字（right_arm / left_leg ...），老动画一行都不用改。
const LIMBS = [
  {
    bone: 'right_arm', lower: 'right_forearm', uv: 'right_arm',
    from_up: [4, 18, -2], to_up: [8, 24, 2], pivot: [5, 22, 0],
    from_low: [4, 12, -2], to_low: [8, 18, 2], pivot_low: [5, 18, 0]
  },
  {
    bone: 'left_arm', lower: 'left_forearm', uv: 'left_arm',
    from_up: [-8, 18, -2], to_up: [-4, 24, 2], pivot: [-5, 22, 0],
    from_low: [-8, 12, -2], to_low: [-4, 18, 2], pivot_low: [-5, 18, 0]
  },
  {
    bone: 'right_leg', lower: 'right_shin', uv: 'right_leg',
    from_up: [0, 6, -2], to_up: [4, 12, 2], pivot: [2, 12, 0],
    from_low: [0, 0, -2], to_low: [4, 6, 2], pivot_low: [2, 6, 0]
  },
  {
    bone: 'left_leg', lower: 'left_shin', uv: 'left_leg',
    from_up: [-4, 6, -2], to_up: [0, 12, 2], pivot: [-2, 12, 0],
    from_low: [-4, 0, -2], to_low: [0, 6, 2], pivot_low: [-2, 6, 0]
  }
];

// 一条肢体 -> 两个立方体（上段 / 下段）。名字用骨骼名，方便在 Blockbench 里对照。
// ⚠ 第二层的立方体必须**另外起名**（<骨骼>_overlay）：builder_body.js 的 makeCube 是按
//   名字查重复用的，叫同名的话第二层会直接覆盖掉第一层那块，结果是四肢只剩一层
//   （第一层被 inflate 0.25 撑大、第二层彻底消失）。立方体重名在自检里会被拦下。
function limbCubes(limb, overlay) {
  const f = limbFaces(LIMB_UV[limb.uv + (overlay ? '_overlay' : '')]);
  const cube = (name, bone, from, to, pivot, faces) => ({
    name,
    parent: bone,
    from,
    to,
    pivot,
    faces,
    ...(overlay ? { inflate: 0.25 } : {})
  });
  return [
    cube(overlay ? limb.bone + '_overlay' : limb.bone, limb.bone, limb.from_up, limb.to_up, limb.pivot, f.upper),
    cube(overlay ? limb.lower + '_overlay' : limb.lower, limb.lower, limb.from_low, limb.to_low, limb.pivot_low, f.lower)
  ];
}

const BODY_CUBE = {
  name: 'body', parent: 'body', from: [-4, 12, -2], to: [4, 24, 2], pivot: [0, 12, 0],
  faces: { north: [20, 20, 8, 12], east: [16, 20, 4, 12], south: [32, 20, 8, 12], west: [28, 20, 4, 12], up: [20, 16, 8, 4], down: [28, 16, 8, 4] }
};

const HEAD_CUBE = {
  name: 'head', parent: 'head', from: [-4, 24, -4], to: [4, 32, 4], pivot: [0, 24, 0],
  faces: { north: [8, 8, 8, 8], east: [0, 8, 8, 8], south: [24, 8, 8, 8], west: [16, 8, 8, 8], up: [8, 0, 8, 8], down: [16, 0, 8, 8] }
};

const BASE_CUBES = [
  BODY_CUBE,
  HEAD_CUBE,
  ...LIMBS.flatMap((l) => limbCubes(l, false))
];

// --------------------------------------------------------------- 皮肤第二层
// 原版玩家模型的 hat / jacket / sleeve / pants 覆盖层。这些皮肤把大量头发与
// 服装细节画在第二层上，只渲染第一层会丢掉一半外观。
// inflate 取原版数值：头部帽子层 0.5，其余 0.25。
const HEAD_OVERLAY_CUBE = {
  name: 'head_overlay', parent: 'head', from: [-4, 24, -4], to: [4, 32, 4], pivot: [0, 24, 0], inflate: 0.5,
  faces: { north: [40, 8, 8, 8], east: [32, 8, 8, 8], south: [56, 8, 8, 8], west: [48, 8, 8, 8], up: [40, 0, 8, 8], down: [48, 0, 8, 8] }
};

const BODY_OVERLAY_CUBE = {
  name: 'body_overlay', parent: 'body', from: [-4, 12, -2], to: [4, 24, 2], pivot: [0, 12, 0], inflate: 0.25,
  faces: { north: [20, 36, 8, 12], east: [16, 36, 4, 12], south: [32, 36, 8, 12], west: [28, 36, 4, 12], up: [20, 32, 8, 4], down: [28, 32, 8, 4] }
};

const OVERLAY_CUBES = [
  HEAD_OVERLAY_CUBE,
  BODY_OVERLAY_CUBE,
  ...LIMBS.flatMap((l) => limbCubes(l, true))
];

// ------------------------------------------------------------- hair / cloth
// 发型骨骼挂到 head 之下，跟着头一起转。**这四条骨骼现在是空的（不带立方体）** ——
// 外观与 v1 完全一致，只是先把挂点占好：以后要加刘海 / 侧发 / 背发几何，
// 直接把 cube 的 parent 指向这些骨骼即可，动画不用改。
//
// ⚠ 下面 HAIR_CUBES 只是**历史存档，不要直接启用**：它的 uv 来源
// （hair_top / hair_back / hair_side）取自 CHARACTERS 里的 uv_sources，全部指向皮肤
// **第一层**（头顶 8,0 / 侧脸 0,8 一带）。而这些皮肤的头发画在**第二层**（hat 层，
// 从 32,0 开始），所以当年启用它得到的是「光头 + 贴错图」。
// 要真的加发丝，先给每个面换成第二层的矩形，参考 OVERLAY_CUBES 里 head_overlay 的
// north [40,8,8,8] / up [40,0,8,8] 那一套坐标。
const HAIR_GROUPS = [
  { name: 'hair_bangs', parent: 'head', pivot: [0, 29.5, -4] },
  { name: 'hair_side_r', parent: 'head', pivot: [4.3, 29, 0] },
  { name: 'hair_side_l', parent: 'head', pivot: [-4.3, 29, 0] },
  { name: 'hair_back', parent: 'head', pivot: [0, 27, 2.4] }
];

const HAIR_CUBES = [
  {
    name: 'hair_bangs', parent: 'hair_bangs', from: [-4.05, 29.0, -4.9], to: [4.05, 31.95, -3.95], pivot: [0, 31.95, -4],
    faces: { north: 'hair_top', south: 'hair_back', east: 'hair_side', west: 'hair_side', up: 'hair_top', down: 'hair_side' }
  },
  {
    name: 'hair_side_r', parent: 'hair_side_r', from: [3.7, 19.5, -4.35], to: [4.75, 29.6, -0.6], pivot: [4.2, 29.6, -1.0],
    faces: { north: 'hair_side', south: 'hair_back', east: 'hair_side', west: 'hair_top', up: 'hair_top', down: 'hair_side' }
  },
  {
    name: 'hair_side_l', parent: 'hair_side_l', from: [-4.75, 19.5, -4.35], to: [-3.7, 29.6, -0.6], pivot: [-4.2, 29.6, -1.0],
    faces: { north: 'hair_side', south: 'hair_back', east: 'hair_top', west: 'hair_side', up: 'hair_top', down: 'hair_side' }
  },
  {
    name: 'hair_back', parent: 'hair_back', from: [-4.2, 8.0, 2.15], to: [4.2, 27.6, 4.15], pivot: [0, 27.6, 2.6],
    faces: { north: 'hair_back', south: 'hair_top', east: 'hair_side', west: 'hair_side', up: 'hair_top', down: 'hair_side' }
  }
];

const SKIRT_GROUP = { name: 'skirt', parent: 'body', pivot: [0, 12, 0] };
const SKIRT_CUBE = {
  name: 'skirt', parent: 'skirt', from: [-4.9, 9.2, -3.0], to: [4.9, 13.2, 3.0], pivot: [0, 12, 0],
  faces: { all: 'cloth' }
};

// -------------------------------------------------------------------- 武器
//
// 武器挂在 right_arm 下面跟着手臂一起转，所以「举枪瞄准」把手臂前抬到 86 度时
// 枪口自然指向正前方。坐标用的是模型绝对坐标：右手大概在 (6, 12.5, 0)，前方是 -Z。
//
// 贴图是 64x128：上半张还是皮肤，下半张 y=64..71 是武器色板（每块 8x8 纯色）。
// UV 在这些色块内部取子矩形，所以每个面拿到的都是纯色，不会串到皮肤上。

// 色块是纯色，所以每个面统一取 2x2 的小矩形即可：
// 采样区被拉伸到整张面，颜色仍然是对的，也不用为不同长宽比各配一块。
const WEAPON_UV = {
  gun_body: { uv: [0, 64], size: [2, 2] },
  gun_dark: { uv: [8, 64], size: [2, 2] },
  gun_steel: { uv: [16, 64], size: [2, 2] },
  gun_mid: { uv: [24, 64], size: [2, 2] },
  gun_sight: { uv: [32, 64], size: [1, 1] },
  blade_mid: { uv: [0, 72], size: [2, 2] },
  blade_dark: { uv: [8, 72], size: [2, 2] },
  blade_light: { uv: [16, 72], size: [2, 2] },
  blade_edge: { uv: [24, 72], size: [2, 2] },
  tsuka: { uv: [32, 72], size: [2, 2] },
  tsuka_wrap: { uv: [40, 72], size: [2, 2] },
  tsuba: { uv: [48, 72], size: [2, 2] }
};

// 分件照两个参考模组来，而不是自己拍脑袋：
//   枪 <- TACZ glock_17 的骨骼：套筒 / 枪管 / 扳机 / 准星 / 弹匣 / 握把
//   刀 <- TinkersKatanas 的贴图分件：刀身 / 刀脊 / 刀柄 / 护手 / 柄头
//
// ⚠ 这段是**旧方案（武器直接建在角色模型里）的遗留**：三个角色的 spec 现在都是 weapon:'none'，
//   武器走「物品 + 渲染层」（见 README「武器：做成物品」）。下面这些坐标是按手心在 +6 写的，
//   要重新启用必须先整体镜像到 -6（right_arm 的立方体在 x -8..-4，手心 -6），
//   否则武器会挂在身体另一侧 —— 详情见 fix_weapon_anchor_side.mjs。
const WEAPON_SPECS = {
  // 手枪（旧坐标，按手心 +6 写的）：握把中心落在 (6, 12.5, 0)，套筒坐在握把正上方、枪身朝 -Z 伸出。
  // 手心是右臂底部（**实际模型中右臂立方体在 x -8..-4**，即手心 -6；这里用 +6 就是上面说的遗留问题），
  // 所以握把在 y12 以下露出下半截，读起来就是「手握着握把」，而不是拿套筒。
  gun: {
    groups: [
      { name: 'gun', parent: 'right_arm', pivot: [6, 15.5, 0], rotation: [-57.5, 0, 0] }
    ],
    cubes: [
      {
        name: 'gun_grip', parent: 'gun',
        from: [5.4, 13.6, -2.6], to: [6.6, 17.0, -1.4], pivot: [6, 15.5, -2],
        faces: { all: 'gun_mid' }
      },
      {
        name: 'gun_magazine', parent: 'gun',
        from: [5.45, 13.0, -2.5], to: [6.55, 13.7, -1.5], pivot: [6, 15.5, -2],
        faces: { all: 'gun_dark' }
      },
      {
        name: 'gun_trigger', parent: 'gun',
        from: [5.55, 16.0, -3.5], to: [6.45, 16.7, -2.7], pivot: [6, 15.5, -2],
        faces: { all: 'gun_dark' }
      },
      {
        name: 'gun_slide', parent: 'gun',
        from: [5.2, 17.0, -7.8], to: [6.8, 18.4, -1.6], pivot: [6, 15.5, -2],
        faces: { all: 'gun_body', down: 'gun_dark' }
      },
      {
        name: 'gun_barrel', parent: 'gun',
        from: [5.65, 17.3, -8.5], to: [6.35, 18.0, -7.8], pivot: [6, 15.5, -2],
        faces: { all: 'gun_steel' }
      },
      {
        name: 'gun_sight', parent: 'gun',
        from: [5.7, 18.4, -7.5], to: [6.3, 18.9, -6.8], pivot: [6, 15.5, -2],
        faces: { all: 'gun_sight' }
      },
      {
        name: 'gun_front_sight', parent: 'gun',
        from: [5.75, 18.4, -8.4], to: [6.25, 18.75, -8.1], pivot: [6, 15.5, -2],
        faces: { all: 'gun_sight' }
      }
    ]
  },
  // 武士刀：刀柄中心同样落在手心，刀身从护手往 -Z 伸出，整体绕手心抬起 35 度。
  // 再大刀尖就插进地面：手在 y=12.5，刀身 17.2 长，12.5 - 17.2*sin(35°) ≈ 2.6，仍在地面之上
  katana: {
    groups: [
      { name: 'katana', parent: 'right_arm', pivot: [6, 12.5, 0], rotation: [-35, 0, 0] }
    ],
    cubes: [
      {
        name: 'katana_top_blade', parent: 'katana',
        from: [5.5, 12.4, -20.0], to: [6.5, 13.2, -2.8], pivot: [6, 12.5, 0],
        faces: { all: 'blade_mid', up: 'blade_light' }
      },
      {
        name: 'katana_spine', parent: 'katana',
        from: [5.5, 12.0, -20.0], to: [6.5, 12.4, -2.8], pivot: [6, 12.5, 0],
        faces: { all: 'blade_dark' }
      },
      {
        name: 'katana_edge', parent: 'katana',
        from: [5.05, 12.4, -19.8], to: [5.5, 13.2, -3.2], pivot: [6, 12.5, 0],
        faces: { all: 'blade_edge' }
      },
      {
        name: 'katana_tsuba', parent: 'katana',
        from: [5.0, 11.8, -3.6], to: [7.0, 13.4, -2.6], pivot: [6, 12.5, 0],
        faces: { all: 'tsuba' }
      },
      {
        name: 'katana_tsuka', parent: 'katana',
        from: [5.4, 11.9, -2.6], to: [6.6, 13.1, 2.6], pivot: [6, 12.5, 0],
        faces: { all: 'tsuka', up: 'tsuka_wrap' }
      },
      {
        name: 'katana_kashira', parent: 'katana',
        from: [5.45, 11.9, 2.6], to: [6.55, 13.1, 3.4], pivot: [6, 12.5, 0],
        faces: { all: 'tsuka_wrap' }
      }
    ]
  }
};

// ------------------------------------------------------------------ per char
// uv_sources were picked from a per-pixel analysis of each 64x64 skin: every
// source rectangle is fully opaque and reads as hair or garment on both sides.
const CHARACTERS = {
  reizein_tohka: {
    label: '冷泉院 桐香',
    texture: 'reizein_tohka.png',
    skin: `${BB_ROOT}/textures/reizein_tohka_armed.png`,
    weapon: 'none',
    uv_sources: {
      hair_top: { uv: [8, 0], size: [8, 8] },
      hair_back: { uv: [24, 8], size: [8, 8] },
      hair_side: { uv: [0, 8], size: [8, 8] },
      cloth: { uv: [4, 20], size: [4, 12] },
      cloth_dark: { uv: [8, 20], size: [4, 12] }
    },
    geo_out: `${MOD_ROOT}/geo/reizein_tohka.geo.json`,
    model_identifier: 'reizein_tohka'
  },
  onabuta_ikuko: {
    label: 'オナブタ 郁子',
    texture: 'onabuta_ikuko.png',
    skin: `${BB_ROOT}/textures/onabuta_ikuko_armed.png`,
    // 刀已改成独立 GeoModel（geo/ikuko_katana.geo.json + IkukoKatanaLayer）
    weapon: 'none',
    uv_sources: {
      hair_top: { uv: [8, 0], size: [8, 8] },
      hair_back: { uv: [24, 8], size: [8, 8] },
      hair_side: { uv: [0, 8], size: [8, 8] },
      cloth: { uv: [4, 26], size: [4, 6] },
      cloth_dark: { uv: [8, 26], size: [4, 6] }
    },
    geo_out: `${MOD_ROOT}/geo/onabuta_ikuko.geo.json`,
    model_identifier: 'onabuta_ikuko'
  },
  tadasugawa_rei: {
    label: '只須川 レイ',
    texture: 'tadasugawa_rei.png',
    skin: `${BB_ROOT}/textures/tadasugawa_rei_armed.png`,
    // 枪已改成独立 GeoModel（geo/rei_gun.geo.json + ReiGunLayer），角色模型上不再挂武器骨骼
    weapon: 'none',
    uv_sources: {
      hair_top: { uv: [8, 0], size: [8, 8] },
      hair_back: { uv: [24, 8], size: [8, 8] },
      hair_side: { uv: [0, 8], size: [8, 8] },
      cloth: { uv: [4, 26], size: [4, 6] },
      cloth_dark: { uv: [8, 26], size: [4, 6] }
    },
    geo_out: `${MOD_ROOT}/geo/tadasugawa_rei.geo.json`,
    model_identifier: 'tadasugawa_rei'
  }
};

// --vanilla 只生成原版玩家（Steve）人形：7 根骨骼 / 6 个立方体，
// 不带刘海、侧发、背发与裙摆。加了发型的版本见 *_with_hair.bbmodel。
const VANILLA_ONLY = process.argv.includes('--vanilla');

function buildSpec(key) {
  const c = CHARACTERS[key];
  if (!c) throw new Error(`unknown character: ${key}`);
  // 模型 = 原版 Steve 的双层人形 + 四肢分段（10 第一层 + 10 第二层），不加任何自定义外观部件：
  //
  //  - 头发画在皮肤**第二层**（hat 层），OVERLAY_CUBES 里的 head_overlay 已经负责渲染它。
  //    额外加发丝几何的坑见 HAIR_CUBES 上方注释（uv 必须取第二层）。
  //  - 裙子同样画在 body / leg 的贴图上，不需要额外几何。
  const weapon = VANILLA_ONLY ? null : (WEAPON_SPECS[c.weapon] || null);
  return {
    name: key,
    texture_name: c.texture,
    uv_sources: { ...c.uv_sources, ...WEAPON_UV },
    // HAIR_GROUPS 是**空骨骼**（没有立方体），只占挂点，外观不变 —— 见它的注释。
    // weapon_anchor 现在在 BASE_GROUPS 里，必须跟着 spec 一起下发，
    // 否则 RESET_JS 清空项目后它就没了，礼的枪 / 郁子的刀会整把消失。
    groups: [...BASE_GROUPS, ...HAIR_GROUPS, ...(weapon ? weapon.groups : [])],
    cubes: [...BASE_CUBES, ...OVERLAY_CUBES, ...(weapon ? weapon.cubes : [])]
  };
}

// ------------------------------------------------------------------- driving
function textOf(res) {
  const content = res?.result?.content;
  if (!Array.isArray(content)) return JSON.stringify(res);
  return content.map((x) => x.text ?? JSON.stringify(x)).join('\n');
}

/**
 * 去掉 JS 注释。
 *
 * 这版 Blockbench MCP 的 risky_eval 会直接拒绝含 `//` 或 `/* *\/` 的代码
 * （"Code must not include 'console.', '//' or '/* *\/' comments."），
 * 而构建脚本和 RESET_JS 里都写了注释，所以送进去之前先剥掉。
 * 脚本里没有 URL、没有除法、也没有含双斜杠的字符串，直接替换是安全的。
 */
function stripComments(code) {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
}

async function evalCode(code, label) {
  const res = await callTool('risky_eval', { code: stripComments(code) });
  const txt = textOf(res);
  console.log(`--- ${label} ---\n${txt}\n`);
  if (res?.result?.isError) throw new Error(`${label} failed`);
  return txt;
}

// Blockbench keeps several .bbmodel tabs alive and the MCP resolves the
// "active" project heuristically, so instead of creating a project per
// character we clear the current one and rebuild it in place.
const RESET_JS = (projectName, skinPath, texName) => `
(function () {
  Undo.initEdit({ outliner: true, elements: [], selection: true });
  Cube.all.slice().forEach(function (c) { c.remove(); });
  Group.all.slice().forEach(function (g) { g.remove(); });
  Outliner.root.slice().forEach(function (e) { if (e && e.remove) e.remove(); });
  Texture.all.slice().forEach(function (t) { t.remove(false); });
  Undo.finishEdit('Reset project');

  Project.name = ${JSON.stringify(projectName)};
  Project.texture_width = 64;
  Project.texture_height = 128;   // 下半张 y=64..79 是武器色板
  Project.uv_mode = 'per_face';

  var t = new Texture({ name: ${JSON.stringify(texName)} });
  Texture.all.push(t);
  t.fromPath(${JSON.stringify(skinPath)});
  t.name = ${JSON.stringify(texName)};
  Project.saved = false;
  Canvas.updateAll();
  return {
    project: Project.name,
    uv_mode: Project.uv_mode,
    cubes: Cube.all.length,
    groups: Group.all.length,
    textures: Texture.all.map(function (x) { return x.name; })
  };
})()
`;

async function phaseRig(key) {
  const specJson = JSON.stringify(buildSpec(key));
  const builder = fs.readFileSync(path.join(HERE, 'builder_body.js'), 'utf8');
  await evalCode(`var SPEC = ${specJson};\n${builder}`, 'rig');
  console.log(await textOf(await callTool('geckolib_validate_model', { include_animations: false })));
}

async function assertActive(key) {
  const txt = await evalCode(
    `(function(){ return { active: Project ? Project.name : null, format: Project ? Project.format.id : null }; })()`,
    'active project'
  );
  if (!txt.includes(`"active"`) || !txt.includes(`"${key}"`)) {
    console.log(`WARNING: expected active project "${key}"`);
  }
}

// Everything for one character runs in a single process: Blockbench's
// "active project" can drift between separate MCP connections, so build,
// preview and export must not be split across runs.
async function saveBbmodel(uuid, key) {
  const res = await rpc('resources/read', { uri: `blockbench://project/${uuid}.bbmodel` });
  const contents = res?.result?.contents || [];
  const text = contents.map((x) => x.text ?? '').join('');
  if (!text) {
    console.log(`could not read project resource for ${key}`);
    return;
  }
  fs.writeFileSync(`${BB_ROOT}/${key}.bbmodel`, text);
  console.log(`saved ${key}.bbmodel (${text.length} chars)`);
}

async function phaseMake(key) {
  const c = CHARACTERS[key];

  // 复用同名项目：以前每次 make 都无条件 create_project，跑几轮就堆出一堆同名标签页。
  // 顺带把同名的多余标签页关掉：以前跑几轮会堆一堆同名项目，这里一并清理，保证一个名字只有一个标签。
  const probe = await evalCode(
    `(function () {
      var hit = null; var dupes = [];
      ModelProject.all.slice().forEach(function (p) {
        if (p.name !== ${JSON.stringify(key)}) return;
        if (!hit) { hit = p; } else { dupes.push(p); }
      });
      dupes.forEach(function (p) { try { p.close(true, true); } catch (e) { } });
      if (hit) { hit.select(); return { reused: true, uuid: hit.uuid, closedDupes: dupes.length }; }
      return { reused: false, closedDupes: dupes.length };
    })()`,
    'reuse project'
  );
  let uuid = (probe.match(/"uuid":\s*"([^"]+)"/) || [])[1];
  if (!/"reused":\s*true/.test(probe)) {
    const created = await textOf(await callTool('create_project', { name: key, format: 'geckolib_model' }));
    console.log(created);
    uuid = (created.match(/UUID: ([0-9a-f-]+)/i) || [])[1];
    await assertActive(key);
  } else {
    console.log(`reusing existing project ${key}`);
  }
  console.log(await textOf(await callTool('geckolib_set_project_settings', {
    modid: 'galboss', model_type: 'Entity', model_identifier: c.model_identifier
  })));
  await evalCode(RESET_JS(key, c.skin, c.texture), 'reset + texture');
  await phaseRig(key);
  await phaseShot(key);
  await phaseExport(key);
  if (uuid) await saveBbmodel(uuid, key);
}

async function phaseShot(key) {
  const views = [
    ['front', [0, 18, -72]],
    ['quarter', [52, 22, -52]],
    ['back', [0, 18, 72]],
    ['side', [72, 18, 0]]
  ];
  const viewId = 'rig_shot';
  await callTool('delete_offscreen_view', { view: viewId }).catch(() => {});
  console.log(await textOf(await callTool('create_offscreen_view', {
    id: viewId, width: 480, height: 640, antialias: false, copy_view: 'none'
  })));

  for (const [name, position] of views) {
    await callTool('set_camera_angle', {
      view: viewId, position, target: [0, 17, 0], projection: 'perspective', fov: 30
    });
    const res = await callTool('capture_screenshot', { view: viewId });
    const img = (res?.result?.content || []).find((x) => x.type === 'image');
    if (img) {
      fs.writeFileSync(`${BB_ROOT}/_preview/${key}_${name}.png`, Buffer.from(img.data, 'base64'));
      console.log(`saved ${key}_${name}.png`);
    } else {
      console.log(`failed ${name}: ` + JSON.stringify(res).slice(0, 300));
    }
  }
  await callTool('delete_offscreen_view', { view: viewId }).catch(() => {});
}

async function phaseExport(key) {
  const c = CHARACTERS[key];
  const res = await callTool('geckolib_export_model', { mode: 'compile', path: c.geo_out, max_content_length: 1200 });
  console.log(textOf(res));
}

async function phaseExtra(key, tool, args) {
  console.log(textOf(await callTool(tool, JSON.parse(args || '{}'))));
}

// ------------------------------------------------------------- offline check
// `node pipeline.mjs check` 不连 Blockbench，纯离线校验 spec：
// 骨骼父子关系 / 立方体父节点 / UV 是否越界 / 四肢两段是否拼得严丝合缝。
// 改完骨架先跑这个，再开 Blockbench 跑 make。
function offlineCheck() {
  const problems = [];
  const warn = [];
  let cubeTotal = 0;

  for (const key of Object.keys(CHARACTERS)) {
    const spec = buildSpec(key);
    const names = new Set(spec.groups.map((g) => g.name));

    // 1. 骨骼：父节点必须存在，且不能成环
    for (const g of spec.groups) {
      if (g.parent && !names.has(g.parent)) {
        problems.push(`${key}: 骨骼 ${g.name} 的父节点 ${g.parent} 不存在`);
      }
      const seen = new Set([g.name]);
      let cur = g.parent;
      while (cur) {
        if (seen.has(cur)) { problems.push(`${key}: 骨骼 ${g.name} 到 ${cur} 成环`); break; }
        seen.add(cur);
        cur = spec.groups.find((x) => x.name === cur)?.parent;
      }
      if (!g.parent && g.name !== 'root') warn.push(`${key}: 骨骼 ${g.name} 是顶层（父节点为空）`);
    }

    // 2. 立方体：名字唯一（重名会被 builder 按名字复用、静默吃掉一块）、
    //    父骨骼必须存在、尺寸非零、uv 不越界
    const seenCubes = new Set();
    for (const c of spec.cubes) {
      if (seenCubes.has(c.name)) {
        problems.push(`${key}: 立方体 ${c.name} 重名 —— builder_body.js 的 makeCube 按名字复用，后一块会覆盖前一块`);
      }
      seenCubes.add(c.name);
      if (!names.has(c.parent)) problems.push(`${key}: 立方体 ${c.name} 的父骨骼 ${c.parent} 不存在`);
      for (let i = 0; i < 3; i++) {
        if (c.to[i] - c.from[i] <= 0) problems.push(`${key}: 立方体 ${c.name} 在第 ${i} 轴上尺寸 <= 0`);
      }
      for (const [face, rect] of Object.entries(c.faces || {})) {
        if (typeof rect === 'string') { problems.push(`${key}: ${c.name}.${face} 还是命名 uv 源 "${rect}"（builder 需要矩形数组）`); continue; }
        const [u, v, w, h] = rect;
        if (u < 0 || v < 0 || u + w > 64 || v + h > 128) {
          problems.push(`${key}: ${c.name}.${face} uv ${JSON.stringify(rect)} 越出 64x128`);
        }
      }
      cubeTotal++;
    }

    // 3. 四肢：上下两段必须在分界处严丝合缝，且合起来正好是原来的 12 像素
    for (const limb of LIMBS) {
      const up = spec.cubes.find((c) => c.name === limb.bone);
      const low = spec.cubes.find((c) => c.name === limb.lower);
      if (!up || !low) { problems.push(`${key}: 缺少四肢立方体 ${limb.bone} / ${limb.lower}`); continue; }
      if (up.from[1] !== low.to[1]) {
        problems.push(`${key}: ${limb.bone} 上段底面 y=${up.from[1]} 与 ${limb.lower} 下段顶面 y=${low.to[1]} 不接`);
      }
      // 四个侧面的 uv：上下两段的 V 必须首尾相接、总高等于原矩形
      for (const face of ['north', 'south', 'east', 'west']) {
        const a = up.faces[face];
        const b = low.faces[face];
        if (a[1] + a[3] !== b[1] || a[0] !== b[0] || a[2] !== b[2]) {
          problems.push(`${key}: ${limb.bone}.${face} 两段 uv 不连续: ${JSON.stringify(a)} / ${JSON.stringify(b)}`);
        }
      }
      const span = (up.to[1] - up.from[1]) + (low.to[1] - low.from[1]);
      if (span !== 12) problems.push(`${key}: ${limb.bone} 两段总长 ${span} != 12`);
    }
  }

  console.log(`\n骨架检查：${Object.keys(CHARACTERS).length} 个角色，每个 ${buildSpec(Object.keys(CHARACTERS)[0]).groups.length} 根骨骼 / ${cubeTotal / Object.keys(CHARACTERS).length} 个立方体`);
  for (const w of warn) console.log('  warn  ' + w);
  for (const p of problems) console.log('  ERROR ' + p);
  console.log(problems.length ? `${problems.length} 个问题` : '通过');
  return problems.length;
}

// 动画自检：GeckoLib 里骨骼名写错是**静默失效**（动画照播，那根骨骼就是不动），
// 所以把骨骼名、时间范围、循环首尾一致性都在离线阶段拦下来。
// 另外会提示"没有立方体的骨骼"——动它们只会带动子骨骼，不会带动网格。
function animCheck() {
  const problems = [];
  const rig = [...BASE_GROUPS, ...HAIR_GROUPS];
  const rigNames = new Set(rig.map((g) => g.name));
  const cubeOwners = new Set([...BASE_CUBES, ...OVERLAY_CUBES].map((c) => c.parent));
  const hasChild = new Set(rig.filter((g) => g.parent).map((g) => g.parent));
  // 只有**既是空骨骼、又没有子骨骼**的才是死骨头（转它什么都不会发生）。
  // root 这类空骨骼本来就是用来带动子节点的，不算问题。
  const deadBones = new Set(rig.filter((g) => !cubeOwners.has(g.name) && !hasChild.has(g.name)).map((g) => g.name));

  let keyTotal = 0;
  const baseByName = new Map(ANIMS.map((x) => [x.name, x]));
  const checkOne = (tag, a) => {
    const used = Object.keys(a.bones);
    for (const bone of used) {
      if (!rigNames.has(bone)) { problems.push(`${tag}: 骨骼 ${bone} 不在骨架里`); continue; }
      if (deadBones.has(bone)) {
        problems.push(`${tag}: ${bone} 既没有立方体也没有子骨骼，动它不会有任何效果`);
      }
      const keys = a.bones[bone];
      if (!Array.isArray(keys) || keys.length === 0) { problems.push(`${tag}.${bone}: 没有关键帧`); continue; }
      for (const k of keys) {
        keyTotal++;
        if (typeof k.time !== 'number' || k.time < 0 || k.time > a.len) {
          problems.push(`${tag}.${bone}: 时间 ${k.time} 越出 [0, ${a.len}]`);
        }
        for (const ch of ['rotation', 'position']) {
          if (!k[ch]) continue;
          if (!Array.isArray(k[ch]) || k[ch].length !== 3 || k[ch].some((v) => typeof v !== 'number')) {
            problems.push(`${tag}.${bone}@${k.time}: ${ch} 不是 3 个数字`);
          }
        }
      }
      // 循环动画：首尾姿态必须一致，否则接缝处会"跳"一下。
      // 只有一个关键帧的骨骼 = 整段恒定值，天然无缝，不用查。
      if (a.loop && keys.length > 1) {
        const first = keys[0];
        const last = keys[keys.length - 1];
        if (first.time !== 0) problems.push(`${tag}.${bone}: 循环动画缺少 t=0 关键帧`);
        if (last.time !== a.len) problems.push(`${tag}.${bone}: 循环动画缺少 t=${a.len} 关键帧`);
        if (first.rotation && last.rotation && first.rotation.join() !== last.rotation.join()) {
          problems.push(`${tag}.${bone}: 循环首尾 rotation 不一致`);
        }
        if (first.position && last.position && first.position.join() !== last.position.join()) {
          problems.push(`${tag}.${bone}: 循环首尾 position 不一致`);
        }
      }
    }
    console.log(`  ${tag.padEnd(28)} len=${a.len}s loop=${a.loop ? 'Y' : 'N'} 骨骼=${String(used.length).padStart(2)} 关键帧=${String(Object.values(a.bones).reduce((n, k) => n + k.length, 0)).padStart(3)}`);
  };

  // 基础版先过一遍
  for (const a of ANIMS) checkOne(`基础 ${a.name}`, a);

  // 再逐个角色过自己的专属版，并强制"同名动画的 len / loop 与基础版一致"
  for (const id of CHARACTER_IDS) {
    const own = overriddenNames(id);
    console.log(`  ---- ${id}：${animsFor(id).length} 段，专属 ${own.length ? own.join(', ') : '（无，全部用基础版）'}`);
    for (const name of own) {
      const a = CHARACTER_ANIMS[id][name];
      const base = baseByName.get(name);
      if (!base) { problems.push(`${id}.${name}: 基础版里没有同名动画，无法对照`); }
      else {
        if (Math.abs(a.len - base.len) > 1e-9) {
          problems.push(`${id}.${name}: len ${a.len} != 基础版 ${base.len}（Java 按动画名算 tick，三人必须一致）`);
        }
        if (!!a.loop !== !!base.loop) problems.push(`${id}.${name}: loop 与基础版不一致`);
      }
      checkOne(`  ${id}.${name}`, a);
    }
  }
  console.log(`\n动画检查：基础 ${ANIMS.length} 段 + 专属 ${CHARACTER_IDS.reduce((n, id) => n + overriddenNames(id).length, 0)} 段 / ${keyTotal} 个关键帧`);
  for (const p of problems) console.log('  ERROR ' + p);
  console.log(problems.length ? `${problems.length} 个问题` : '通过');
  return problems.length;
}

if (process.argv.includes('--check')) {
  const bad = offlineCheck() + animCheck();
  process.exit(bad ? 1 : 0);
}

const [phase, key, extra] = process.argv.slice(2);
await initialize();

if (phase === 'make') await phaseMake(key);
else if (phase === 'rig') await phaseRig(key);
else if (phase === 'shot') await phaseShot(key);
else if (phase === 'export') await phaseExport(key);
else if (phase === 'call') console.log(textOf(await callTool(extra, JSON.parse(process.argv[4] || '{}'))));
else {
  console.log('usage: node pipeline.mjs make|rig|shot|export|call <character> [tool] [json]');
  console.log('       node pipeline.mjs --check            # 离线自检，不连 Blockbench');
  console.log('characters: ' + Object.keys(CHARACTERS).join(', '));
}
