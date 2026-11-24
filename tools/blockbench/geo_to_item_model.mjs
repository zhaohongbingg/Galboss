import fs from 'node:fs';
import path from 'node:path';

// 把清理过的武器 geo 转成原版 item model（1.20.1）。
//
// 为什么走物品：位置/朝向交给原版物品系统的 display 变换，不需要渲染层算矩阵，
// 也不存在「GeoModel 用角色当 animatable、骨骼被角色动画按名字套上」的自转问题。
//
// ============================ 三条硬约束 ============================
//
// 【1】UV 必须换算到 0..16 的归一化空间
//   原版 BlockModel.Deserializer **根本不读 texture_size**（整个 1.20.1 的 class 里
//   都搜不到这个字符串），面的 uv 由 BlockFaceUV.Deserializer 原样读入，
//   最后经 TextureAtlasSprite 映射到贴图：
//       getU(u) = u0 + (u1 - u0) * u / 16
//       getV(v) = v0 + (v1 - v0) * v / 16
//   即 uv 的单位是「整张贴图的 1/16」，**不是像素**。
//   -> uv16 = uv像素 * 16 / TEX_SIZE
//   之前直接把 512 空间里的像素值写进去（最大到 114），全部落到 sprite 外，
//   表现就是「武器像没贴图 / 采到别的贴图碎片」。
//
// 【2】坐标系：只把 X 取反，Y/Z 原样
//   GeckoLib 的 BakedModelFactory 载入 bedrock geo 时做的是 X 镜像：
//       bone.pivot  = (-x,  y,  z)
//       bone.rot    = (-x, -y,  z)
//       cube.origin = (-(x + w), y, z)
//   所以 geckolib 模型空间 = bedrock 空间做 X 镜像，而 GeckoLib 的物品模型是渲染在
//   骨骼帧里的（就是那套原版模型空间），因此 geo -> item model 只需 x -> -x。
//   （曾经误以为 item model 的 Y 是向下的、对 Y 做了镜像，结果整把武器上下颠倒。）
//
// 【3】握把（手握住的那一点）要落在物品空间的中心 (8,8,8)
//   原版物品渲染的顺序是：先套 display 变换（绕**模型中心**做旋转/缩放），
//   再 T(-0.5,-0.5,-0.5)。把握把放在模型中心，旋转轴/缩放轴就正好落在握把上 ——
//   无论 display 怎么转，握把都停在骨骼原点（= weapon_anchor 的枢轴 = 手心）。
//
// 用法:
//   node geo_to_item_model.mjs <源geo> <输出item模型> <贴图名> [缩放] [握把x,y,z]
// 环境变量:
//   TEX_SIZE  贴图边长（默认 512）
// 不传握把时按「长轴最长的那一节立方体的中心」自动取（武士刀正好是刀柄）。
// ===================================================================

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const RES = path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'galboss');

const [srcGeo, outModel, texName, scaleArg, gripArg] = [
  process.argv[2],
  process.argv[3],
  process.argv[4],
  process.argv[5],
  process.argv[6]
];

if (!srcGeo || !outModel || !texName) {
  console.error('用法: node geo_to_item_model.mjs <源geo> <输出item模型> <贴图名> [缩放] [握把x,y,z]');
  process.exit(1);
}

const SCALE = scaleArg ? Number(scaleArg) : 0.45;
// TEX_SIZE 支持 "512"（正方形）与 "128x64"（非正方形：烟斗的图集就是 128x64）。
// 原版 uv 的单位是「整张贴图的 1/16」，u / v 两个轴各按自己的贴图边长归一化，
// 所以非正方形贴图不能只给一个边长 —— 枪和刀都是正方形贴图，历史上才只需要一个数。
const TEX = (process.env.TEX_SIZE || '512').split('x').map(Number);
const TEX_W = TEX[0];
const TEX_H = TEX[1] ?? TEX[0];
/** geo 的 uv 是「贴图像素」，item model 的 uv 是「整张贴图的 1/16」。 */
const UV_U = 16 / TEX_W;
const UV_V = 16 / TEX_H;

