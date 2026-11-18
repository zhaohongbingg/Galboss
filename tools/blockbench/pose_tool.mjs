import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import zlib from 'node:zlib';

// ============================================================================
// GeckoLib 姿态工具：不启动 Blockbench，直接对 geo + animation 做「求值 → 出图 / 穿模体检」
//
// 为什么需要它：Blockbench 靠 MCP 一次改一个骨骼太慢，而「抬手会不会穿模」「走路腿穿过裙子」
// 这类问题本质是几何问题 —— 与其进游戏肉眼找，不如在这里把任意时刻的骨骼姿态算出来，
// 再用立方体 AABB 相交量量化穿模，并用软件光栅器出图确认。
//
// 求值语义（对齐 GeckoLib 4.4.2 的 AnimationProcessor/BoneSnapshot）：
//   - rotation 通道是**绝对**值（会覆盖模型里骨骼的静止角度，所以动画里要写上静止角度）
//   - position 通道是**偏移**（相对静止姿态的位移，单位 1/16 格）
//   - 关键帧之间线性插值；Molang 只支持本仓库实际用到的 Math.sin/cos/abs/max/min + query.anim_time
//   - loop=true 的动画按 animation_length 取模；hold_on_last_frame 超出后钳在最后一帧
//
// 用法：
//   node pose_tool.mjs <geo.json> --anim <animations.json> --name <动画名> --t 0.3 \
//        [--pose '{"RightArm":{"rotation":[0,0,40]}}'] [--render out.png] [--views front,side,three] \
//        [--clip RightArm:Skirt] [--size 800]
//   --pose 里的骨骼会覆盖动画值（用来试角度）；--clip 会对指定两组骨骼做 AABB 相交统计
// ============================================================================

// ---------------------------------------------------------------- PNG
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
const crc32 = (buf) => {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
};
function decodePng(buf) {
  let pos = 8, ihdr = null;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') ihdr = { width: data.readUInt32BE(0), height: data.readUInt32BE(4), bitDepth: data[8], colorType: data[9], interlace: data[12] };
    else if (type === 'IDAT') idat.push(Buffer.from(data));
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType];
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = ihdr.width * channels;
  const pixels = Buffer.alloc(ihdr.height * stride);
  for (let y = 0; y < ihdr.height; y++) {
    const filter = raw[y * (stride + 1)];
    const src = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    const dst = pixels.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? pixels.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? dst[x - channels] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= channels ? prev[x - channels] : 0;
      let v = src[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      dst[x] = v & 0xff;
    }
  }
  return { width: ihdr.width, height: ihdr.height, channels, pixels };
}
function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const chunk = (type, data) => {
    const out = Buffer.alloc(12 + data.length);
    out.writeUInt32BE(data.length, 0);
    out.write(type, 4, 'ascii');
    data.copy(out, 8);
    out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 8 + data.length);
    return out;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))
  ]);
}