const geo = JSON.parse(fs.readFileSync(path.join(RES, 'geo', srcGeo), 'utf8'));
const g = geo['minecraft:geometry'][0];
const bones = g.bones;

// 收集全部立方体（武器已清理为空骨骼+几何骨骼，直接拍平）
const cubes = [];
for (const b of bones) {
  if (b.cubes) cubes.push(...b.cubes);
}
console.log(`源: ${srcGeo}  ->  ${cubes.length} 个立方体`);
console.log(`贴图: ${texName} ${TEX_W}x${TEX_H}  uv 换算系数 u=${UV_U} v=${UV_V}  缩放 ${SCALE}`);

// 拍平是以「cube 的绝对坐标」为前提的，骨骼旋转不会被重建 —— 非零就要提醒
const boneRot = bones.filter((b) => b.rotation && b.rotation.some((v) => v !== 0));
if (boneRot.length) {
  console.log(`⚠ ${boneRot.length} 个骨骼自带旋转，拍平时会被丢掉（几何变换请写在 cube 上）: `
    + boneRot.map((b) => `${b.name}${JSON.stringify(b.rotation)}`).join(' '));
}

// ---- 长轴 & 握把 ----
const spans = [0, 1, 2].map((i) => {
  const lo = Math.min(...cubes.map((c) => c.origin[i]));
  const hi = Math.max(...cubes.map((c) => c.origin[i] + c.size[i]));
  return hi - lo;
});
const AXIS = spans.indexOf(Math.max(...spans));

let grip;
if (gripArg) {
  grip = gripArg.split(',').map(Number);
  if (grip.length !== 3 || grip.some((v) => !Number.isFinite(v))) {
    console.error(`握把参数要写成 x,y,z，收到 "${gripArg}"`);
    process.exit(1);
  }
  console.log(`长轴=${'XYZ'[AXIS]}  握把（手写）=${JSON.stringify(grip)}`);
} else {
  // 长轴上最长的那一节（武士刀 = 刀柄 13.9）
  const longest = [...cubes].sort((a, b) => b.size[AXIS] - a.size[AXIS])[0];
  grip = longest.origin.map((v, i) => v + longest.size[i] / 2);
  console.log(`长轴=${'XYZ'[AXIS]}  握把（自动：长轴最长立方体的中心）=${JSON.stringify(grip.map((v) => +v.toFixed(2)))}`);
  console.log(`  该立方体 origin=${JSON.stringify(longest.origin.map((v) => +v.toFixed(2)))} size=${JSON.stringify(longest.size.map((v) => +v.toFixed(2)))}`);
}

/**
 * geo 坐标 -> item 模型坐标。
 * X 取反（见【2】），三轴都相对握把缩放，再把握把搬到 (8,8,8)。
 */
const map = (value, i) => {
  const rel = (i === 0 ? -value : value) - (i === 0 ? -grip[i] : grip[i]);
  return Math.round((8 + rel * SCALE) * 1000) / 1000;
};

const uvU = (v) => Math.round(v * UV_U * 1000) / 1000;
const uvV = (v) => Math.round(v * UV_V * 1000) / 1000;

const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
let negativeUv = 0;
let rotated = 0;
const multiAxis = [];
/**
 * 原版 `BlockElement.Deserializer.getAngle` 只认 **0 / ±22.5 / ±45**，别的角度会让
 * **整个模型反序列化抛异常** —— 表现不是「某面歪了」，而是模型根本不加载：
 * 手上顶着一个 missing 模型的紫黑棋盘格方块。贝阿朵莉切的烟斗来自自由旋转建模
 * （环状装饰是 0/45/90/135/… 一圈摆出来的），这些角度原版表达不了，
 * 只能丢掉旋转、把零件退回各自轴对齐的盒子（靠重叠维持体积）。
 */
const LEGAL_ANGLES = new Set([0, 22.5, -22.5, 45, -45]);
const illegalAngles = new Map();
const noteIllegalAngle = (angle) => illegalAngles.set(angle, (illegalAngles.get(angle) ?? 0) + 1);

/**
 * geo 立方体自带旋转 -> 原版 element 旋转。
 *
 * 这是武器模型最容易漏的一环：清理脚本只清了**骨骼**旋转，TACZ 的枪有 101 个立方体
 * 是**自带旋转**的（±22.5 / ±45，绕自己的 pivot）。GeckoLib 逐 cube 应用它
 * （renderCube -> rotateMatrixAroundCube），所以旧方案是正常的；转换时如果直接拍平，
 * 这些零件就全歪了 —— 表现就是「模型乱、贴图跟着乱」。武士刀是 0 个，所以没事。
 *
 * 对应关系（两边都是右手系）：
 *   geckolib: Quaternionf().rotationXYZ(toRadians(-rx), toRadians(-ry), toRadians(rz))
 *   原版    : new Quaternionf().rotationAxis(toRadians(angle), axis)，origin = pivot
 * 所以单轴时 angle = rx * -1 / ry * -1 / rz * +1；pivot 走和几何一样的 map()。
 */
function toElementRotation(c, warn) {
  const r = c.rotation;
  if (!r || !r.some((v) => v !== 0)) return undefined;
  const nz = [0, 1, 2].filter((i) => r[i] !== 0);
  if (nz.length > 1) {
    // 原版一个 element 只能绕单轴转，多轴没法表达
    warn.push(JSON.stringify(r));
    return undefined;
  }
  const i = nz[0];
  const axis = 'xyz'[i];
  // 归一到 (-180, 180] 再判定是否落在原版允许的档位上
  let angle = r[i] * (axis === 'z' ? 1 : -1);
  angle = ((angle % 360) + 540) % 360 - 180;
  angle = angle === -180 ? 180 : Math.round(angle * 1000) / 1000;
  if (!LEGAL_ANGLES.has(angle)) {
    noteIllegalAngle(angle);
    return undefined;
  }
  const pivot = c.pivot ?? [0, 0, 0];
  rotated++;
  return {
    origin: [map(pivot[0], 0), map(pivot[1], 1), map(pivot[2], 2)],
    axis,
    angle
  };
}

const elements = cubes.map((c) => {
  // Bedrock 的 inflate 是「往外撑 inflate 个模型单位」，原版没有这个字段，
  // 直接把 from/to 各撑开同样多（缩放后）即可 —— 语义一致
  const inf = (c.inflate ?? 0) * SCALE;
  const a = [map(c.origin[0], 0) - inf, map(c.origin[1], 1) - inf, map(c.origin[2], 2) - inf];
  const b = [
    map(c.origin[0] + c.size[0], 0) + inf,
    map(c.origin[1] + c.size[1], 1) + inf,
    map(c.origin[2] + c.size[2], 2) + inf
  ];
  // X 镜像会让某一端的顺序颠倒，统一成 from < to
  const lo = a.map((v, i) => Math.min(v, b[i]));
  const hi = a.map((v, i) => Math.max(v, b[i]));

  const faces = {};
  for (const f of FACES) {
    const src = c.uv?.[f];
    if (!src) continue;
    const [u, v] = src.uv;
    const [w, h] = src.uv_size;
    if (w < 0 || h < 0) negativeUv++;
    // 直接搬：uv 矩形左上角 + 尺寸。uv_size 为负时保留（原版用 [u0,v0,u1,v1] 四点，
    // 允许 u0>u1，天然表示镜像，不需要归一化）。
    faces[f] = { uv: [uvU(u), uvV(v), uvU(u + w), uvV(v + h)], texture: '#0' };
  }

  const element = { from: lo, to: hi, faces };
  const rotation = toElementRotation(c, multiAxis);
  if (rotation) element.rotation = rotation;
  return element;
});