// ---------------------------------------------------------------- 数学
const rad = (d) => (d * Math.PI) / 180;
const mul = (a, b) => a.map((row, i) => b[0].map((_, j) => row.reduce((s, v, k) => s + v * b[k][j], 0)));
const apply = (m, p) => [
  m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3],
  m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3],
  m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3]
];
const I4 = () => [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
const T = (t) => [[1, 0, 0, t[0]], [0, 1, 0, t[1]], [0, 0, 1, t[2]], [0, 0, 0, 1]];
const S = (s) => [[s[0], 0, 0, 0], [0, s[1], 0, 0], [0, 0, s[2], 0], [0, 0, 0, 1]];
/** GeckoLib / THREE 'XYZ'：R = Rx*Ry*Rz */
const R = ([rx, ry, rz]) => {
  const [a, b, c] = [rad(rx), rad(ry), rad(rz)];
  const Rx = [[1, 0, 0, 0], [0, Math.cos(a), -Math.sin(a), 0], [0, Math.sin(a), Math.cos(a), 0], [0, 0, 0, 1]];
  const Ry = [[Math.cos(b), 0, Math.sin(b), 0], [0, 1, 0, 0], [-Math.sin(b), 0, Math.cos(b), 0], [0, 0, 0, 1]];
  const Rz = [[Math.cos(c), -Math.sin(c), 0, 0], [Math.sin(c), Math.cos(c), 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]];
  return mul(mul(Rx, Ry), Rz);
};
const aroundPivot = (pivot, rot) => mul(mul(T(pivot), rot), T(pivot.map((v) => -v)));

// ---------------------------------------------------------------- 参数
const args = process.argv.slice(2);
const geoPath = args[0];
const opt = { anim: null, name: null, t: 0, pose: null, render: null, views: ['front', 'side', 'three'], clip: null, size: 800, texture: null, show: null, item: null, anchor: null, itemTexture: null };
for (let i = 1; i < args.length; i++) {
  const a = args[i];
  if (a === '--anim') opt.anim = args[++i];
  else if (a === '--name') opt.name = args[++i];
  else if (a === '--t') opt.t = Number(args[++i]);
  else if (a === '--pose') opt.pose = JSON.parse(args[++i]);
  else if (a === '--pose-file') opt.pose = JSON.parse(readFileSync(args[++i], 'utf8'));
  else if (a === '--render') opt.render = args[++i];
  else if (a === '--views') opt.views = args[++i].split(',');
  else if (a === '--clip') opt.clip = args[++i];
  else if (a === '--size') opt.size = Number(args[++i]);
  else if (a === '--texture') opt.texture = args[++i];
  else if (a === '--show') opt.show = args[++i].split(',');
  else if (a === '--item') opt.item = args[++i];
  else if (a === '--anchor') opt.anchor = args[++i];
  else if (a === '--item-texture') opt.itemTexture = args[++i];
  else if (a === '--item-disp-file') opt.itemDisp = JSON.parse(readFileSync(args[++i], 'utf8'));
  else if (a === '--search-disp') opt.searchDisp = true;
  else if (a === '--game-space') opt.gameSpace = true;
}
if (!geoPath) {
  console.error('用法: node pose_tool.mjs <geo.json> [--anim a.json --name idle --t 0.3] [--pose json] [--render out.png] [--clip A:B]');
  process.exit(1);
}
const geo = JSON.parse(readFileSync(geoPath, 'utf8'))['minecraft:geometry'][0];
const TEX_W = geo.description.texture_width;
const TEX_H = geo.description.texture_height;

// ---------------------------------------------------------------- Molang（够用就好）
function evalMolang(expr, animTime) {
  if (typeof expr === 'number') return expr;
  // 动画里写的是 Molang 风格：Math.sin(...)（角度制）+ query.anim_time。
  // 这里剥掉 Math. 前缀，换成我们自己按角度制实现的同名函数。
  const src = String(expr).replace(/query\.anim_time/g, 'anim_time').replace(/Math\./g, '');
  const fn = new Function('sin', 'cos', 'abs', 'max', 'min', 'anim_time', `return (${src});`);
  return fn(
    (x) => Math.sin(rad(x)),
    (x) => Math.cos(rad(x)),
    Math.abs, Math.max, Math.min,
    animTime
  );
}
/** 取通道在 t 时刻的 [x,y,z]；常量通道直接给值，关键帧通道线性插值。
 *  注意：JSON 里的时间键是字符串（"0.0"/"0.5"/"1.0"），排序要用 Number 比较，
 *  但回查 channel 必须用**原始字符串键**，否则 String(Number(key)) 与键格式不一致会取到 undefined。 */
function sampleChannel(channel, t) {
  if (!channel) return null;
  if (Array.isArray(channel)) return channel.map((v) => evalMolang(v, t));
  if (channel.vector) {
    if (Object.keys(channel).length === 1) return channel.vector.map((v) => evalMolang(v, t));
  }
  const keyStrs = Object.keys(channel).filter((k) => k !== 'vector').sort((a, b) => Number(a) - Number(b));
  const times = keyStrs.map(Number);
  if (!times.length) return channel.vector ? channel.vector.map((v) => evalMolang(v, t)) : null;
  const vecAt = (k) => (channel[k].vector ?? channel[k]).map((v) => evalMolang(v, t));
  if (t <= times[0]) return vecAt(keyStrs[0]);
  if (t >= times[times.length - 1]) return vecAt(keyStrs[keyStrs.length - 1]);
  let i = 0;
  while (i < times.length - 1 && times[i + 1] <= t) i++;
  const t0 = times[i], t1 = times[i + 1];
  const w = t1 === t0 ? 0 : (t - t0) / (t1 - t0);
  const v0 = vecAt(keyStrs[i]), v1 = vecAt(keyStrs[i + 1]);
  return v0.map((v, k) => v + (v1[k] - v) * w);
}

// ---------------------------------------------------------------- 姿态求值
const byName = new Map(geo.bones.map((b) => [b.name, b]));
let animTime = opt.t;
let duration = Infinity;
let loop = false;
let animBones = {};
if (opt.anim && opt.name) {
  const anims = JSON.parse(readFileSync(opt.anim, 'utf8')).animations;
  const anim = anims[opt.name];
  if (!anim) {
    console.error(`动画不存在: ${opt.name}（可选: ${Object.keys(anims).join(', ')}）`);
    process.exit(1);
  }
  duration = anim.animation_length ?? Infinity;
  loop = anim.loop === true;
  animBones = anim.bones ?? {};
  if (loop && duration !== Infinity) animTime = ((opt.t % duration) + duration) % duration;
  else if (duration !== Infinity) animTime = Math.min(opt.t, duration);
}
const overrides = opt.pose ?? {};
/**
 * 游戏空间：GeckoLib 载入 geo 时对骨骼 pivot/rotation 做 X 取反（x、y 取反、z 保留），
 * 动画值则按文件里写的直接套。也就是说「文件里的动画值」和「geo 里的静止角度」不在同一套符号里，
 * 想离线复现游戏画面，要么两边都取反，要么都不取反 —— 这里用 --game-space 统一模拟：
 * 动画 rotation 的 X/Y 取反、geo 骨骼 rest rotation 也取反（等价于把整个模型镜像回来）。
 */
const fixRot = (r) => (opt.gameSpace ? [-r[0], -r[1], r[2]] : r);
const poseOf = (boneName) => {
  const a = animBones[boneName] ?? {};
  const o = overrides[boneName] ?? {};
  const rot = o.rotation ?? (a.rotation ? sampleChannel(a.rotation, animTime) : null);
  return {
    rotation: rot ? fixRot(rot) : null,
    position: o.position ?? (a.position ? sampleChannel(a.position, animTime) : null),
    scale: o.scale ?? (a.scale ? sampleChannel(a.scale, animTime) : null)
  };
};

const worldOf = new Map();
const resolve = (bone, depth = 0) => {
  if (worldOf.has(bone.name)) return worldOf.get(bone.name);
  const pose = poseOf(bone.name);
  const pivot = bone.pivot ?? [0, 0, 0];
  const rot = R(pose.rotation ?? bone.rotation ?? [0, 0, 0]);
  const scale = pose.scale ?? [1, 1, 1];
  const offset = pose.position ?? [0, 0, 0];
  const local = mul(mul(aroundPivot(pivot, rot), S(scale)), T(offset));
  const parent = bone.parent ? byName.get(bone.parent) : null;
  const m = parent && depth < 64 ? mul(resolve(parent, depth + 1), local) : local;
  worldOf.set(bone.name, m);
  return m;
};

// ---------------------------------------------------------------- 收集立方体
const cubes = [];
for (const bone of geo.bones) {
  if (!bone.cubes) continue;
  const bm = resolve(bone);
  for (const cube of bone.cubes) {
    // 立方体自带旋转的"表示法转换"：geo 是 Blockbench compile 出来的镜像空间
    // （importer 把 rotation 的 X/Y 取反、Z 保留），而 GeckoLib 载入时会再镜像一次
    // （BakedModelFactory.constructCube: rot=(-x,-y,z)），两层叠加后游戏里等于编辑器里的角度。
    // pose_tool 直接渲染 geo，所以这里要按「geo 空间里镜像后的旋转」来应用：
    // 正确值 = (-gx, gy, -gz)（= M·R·M）。不这样处理，任何带 cube rotation 的模型
    // （黑山羊的羊角、贝阿朵莉切的 1839 个旋转立方体）都会被画歪/画断。
    const cm = cube.rotation
      ? mul(bm, aroundPivot(cube.pivot ?? [0, 0, 0], R([-cube.rotation[0], cube.rotation[1], -cube.rotation[2]])))
      : bm;
    const o = cube.origin;
    const s = cube.size;
    const pts = [];
    for (const dx of [0, s[0]]) for (const dy of [0, s[1]]) for (const dz of [0, s[2]]) pts.push(apply(cm, [o[0] + dx, o[1] + dy, o[2] + dz]));
    const lo = [0, 1, 2].map((i) => Math.min(...pts.map((p) => p[i])));
    const hi = [0, 1, 2].map((i) => Math.max(...pts.map((p) => p[i])));
    cubes.push({ bone: bone.name, lo, hi, cube, pts, cm });
  }
}

// ---------------------------------------------------------------- 骨骼世界位置
if (opt.show) {
  console.log('\n骨骼世界位置（pivot 与所属立方体包围盒，模型单位 1/16 格）:');
  for (const name of opt.show) {
    const bone = byName.get(name);
    if (!bone) { console.log(`  ${name}: (不存在)`); continue; }
    const m = resolve(bone);
    const p = apply(m, bone.pivot ?? [0, 0, 0]);
    const own = cubes.filter((c) => c.bone === name);
    let bbox = '-';
    if (own.length) {
      const lo = [0, 1, 2].map((i) => Math.min(...own.map((c) => c.lo[i])));
      const hi = [0, 1, 2].map((i) => Math.max(...own.map((c) => c.hi[i])));
      bbox = `cube[${lo.map((v) => v.toFixed(1)).join(',')}]..[${hi.map((v) => v.toFixed(1)).join(',')}]`;
    }
    console.log(`  ${name.padEnd(24)} pivot@[${p.map((v) => v.toFixed(2)).join(', ')}]  ${bbox}`);
  }
}

// ---------------------------------------------------------------- 穿模体检
// 用 AABB 相交量会严重高估：立方体一旦被旋转，AABB 被撑大，隔着一大截也会被算成相交。
// 这里改成真正的体积采样判定 —— 把 A 的立方体体积按 3×3×3 采样点，逐点变换到 B 的局部坐标系，
// 落在 B 的 [origin, origin+size] 内才算穿透；反过来再采一次 B 提高灵敏度。
const rigidInverseOf = (m) => ({
  Rt: [[m[0][0], m[1][0], m[2][0]], [m[0][1], m[1][1], m[2][1]], [m[0][2], m[1][2], m[2][2]]],
  t: [m[0][3], m[1][3], m[2][3]]
});
const toLocal = (inv, p) => {
  const q = [p[0] - inv.t[0], p[1] - inv.t[1], p[2] - inv.t[2]];
  return [
    inv.Rt[0][0] * q[0] + inv.Rt[0][1] * q[1] + inv.Rt[0][2] * q[2],
    inv.Rt[1][0] * q[0] + inv.Rt[1][1] * q[1] + inv.Rt[1][2] * q[2],
    inv.Rt[2][0] * q[0] + inv.Rt[2][1] * q[1] + inv.Rt[2][2] * q[2]
  ];
};
const SAMPLES = [0.2, 0.5, 0.8];
/** A 有多少采样点落在 B 内部（A/B 都是 {cube, cm, inv}） */
const penetrationSamples = (a, b) => {
  const o = b.cube.origin;
  const s = b.cube.size;
  let inside = 0;
  for (const fx of SAMPLES) for (const fy of SAMPLES) for (const fz of SAMPLES) {
    const world = apply(a.cm, [o[0] * 0 + a.cube.origin[0] + a.cube.size[0] * fx,
      a.cube.origin[1] + a.cube.size[1] * fy,
      a.cube.origin[2] + a.cube.size[2] * fz]);
    const p = toLocal(b.inv, world);
    if (p[0] > o[0] && p[0] < o[0] + s[0] && p[1] > o[1] && p[1] < o[1] + s[1] && p[2] > o[2] && p[2] < o[2] + s[2]) inside++;
  }
  return inside;
};
for (const c of cubes) c.inv = rigidInverseOf(c.cm);

if (opt.clip) {
  const [groupA, groupB] = opt.clip.split(':');
  const pick = (spec) => {
    const prefixes = spec.split(',');
    return cubes.filter((c) => prefixes.some((p) => c.bone.startsWith(p)));
  };
  const A = pick(groupA);
  const B = pick(groupB);
  const hits = [];
  for (const a of A) {
    for (const b of B) {
      const fwd = penetrationSamples(a, b);
      const back = fwd > 0 ? 0 : penetrationSamples(b, a);
      const score = Math.max(fwd, back);
      if (score > 0) hits.push({ a: a.bone, b: b.bone, score, aLo: a.lo, bLo: b.lo });
    }
  }
  const pairs = new Map();
  for (const h of hits) {
    const k = `${h.a} × ${h.b}`;
    pairs.set(k, (pairs.get(k) ?? 0) + 1);
  }
  console.log(`\n穿模体检 ${groupA}(${A.length} 立方体) × ${groupB}(${B.length} 立方体): ${hits.length} 对真穿透`);
  if (hits.length) {
    console.log('按骨骼对:');
    for (const [k, v] of [...pairs.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)) console.log(`  ${k}: ${v} 对`);
    console.log('最深的 5 对:');
    for (const h of hits.sort((x, y) => y.score - x.score).slice(0, 5)) {
      console.log(`  ${h.a} × ${h.b}  采样命中 ${h.score}/27  A@[${h.aLo.map((v) => v.toFixed(1)).join(',')}] B@[${h.bLo.map((v) => v.toFixed(1)).join(',')}]`);
    }
  }
}

// ---------------------------------------------------------------- 挂载物品（烟斗）
// 把 models/item/*.json 按「骨骼帧 + 原版 display 变换」挂到锚点骨骼上，这样离线就能看到
// 手上到底朝哪 —— 之前烟斗在游戏里倒挂着，光看 geo 是发现不了的（物品不在 geo 里）。
// 简化点：只支持单轴 element 旋转、单一贴图；足够本项目用。
const itemFaces = [];
if (opt.item) {
  const item = JSON.parse(readFileSync(opt.item, 'utf8'));
  const anchorName = opt.anchor ?? 'RightHandLocator';
  const anchorBone = byName.get(anchorName);
  if (!anchorBone) {
    console.error(`锚点骨骼不存在: ${anchorName}`);
    process.exit(1);
  }
  const disp = opt.itemDisp ?? item.display?.thirdperson_righthand ?? {};
  // 原版 display 的顺序：绕模型中心 (8,8,8) 旋转/缩放，再平移到骨骼原点
  const DR = R(disp.rotation ?? [0, 0, 0]);
  const DS = disp.scale ?? [1, 1, 1];
  const DT = disp.translation ?? [0, 0, 0];
  const center = [8, 8, 8];
  const bp = anchorBone.pivot ?? [0, 0, 0];
  // 原版物品变换：模型先平移到 (8,8,8) 中心、套 display 的缩放/旋转/平移，
  // 然后挂到**骨骼枢轴**（不是骨骼原点 —— GeckoLib 的 translateAndRotateMatrixForBone
  // 是先平移到 pivot 再旋转）。
  const itemToBone = (v) => {
    const rel = [v[0] - center[0], v[1] - center[1], v[2] - center[2]];
    const scaled = [rel[0] * DS[0], rel[1] * DS[1], rel[2] * DS[2]];
    const rotated = apply(DR, scaled);
    return [bp[0] + rotated[0] + DT[0], bp[1] + rotated[1] + DT[1], bp[2] + rotated[2] + DT[2]];
  };
  const boneM = resolve(anchorBone);
  const itemTexPath = opt.itemTexture;
  if (!itemTexPath) {
    console.error('--item 需要 --item-texture <png>');
    process.exit(1);
  }
  const itemTex = decodePng(readFileSync(itemTexPath));
  const iw = item.texture_size?.[0] ?? 16;
  const ih = item.texture_size?.[1] ?? 16;
  const texelItem = (u, v) => {
    const x = Math.min(itemTex.width - 1, Math.max(0, Math.floor((u / iw) * itemTex.width)));
    const y = Math.min(itemTex.height - 1, Math.max(0, Math.floor((v / ih) * itemTex.height)));
    const i = (y * itemTex.width + x) * itemTex.channels;
    const c = itemTex.channels;
    return c >= 3 ? [itemTex.pixels[i], itemTex.pixels[i + 1], itemTex.pixels[i + 2], c === 4 ? itemTex.pixels[i + 3] : 255]
      : [itemTex.pixels[i], itemTex.pixels[i], itemTex.pixels[i], 255];
  };
  for (const el of item.elements ?? []) {
    const lo = el.from, hi = el.to;
    const s = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]];
    // element 自己的旋转（原版只允许单轴）
    let em = I4();
    if (el.rotation) {
      const axis = el.rotation.axis;
      const angle = el.rotation.angle;
      const rot = axis === 'x' ? R([angle, 0, 0]) : axis === 'y' ? R([0, angle, 0]) : R([0, 0, angle]);
      em = aroundPivot(el.rotation.origin ?? [0, 0, 0], rot);
    }
    const xf = (v) => apply(boneM, itemToBone(apply(em, v)));
    for (const [name, fn] of Object.entries({
      north: (o, s2) => [[o[0] + s2[0], o[1], o[2]], [o[0], o[1], o[2]], [o[0], o[1] + s2[1], o[2]], [o[0] + s2[0], o[1] + s2[1], o[2]]],
      south: (o, s2) => [[o[0], o[1], o[2] + s2[2]], [o[0] + s2[0], o[1], o[2] + s2[2]], [o[0] + s2[0], o[1] + s2[1], o[2] + s2[2]], [o[0], o[1] + s2[1], o[2] + s2[2]]],
      west: (o, s2) => [[o[0], o[1], o[2] + s2[2]], [o[0], o[1], o[2]], [o[0], o[1] + s2[1], o[2]], [o[0], o[1] + s2[1], o[2] + s2[2]]],
      east: (o, s2) => [[o[0] + s2[0], o[1], o[2]], [o[0] + s2[0], o[1], o[2] + s2[2]], [o[0] + s2[0], o[1] + s2[1], o[2] + s2[2]], [o[0] + s2[0], o[1] + s2[1], o[2]]],
      up: (o, s2) => [[o[0], o[1] + s2[1], o[2] + s2[2]], [o[0] + s2[0], o[1] + s2[1], o[2] + s2[2]], [o[0] + s2[0], o[1] + s2[1], o[2]], [o[0], o[1] + s2[1], o[2]]],
      down: (o, s2) => [[o[0], o[1], o[2]], [o[0] + s2[0], o[1], o[2]], [o[0] + s2[0], o[1], o[2] + s2[2]], [o[0], o[1], o[2] + s2[2]]]
    })) {
      const uv = el.faces?.[name]?.uv;
      if (!uv) continue;
      // 物品 uv 是归一化 0..16，转回像素再采样
      const ua = Math.min(uv[0], uv[2]), ub = Math.max(uv[0], uv[2]);
      const va = Math.min(uv[1], uv[3]), vb = Math.max(uv[1], uv[3]);
      let r = 0, g = 0, b = 0, a = 0;
      for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) {
        const c = texelItem(ua + ((ub - ua) * (i + 0.5)) / 4, va + ((vb - va) * (j + 0.5)) / 4);
        r += c[0] * c[3]; g += c[1] * c[3]; b += c[2] * c[3]; a += c[3];
      }
      if (a / 16 <= 1) continue;
      itemFaces.push({
        pts: fn(lo, s).map((p) => xf(p)),
        color: [Math.round(r / a), Math.round(g / a), Math.round(b / a)]
      });
    }
  }
  // 报告烟斗长轴在世界空间朝哪儿（bowl 端 = item 空间 -X 侧）
  const probe = (v) => apply(boneM, itemToBone(v));
  const bowlDirOf = (dispRot) => {
    const dr = R(dispRot);
    const toB = (v) => {
      const rel = [v[0] - center[0], v[1] - center[1], v[2] - center[2]];
      const scaled = [rel[0] * DS[0], rel[1] * DS[1], rel[2] * DS[2]];
      const rotated = apply(dr, scaled);
      return [bp[0] + rotated[0] + DT[0], bp[1] + rotated[1] + DT[1], bp[2] + rotated[2] + DT[2]];
    };
    const a = apply(boneM, toB([-2, 8, 8]));
    const b = apply(boneM, toB([8, 8, 8]));
    const d = [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
    const l = Math.hypot(...d) || 1;
    return d.map((v) => v / l);
  };
  if (opt.searchDisp) {
    // 目标：斗朝「上 + 略朝前 + 略微朝外」。--game-space 下渲染出来的方向就是游戏方向，
    // 否则按「游戏方向 ≈ 模型方向取反」校准。
    const want = [0.3, 0.85, -0.4];
    const wl = Math.hypot(...want);
    const target = want.map((v) => v / wl);
    const results = [];
    const steps = [-180, -135, -90, -45, 0, 45, 90, 135, 180];
    for (const rx of steps) for (const ry of steps) for (const rz of steps) {
      const raw = bowlDirOf([rx, ry, rz]);
      const d = opt.gameSpace ? raw : raw.map((v) => -v);
      const score = d[0] * target[0] + d[1] * target[1] + d[2] * target[2];
      results.push({ rot: [rx, ry, rz], d, score });
    }
    results.sort((a, b) => b.score - a.score);
    console.log('\n候选 display rotation（按“斗朝上略朝前”打分，游戏方向 = -模型方向）:');
    for (const r of results.slice(0, 8)) {
      console.log(`  ${JSON.stringify(r.rot).padEnd(20)} 游戏方向=[${r.d.map((v) => v.toFixed(2)).join(', ')}]  分 ${r.score.toFixed(3)}`);
    }
    process.exit(0);
  }
  const bowl = probe([-2, 8, 8]);
  const grip = probe([8, 8, 8]);
  const dir = [bowl[0] - grip[0], bowl[1] - grip[1], bowl[2] - grip[2]];
  const len = Math.hypot(...dir) || 1;
  console.log(`\n烟斗朝向: 斗端相对手部方向 = [${dir.map((v) => (v / len).toFixed(2)).join(', ')}]（+Y 上 / -Z 前；游戏里约为其反向）`);
  console.log(`  bowl 世界坐标 ≈ [${bowl.map((v) => v.toFixed(1)).join(', ')}]，握手点 ≈ [${grip.map((v) => v.toFixed(1)).join(', ')}]`);
}