// ---- display 预设（要先定义：下面 GUI 的朝向也从这里取） ----
// 键 = 输出模型文件名。模型空间约定（= 骨骼帧 = 原版实体模型空间）：
//   +Y 上、-Z 角色正面、-X 角色右手侧（和原版 PlayerModel 的 right_arm 在 -X 一致）。
// 实测 joml 的 Quaternionf.rotationXYZ(x,y,z)（原版 ItemTransform.apply 就是用它）：
//   rot(0,  0,  0) 下 item 的 +Y -> (0, 1,     0)      纯向上
//   rot(0,-90, 55) 下 item 的 +Y -> (0, 0.574, -0.819) 向上并向前倾 55°
//   rot(0,-90,  0) 下 item 的 -Z -> (1, 0,     0)      甩到 +X 上
//   rot(-30,0,  0) 下 item 的 -Z -> (0, -0.5, -0.866)  朝前并下压 30°
// 注意 yaw=-90 会把 +Z/-Z 甩到 X 上，所以带枪管的模型**不能**照抄 handheld 的那组角度。
//
// guiRotation 只影响物品栏图标，不影响世界里的持握。
const DISPLAY = {
  // 刀：刀身在模型空间沿 +Y（刀尖朝上），套原版 item/handheld 的第三人称旋转
  'ikuko_katana.json': {
    guiRotation: [0, 0, -45],
    fixedRotation: [0, 0, -45],
    groundRotation: [0, 0, 45],
    groundScale: 0.55,
    thirdperson_righthand: { rotation: [0, -90, 55], scale: [1, 1, 1] },
    thirdperson_lefthand: { rotation: [0, 90, -55], scale: [1, 1, 1] },
    firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    firstperson_lefthand: { rotation: [0, 90, -25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] }
  },
  // 枪：枪管沿 -Z（朝角色正面），所以**不转**就已经指向前方 —— 和旧方案（geckolib 直接按
  //     geo 原点摆放、骨骼旋转已清零）的姿态一致。想让它口下压就改这一项，例如 [-30,0,0]。
  'rei_gun.json': {
    // gui / fixed 绕 Y 转 -90°：把枪管摆到屏幕横向上，图标和展示框才是侧面剪影。
    guiRotation: [0, -90, 0],
    fixedRotation: [0, -90, 0],
    groundRotation: [0, 0, 0],
    groundScale: 0.75,
    thirdperson_righthand: { rotation: [0, 0, 0], scale: [1, 1, 1] },
    thirdperson_lefthand: { rotation: [0, 0, 0], scale: [1, 1, 1] },
    firstperson_righthand: { rotation: [0, 0, 0], translation: [0.5, 2.5, -1.0], scale: [0.85, 0.85, 0.85] },
    firstperson_lefthand: { rotation: [0, 0, 0], translation: [0.5, 2.5, -1.0], scale: [0.85, 0.85, 0.85] }
  },
  // 烟斗：长轴沿 X（geo 空间里斗在 +X 端、嘴含的那头在 -X 端），物品空间 x 又被取反，
  //   所以斗落在 -X 侧、嘴那头在 +X 侧。握把定在靠嘴那段的杆上，模型从手心往前伸出去。
  //   绕 Y 转 -90°：R_y(-90) 把 -X 轴甩到 -Z（角色正面），于是斗朝前、杆收进手心。
  //   GUI 图标不转 —— 长轴本来就在画面横向上，正好是侧视剪影。
  'beatrice_pipe.json': {
    guiRotation: [0, 0, 0],
    fixedRotation: [0, 0, 0],
    groundRotation: [0, 0, 0],
    groundScale: 0.75,
    thirdperson_righthand: { rotation: [0, -90, 0], scale: [1, 1, 1] },
    thirdperson_lefthand: { rotation: [0, -90, 0], scale: [1, 1, 1] },
    firstperson_righthand: { rotation: [0, -90, 0], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    firstperson_lefthand: { rotation: [0, -90, 0], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] }
  }
};