// ---------------------------------------------------------------- 渲染
// --texture 可省略：没有贴图时退化成纯色 clay 渲染（只用来核对姿态 / 穿模 / 轮廓），
// 带 --texture 时行为与旧版完全一致。
const texPath = opt.texture;
if (opt.render) {
  const tex = texPath ? decodePng(readFileSync(texPath)) : null;
  const texel = (u, v) => {
    if (!tex) return [205, 205, 210, 255];
    const x = Math.min(tex.width - 1, Math.max(0, Math.floor((u / TEX_W) * tex.width)));
    const y = Math.min(tex.height - 1, Math.max(0, Math.floor((v / TEX_H) * tex.height)));
    const i = (y * tex.width + x) * tex.channels;
    const c = tex.channels;
    const a = c === 4 ? tex.pixels[i + 3] : 255;
    return c >= 3 ? [tex.pixels[i], tex.pixels[i + 1], tex.pixels[i + 2], a] : [tex.pixels[i], tex.pixels[i], tex.pixels[i], a];
  };
  const faceColor = (uv) => {
    if (!tex) return [205, 205, 210]; // 无贴图：clay
    const [u0, v0] = uv.uv;
    const [du, dv] = uv.uv_size;
    const ua = Math.min(u0, u0 + du), ub = Math.max(u0, u0 + du);
    const va = Math.min(v0, v0 + dv), vb = Math.max(v0, v0 + dv);
    let r = 0, g = 0, b = 0, a = 0, n = 0;
    for (let i = 0; i < 6; i++) for (let j = 0; j < 6; j++) {
      const c = texel(ua + ((ub - ua) * (i + 0.5)) / 6, va + ((vb - va) * (j + 0.5)) / 6);
      r += c[0] * c[3]; g += c[1] * c[3]; b += c[2] * c[3]; a += c[3]; n++;
    }
    if (a / n <= 1) return null;
    return [Math.round(r / a), Math.round(g / a), Math.round(b / a)];
  };
  const FACE_DEFS = {
    north: (o, s) => [[o[0] + s[0], o[1], o[2]], [o[0], o[1], o[2]], [o[0], o[1] + s[1], o[2]], [o[0] + s[0], o[1] + s[1], o[2]]],
    south: (o, s) => [[o[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0], o[1] + s[1], o[2] + s[2]]],
    west: (o, s) => [[o[0], o[1], o[2] + s[2]], [o[0], o[1], o[2]], [o[0], o[1] + s[1], o[2]], [o[0], o[1] + s[1], o[2] + s[2]]],
    east: (o, s) => [[o[0] + s[0], o[1], o[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2]]],
    up: (o, s) => [[o[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2] + s[2]], [o[0] + s[0], o[1] + s[1], o[2]], [o[0], o[1] + s[1], o[2]]],
    down: (o, s) => [[o[0], o[1], o[2]], [o[0] + s[0], o[1], o[2]], [o[0] + s[0], o[1], o[2] + s[2]], [o[0], o[1], o[2] + s[2]]]
  };
  const faces = [];
  for (const cube of cubes) {
    const o = cube.cube.origin, s = cube.cube.size;
    for (const [name, fn] of Object.entries(FACE_DEFS)) {
      const uv = cube.cube.uv?.[name];
      if (!uv) continue;
      const color = faceColor(uv);
      if (!color) continue;
      const pts = fn(o, s).map((p) => apply(cube.cm, p));
      const e1 = [pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]];
      const e2 = [pts[3][0] - pts[0][0], pts[3][1] - pts[0][1], pts[3][2] - pts[0][2]];
      const cr = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
      const len = Math.hypot(...cr) || 1;
      faces.push({ pts, color, normal: [cr[0] / len, cr[1] / len, cr[2] / len] });
    }
  }
  // 挂载物品的面
  for (const f of itemFaces) {
    const e1 = [f.pts[1][0] - f.pts[0][0], f.pts[1][1] - f.pts[0][1], f.pts[1][2] - f.pts[0][2]];
    const e2 = [f.pts[3][0] - f.pts[0][0], f.pts[3][1] - f.pts[0][1], f.pts[3][2] - f.pts[0][2]];
    const cr = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
    const len = Math.hypot(...cr) || 1;
    faces.push({ pts: f.pts, color: f.color, normal: [cr[0] / len, cr[1] / len, cr[2] / len] });
  }
  const CAMS = {
    front: { right: [1, 0, 0], up: [0, 1, 0], view: [0, 0, 1] },
    side: { right: [0, 0, -1], up: [0, 1, 0], view: [-1, 0, 0] },
    three: { right: [0.86, 0, -0.5], up: [0, 1, 0], view: [0.5, 0, 0.86] }
  };
  const LIGHT = (() => { const v = [-0.35, 0.62, -0.7]; const l = Math.hypot(...v); return v.map((x) => x / l); })();
  for (const view of opt.views) {
    const cam = CAMS[view];
    if (!cam) continue;
    const SS = 2, px = opt.size * SS;
    const color = Buffer.alloc(px * px * 4);
    const depth = new Float32Array(px * px).fill(Infinity);
    for (let y = 0; y < px; y++) for (let x = 0; x < px; x++) {
      const i = (y * px + x) * 4;
      color[i] = 24; color[i + 1] = 26; color[i + 2] = 34; color[i + 3] = 255;
    }
    const toCam = (p) => [
      p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2],
      p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2],
      p[0] * cam.view[0] + p[1] * cam.view[1] + p[2] * cam.view[2]
    ];
    const camFaces = faces.map((f) => f.pts.map(toCam));
    let cmin = [Infinity, Infinity], cmax = [-Infinity, -Infinity];
    for (const pts of camFaces) for (const p of pts) for (let i = 0; i < 2; i++) {
      cmin[i] = Math.min(cmin[i], p[i]);
      cmax[i] = Math.max(cmax[i], p[i]);
    }
    const scale = Math.min(px / ((cmax[0] - cmin[0]) * 1.1), px / ((cmax[1] - cmin[1]) * 1.1));
    const cx = (cmin[0] + cmax[0]) / 2, cy = (cmin[1] + cmax[1]) / 2;
    const proj = (p) => [px / 2 + (p[0] - cx) * scale, px / 2 - (p[1] - cy) * scale, p[2]];
    faces.forEach((f, idx) => {
      const pts = camFaces[idx].map(proj);
      const nCam = [
        f.normal[0] * cam.right[0] + f.normal[1] * cam.right[1] + f.normal[2] * cam.right[2],
        f.normal[0] * cam.up[0] + f.normal[1] * cam.up[1] + f.normal[2] * cam.up[2],
        f.normal[0] * cam.view[0] + f.normal[1] * cam.view[1] + f.normal[2] * cam.view[2]
      ];
      if (nCam[2] > 0) return;
      const lit = 0.42 + 0.58 * Math.max(0, -(nCam[0] * LIGHT[0] + nCam[1] * LIGHT[1] + nCam[2] * LIGHT[2]));
      for (const tri of [[0, 1, 2], [0, 2, 3]]) {
        const [a, b, c] = tri.map((i) => pts[i]);
        const minX = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0])));
        const maxX = Math.min(px - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
        const minY = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1])));
        const maxY = Math.min(px - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
        const den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(den) < 1e-9) continue;
        for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
          const w0 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / den;
          const w1 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / den;
          const w2 = 1 - w0 - w1;
          if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
          const z = w0 * a[2] + w1 * b[2] + w2 * c[2];
          const o = y * px + x;
          if (z >= depth[o]) continue;
          depth[o] = z;
          const i = o * 4;
          color[i] = Math.min(255, f.color[0] * lit);
          color[i + 1] = Math.min(255, f.color[1] * lit);
          color[i + 2] = Math.min(255, f.color[2] * lit);
          color[i + 3] = 255;
        }
      }
    });
    const out = Buffer.alloc(opt.size * opt.size * 4);
    for (let y = 0; y < opt.size; y++) for (let x = 0; x < opt.size; x++) {
      let r = 0, g = 0, b = 0;
      for (let dy = 0; dy < SS; dy++) for (let dx = 0; dx < SS; dx++) {
        const i = ((y * SS + dy) * px + (x * SS + dx)) * 4;
        r += color[i]; g += color[i + 1]; b += color[i + 2];
      }
      const i = (y * opt.size + x) * 4;
      out[i] = r / (SS * SS); out[i + 1] = g / (SS * SS); out[i + 2] = b / (SS * SS); out[i + 3] = 255;
    }
    const file = opt.render.replace(/\.png$/, `-${view}.png`);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, encodePng(opt.size, opt.size, out));
    console.log(`写出 ${file}`);
  }
}