const preset = { ...(DISPLAY[outModel] ?? {}) };
const guiRot = preset.guiRotation ?? [0, 0, 0];
const fixedRot = preset.fixedRotation ?? guiRot;
const groundRot = preset.groundRotation ?? [0, 0, 0];
const groundScale = preset.groundScale ?? 0.65;
delete preset.guiRotation;
delete preset.fixedRotation;
delete preset.groundRotation;
delete preset.groundScale;

// ---- GUI 展示：整把武器缩放进 16³ 的物品格并居中（物品栏图标用） ----
// 变换语义见【3】：v' = R * S * (v - 模型中心) + translation，
// 旋转/缩放都绕模型中心，translation 单位与模型坐标一致（原版读 JSON 时再 ×0.0625 换算成格）。
//
// 原版 GuiGraphics 画物品只做 translate + scale(16,-16,16)，**不带旋转**，
// 相机沿 -Z 看，看到的是模型 +Z 那一侧。所以长轴沿 Z 的模型在图标里会被完全压缩掉，
// 必须靠 guiRotation 把它摆平 —— 这就是给枪加 guiRotation 的原因。
const r3 = (v) => Math.round(v * 1000) / 1000;

/** joml Quaternionf.rotationXYZ(rx,ry,rz) == 矩阵 Rx·Ry·Rz，即先 Rz 再 Ry 再 Rx。 */
const rotXYZ = ([rx, ry, rz], [x, y, z]) => {
  const rad = Math.PI / 180;
  const a = rx * rad;
  const b = ry * rad;
  const c = rz * rad;
  let p = [x * Math.cos(c) - y * Math.sin(c), x * Math.sin(c) + y * Math.cos(c), z];
  p = [p[0] * Math.cos(b) + p[2] * Math.sin(b), p[1], -p[0] * Math.sin(b) + p[2] * Math.cos(b)];
  return [p[0], p[1] * Math.cos(a) - p[2] * Math.sin(a), p[1] * Math.sin(a) + p[2] * Math.cos(a)];
};

const lo = [0, 1, 2].map((i) => Math.min(...elements.map((e) => e.from[i])));
const hi = [0, 1, 2].map((i) => Math.max(...elements.map((e) => e.to[i])));

// 所有角点相对模型中心旋转后的包围盒（旋转会改变投影大小，居中也要按旋转后的算）
// 注意：element 自己的 rotation 会把顶点带出 from/to 的盒子（比如后倾的握把），
// 所以这里先把每个元素按它自己的旋转摆一遍再取包围盒。
const elemCorners = (e) => {
  const pts = [];
  for (const x of [e.from[0], e.to[0]]) {
    for (const y of [e.from[1], e.to[1]]) {
      for (const z of [e.from[2], e.to[2]]) pts.push([x, y, z]);
    }
  }
  if (!e.rotation) return pts;
  const { origin, axis, angle } = e.rotation;
  const rot = axis === 'x' ? [angle, 0, 0] : axis === 'y' ? [0, angle, 0] : [0, 0, angle];
  return pts.map((p) => {
    const d = rotXYZ(rot, [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]]);
    return [d[0] + origin[0], d[1] + origin[1], d[2] + origin[2]];
  });
};

const fitSlot = (rot, targetSpan = 15) => {
  const rel = [];
  for (const e of elements) {
    for (const p of elemCorners(e)) rel.push(rotXYZ(rot, [p[0] - 8, p[1] - 8, p[2] - 8]));
  }
  const rlo = [0, 1, 2].map((i) => Math.min(...rel.map((c) => c[i])));
  const rhi = [0, 1, 2].map((i) => Math.max(...rel.map((c) => c[i])));
  const scale = r3(targetSpan / Math.max(...rhi.map((v, i) => v - rlo[i])));
  const trans = [0, 1, 2].map((i) => r3(-scale * ((rlo[i] + rhi[i]) / 2)));
  return { rotation: rot, translation: trans, scale: [scale, scale, scale] };
};

const guiTransform = fitSlot(guiRot, 15);
const fixedTransform = fitSlot(fixedRot, 14);
const groundCenter = (() => {
  const rel = [];
  for (const e of elements) {
    for (const p of elemCorners(e)) rel.push(rotXYZ(groundRot, [p[0] - 8, p[1] - 8, p[2] - 8]));
  }
  const rlo = [0, 1, 2].map((i) => Math.min(...rel.map((c) => c[i])));
  const rhi = [0, 1, 2].map((i) => Math.max(...rel.map((c) => c[i])));
  return {
    rotation: groundRot,
    translation: [
      r3(-groundScale * ((rlo[0] + rhi[0]) / 2)),
      r3(-groundScale * rlo[1] + 1.5),
      r3(-groundScale * ((rlo[2] + rhi[2]) / 2))
    ],
    scale: [groundScale, groundScale, groundScale]
  };
})();

console.log(`物品空间包围盒 from=${JSON.stringify(lo)} to=${JSON.stringify(hi)}`);
console.log(`gui 展示: rotation=${JSON.stringify(guiRot)} scale=${guiTransform.scale[0]} translation=${JSON.stringify(guiTransform.translation)}`);
if (negativeUv) console.log(`⚠ 有 ${negativeUv} 个面的 uv_size 为负（已按镜像保留）`);
if (rotated) console.log(`立方体自带旋转: ${rotated} 个已转成原版 element rotation`);
if (multiAxis.length) {
  console.log(`⚠ 有 ${multiAxis.length} 个立方体是多轴旋转，原版一个 element 只能绕单轴，已忽略: ${[...new Set(multiAxis)].join(' ')}`);
}
if (illegalAngles.size) {
  const total = [...illegalAngles.values()].reduce((a, b) => a + b, 0);
  console.log(`⚠ 有 ${total} 个立方体的旋转不在原版合法档位（0/±22.5/±45），已丢掉旋转、保留轴对齐盒子（否则整个模型加载失败、手上只剩 missing 方块）: `
    + [...illegalAngles.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}°×${v}`).join(' '));
}

const display = {
  ...preset,
  // 第三人称的 translation 一律留空：握把已经在模型中心，落在骨骼原点即为手心。
  gui: guiTransform,
  fixed: fixedTransform,
  ground: groundCenter
};
for (const [k, v] of Object.entries(display)) {
  v.translation = v.translation ?? [0, 0, 0];
}

const model = {
  credit: `generated from ${srcGeo} by geo_to_item_model.mjs —— uv 已换算到 0..16 归一化空间（原版忽略 texture_size，见脚本注释）`,
  texture_size: [TEX_W, TEX_H],
  gui_light: 'front',
  textures: { '0': `galboss:item/${texName}`, particle: `galboss:item/${texName}` },
  elements,
  display
};

const outPath = path.join(RES, 'models', 'item', outModel);
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(model, null, 2));

// 贴图挪到 item 目录
const srcTex = path.join(RES, 'textures', 'entity', `${texName}.png`);
const dstTex = path.join(RES, 'textures', 'item', `${texName}.png`);
if (fs.existsSync(srcTex)) {
  fs.mkdirSync(path.dirname(dstTex), { recursive: true });
  fs.copyFileSync(srcTex, dstTex);
  console.log(`贴图 ${path.relative(RES, dstTex)}`);
} else {
  console.log(`⚠ 未找到贴图 ${srcTex}`);
}

console.log(`写出 models/item/${outModel}（${elements.length} 个 elements）`);
