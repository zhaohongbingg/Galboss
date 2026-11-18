import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

// ============================================================================
// Blockbench .bbmodel -> GeckoLib/Bedrock geo.json 导入器
//
// 为什么需要它：交付来的贝阿朵莉切模型是 Blockbench 工程（.bbmodel），而本模组所有
// 角色走的都是 geo/ + textures/entity/ 这一套 GeckoLib 资产。以前三个角色是
// tools/blockbench/pipeline.mjs 在 Blockbench 里现场建模、再由 GeckoLib 插件导出；
// 这次是外部交付的工程文件，没有 Blockbench GUI 可点，所以把「导出器做了什么」
// 用脚本复刻一遍。
//
// 坐标系与 UV 的转换规则不是猜的，是拿本仓库已有的导出结果反推出来的
// （比对 tools/blockbench/onabuta_ikuko.bbmodel 与 assets/galboss/geo/onabuta_ikuko.geo.json）：
//
//   1. 骨骼 pivot：x 取反。blockbench 的 right_arm origin [5,22,0]
//      -> geo pivot [-5,22,0]。
//   2. 立方体 origin：x 取反且换成最小角，origin.x = -(from.x + sizeX) = -to.x；
//      y / z 原样。blockbench from [4,18,-2] to [8,24,2]
//      -> geo origin [-8,18,-2] size [4,6,4]。
//   3. 立方体 rotation / pivot：x 取反，pivot 同规则取反。
//   4. 骨骼 rotation：x 与 y 取反、z 保留（与 tools/blockbench/README.md 记录的
//      「导出表示法转换」一致；GeckoLib 载入时还会再把 x 镜像回来，两层嵌套后
//      编辑器视口与游戏画面一致）。
//   5. 单面 UV：north/south/east/west 原样搬成 { uv:[u1,v1], uv_size:[du,dv] }；
//      up/down 必须翻成右下角 + 负尺寸（uv:[u2,v2], uv_size:[-du,-dv]），
//      否则顶面/底面贴图会翻转 —— 这是 Blockbench bedrock 导出器的既有行为，
//      仓库里三份 geo 的 up/down 全是负 uv_size 可证。
//   6. 贴图分辨率：bbmodel 的 resolution 是 UV 空间（如 512x512），贴图文件本身
//      可能是它的整数倍（如 4096x4096）。geo 的 texture_width/height 必须填
//      **UV 空间**，同时把 PNG 缩到同一尺寸，否则游戏里只采到左上角 1/N。
//
// 用法：
//   node import_bbmodel.mjs <输入.bbmodel> <输出geo名> --texture <输出贴图名>
//        [--identifier geometry.xxx] [--texture-scale N] [--dry]
//
//   --texture-scale N  贴图按 N 倍缩小（4096 -> 512 就是 8）；省略则保持原尺寸，
//                      但会校验尺寸是否等于 resolution，不等就报错让人显式给参数。
//   --dry              只打印统计，不写文件。
//
// 输出位置固定为 assets/galboss/geo/<输出geo名> 与 assets/galboss/textures/entity/<输出贴图名>，
// 与 pipeline.mjs 产出的资产放在一起。
// ============================================================================

const HERE = dirname(fileURLToPath(import.meta.url));
const RES = join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'galboss');

const argv = process.argv.slice(2);
if (argv.length < 2) {
  console.error('用法: node import_bbmodel.mjs <输入.bbmodel> <输出geo名> --texture <名称.png> [--identifier geometry.x] [--texture-scale N] [--dry]');
  process.exit(1);
}

const [inPath, geoOutName] = argv;
const opts = { dry: false, scale: null, identifier: null, texture: null };
for (let i = 2; i < argv.length; i++) {
  const a = argv[i];
  if (a === '--dry') opts.dry = true;
  else if (a === '--texture') opts.texture = argv[++i];
  else if (a === '--identifier') opts.identifier = argv[++i];
  else if (a === '--texture-scale') opts.scale = Number(argv[++i]);
  else { console.error(`未知参数: ${a}`); process.exit(1); }
}
if (!opts.texture) { console.error('缺少 --texture <名称.png>'); process.exit(1); }

// ------------------------------------------------------------------ 读取工程
const bb = JSON.parse(readFileSync(inPath, 'utf8'));
const groups = new Map((bb.groups ?? []).map(g => [g.uuid, g]));
const elements = bb.elements ?? [];
const elByUuid = new Map(elements.map(e => [e.uuid, e]));
const uvW = bb.resolution?.width ?? 64;
const uvH = bb.resolution?.height ?? 64;

console.log(`源工程: ${basename(inPath)}（${bb.meta?.model_format}，${(bb.name ?? '')}）`);
console.log(`UV 空间: ${uvW}x${uvH}  骨骼(组): ${groups.size}  立方体: ${elements.length}`);

// ------------------------------------------------------------------ 骨骼树
// 名字必须唯一且只含 [A-Za-z0-9_]：动画 JSON、渲染层挂点都靠这个名字查，
// 拼错是静默失效，所以这里直接清洗 + 去重（重名在 bedrock 里也没有定义行为）。
const usedNames = new Set();
const bones = [];
const solidify = (raw) => raw.replace(/[^A-Za-z0-9_]/g, '_').replace(/^(\d)/, 'b$1') || 'bone';
const uniqueName = (raw) => {
  let base = solidify(raw);
  let name = base;
  let n = 2;
  while (usedNames.has(name)) name = `${base}_${n++}`;
  usedNames.add(name);
  return name;
};

const negX = (v) => Math.round(-v * 10000) / 10000;
const round4 = (v) => Math.round(v * 10000) / 10000;

/** 立方体自带旋转：geo 是 { rotation:[x,y,z], pivot:[...] }。 */
function cubeRotation(el) {
  const r = el.rotation;
  if (!Array.isArray(r) || !r.some(v => v !== 0)) return null;
  const origin = el.origin ?? [0, 0, 0];
  return { rotation: [negX(r[0]), negX(r[1]), r[2]], pivot: [negX(origin[0]), origin[1], origin[2]] };
}

/** 单面 UV：up/down 翻成右下角 + 负尺寸（见文件头第 5 条）。 */
function faceUv(uv) {
  const [u1, v1, u2, v2] = uv;
  return { u1, v1, du: u2 - u1, dv: v2 - v1 };
}

const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
const uvOutOfRange = [];
let cubeCount = 0;

function buildCube(el, boneName) {
  const [fx, fy, fz] = el.from;
  const [tx, ty, tz] = el.to;
  // 负数尺寸（from > to）先归一化，否则 geo 里 size 为负会整个立方体翻面
  const lo = [Math.min(fx, tx), Math.min(fy, ty), Math.min(fz, tz)];
  const hi = [Math.max(fx, tx), Math.max(fy, ty), Math.max(fz, tz)];
  const cube = {
    origin: [negX(hi[0]), round4(lo[1]), round4(lo[2])],
    size: [round4(hi[0] - lo[0]), round4(hi[1] - lo[1]), round4(hi[2] - lo[2])],
    uv: {}
  };
  for (const f of FACES) {
    const uv = el.faces?.[f]?.uv;
    if (!uv) continue;
    const { u1, v1, du, dv } = faceUv(uv);
    for (const v of [u1, u1 + du]) if (v < -0.001 || v > uvW + 0.001) uvOutOfRange.push(`${el.name}.${f} u=${v}`);
    for (const v of [v1, v1 + dv]) if (v < -0.001 || v > uvH + 0.001) uvOutOfRange.push(`${el.name}.${f} v=${v}`);
    cube.uv[f] = (f === 'up' || f === 'down')
      ? { uv: [round4(u1 + du), round4(v1 + dv)], uv_size: [round4(-du), round4(-dv)] }
      : { uv: [round4(u1), round4(v1)], uv_size: [round4(du), round4(dv)] };
  }
  if (el.inflate) cube.inflate = round4(el.inflate);
  const rot = cubeRotation(el);
  if (rot) { cube.rotation = rot.rotation; cube.pivot = rot.pivot; }
  cubeCount++;
  return cube;
}

const walk = (nodes, parentName) => {
  for (const node of nodes) {
    if (typeof node === 'string') continue;
    const g = groups.get(node.uuid);
    if (!g) continue;
    const name = uniqueName(g.name ?? `bone_${bones.length}`);
    const origin = g.origin ?? [0, 0, 0];
    const rotation = g.rotation ?? [0, 0, 0];
    const bone = { name, pivot: [negX(origin[0]), round4(origin[1]), round4(origin[2])] };
    if (parentName) bone.parent = parentName;
    if (rotation.some(v => v !== 0)) {
      bone.rotation = [negX(rotation[0]), negX(rotation[1]), round4(rotation[2])];
    }
    const kids = node.children ?? [];
    const cubes = kids.filter(c => typeof c === 'string')
      .map(uuid => elByUuid.get(uuid))
      .filter(Boolean)
      .map(el => buildCube(el, name));
    if (cubes.length) bone.cubes = cubes;
    bones.push(bone);
    walk(kids.filter(c => typeof c === 'object'), name);
  }
};
walk(bb.outliner ?? [], null);

// ------------------------------------------------------------------ 统计/校验
const withCubes = bones.filter(b => b.cubes?.length);
const deadBones = bones.filter(b => !b.cubes?.length && !bones.some(o => o.parent === b.name));
const rotatedCubeCount = bones.reduce((n, b) => n + (b.cubes ?? []).filter(c => c.rotation).length, 0);
let lo = [Infinity, Infinity, Infinity];
let hi = [-Infinity, -Infinity, -Infinity];
for (const b of bones) {
  for (const c of b.cubes ?? []) {
    for (let i = 0; i < 3; i++) {
      lo[i] = Math.min(lo[i], c.origin[i]);
      hi[i] = Math.max(hi[i], c.origin[i] + c.size[i]);
    }
  }
}
const boundsSize = hi.map((v, i) => round4(v - lo[i]));
console.log(`输出骨骼: ${bones.length}（含几何 ${withCubes.length}）  立方体: ${cubeCount}/${elements.length}`);
console.log(`立方体自带旋转: ${rotatedCubeCount}  无几何且无子骨骼的“死骨骼”: ${deadBones.length}`);
console.log(`模型包围盒(geo 空间): min=${JSON.stringify(lo)} max=${JSON.stringify(hi)} 尺寸=${JSON.stringify(boundsSize)}`);
if (cubeCount !== elements.length) console.log(`⚠ 有 ${elements.length - cubeCount} 个立方体没有被任何骨骼引用（未挂到 outliner 上），已跳过`);
if (uvOutOfRange.length) {
  console.log(`⚠ ${uvOutOfRange.length} 个面的 UV 超出 ${uvW}x${uvH} 空间: ${uvOutOfRange.slice(0, 8).join(' ')}`);
}

// ------------------------------------------------------------------ 贴图
const tex = bb.textures?.[0];
if (!tex?.source?.startsWith('data:image/png;base64,')) {
  console.error('工程里没有内嵌 PNG 贴图，无法导出 textures/entity/');
  process.exit(1);
}
const pngBytes = Buffer.from(tex.source.slice('data:image/png;base64,'.length), 'base64');
const imgW = pngBytes.readUInt32BE(16);
const imgH = pngBytes.readUInt32BE(20);
// 贴图缩放：**不调外部程序**（DSH 沙箱里 node 用管道 spawn 子进程会 EPERM），
// 直接在 JS 里解 PNG -> 整数倍盒式降采样 -> 重新编码。交付图都是 RGBA/RGB 8bit 非隔行 PNG，
// 这两个类型足够覆盖；遇到别的类型直接报错，不猜。
const scale = opts.scale ?? 1;
if (!Number.isInteger(scale) || scale < 1) { console.error('--texture-scale 必须是正整数'); process.exit(1); }
const outW = Math.round(imgW / scale);
const outH = Math.round(imgH / scale);
if (scale === 1 && (outW !== uvW || outH !== uvH)) {
  console.error(`贴图 ${imgW}x${imgH} 与 UV 空间 ${uvW}x${uvH} 不一致，请显式给 --texture-scale（${imgW / uvW}）`);
  process.exit(1);
}
if (outW !== uvW || outH !== uvH) {
  console.log(`⚠ 缩放后贴图 ${outW}x${outH} 仍与 UV 空间 ${uvW}x${uvH} 不一致，UV 会错位`);
}

// ------------------------------------------------------------------ geo
const visibleHeight = Math.max(1, round4(boundsSize[1] / 16 + 0.5));
const geo = {
  format_version: '1.12.0',
  'minecraft:geometry': [{
    description: {
      identifier: opts.identifier ?? `geometry.${geoOutName.replace(/\.geo\.json$/, '')}`,
      texture_width: uvW,
      texture_height: uvH,
      visible_bounds_width: Math.max(3, Math.ceil(Math.max(boundsSize[0], boundsSize[2]) / 16)),
      visible_bounds_height: visibleHeight,
      visible_bounds_offset: [0, round4(visibleHeight / 2 - 0.25), 0]
    },
    bones
  }]
};

if (opts.dry) {
  console.log('[dry] 不写文件');
  process.exit(0);
}

const geoPath = join(RES, 'geo', geoOutName);
mkdirSync(dirname(geoPath), { recursive: true });
writeFileSync(geoPath, `${JSON.stringify(geo, null, 2)}\n`);

// ------------------------------------------------------------------ PNG 读写
// 只支持 8bit 非隔行的灰度 / RGB / RGBA —— 交付来的贴图都是这两种（Blockbench 导出）。
// 不支持的类型直接报错，不猜。

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function decodePng(buf) {
  let pos = 8;
  let ihdr = null;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      ihdr = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        interlace: data[12]
      };
    } else if (type === 'IDAT') {
      idat.push(Buffer.from(data));
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  if (!ihdr) throw new Error('PNG 缺少 IHDR');
  if (ihdr.bitDepth !== 8 || ihdr.interlace !== 0) {
    throw new Error(`只支持 8bit 非隔行 PNG，收到 bitDepth=${ihdr.bitDepth} interlace=${ihdr.interlace}`);
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType];
  if (!channels) throw new Error(`不支持的 PNG colorType=${ihdr.colorType}`);

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
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      } else if (filter !== 0) throw new Error(`未知 PNG 行滤波 ${filter}`);
      dst[x] = v & 0xff;
    }
  }
  return { ...ihdr, channels, pixels };
}

/** 整数倍盒式降采样。按 alpha 预乘后再平均，避免透明像素的 RGB 把边缘染脏。 */
function downscalePng(img, factor) {
  const { width, height, channels, pixels } = img;
  const ow = Math.floor(width / factor);
  const oh = Math.floor(height / factor);
  const out = Buffer.alloc(ow * oh * 4);
  const n = factor * factor;
  for (let y = 0; y < oh; y++) {
    for (let x = 0; x < ow; x++) {
      let sr = 0, sg = 0, sb = 0, sa = 0;
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const si = ((y * factor + dy) * width + (x * factor + dx)) * channels;
          let r, g, b, a;
          if (channels === 4) { r = pixels[si]; g = pixels[si + 1]; b = pixels[si + 2]; a = pixels[si + 3]; }
          else if (channels === 3) { r = pixels[si]; g = pixels[si + 1]; b = pixels[si + 2]; a = 255; }
          else if (channels === 2) { r = g = b = pixels[si]; a = pixels[si + 1]; }
          else { r = g = b = pixels[si]; a = 255; }
          sr += r * a; sg += g * a; sb += b * a; sa += a;
        }
      }
      const oi = (y * ow + x) * 4;
      out[oi] = sa > 0 ? Math.round(sr / sa) : 0;
      out[oi + 1] = sa > 0 ? Math.round(sg / sa) : 0;
      out[oi + 2] = sa > 0 ? Math.round(sb / sa) : 0;
      out[oi + 3] = Math.round(sa / n);
    }
  }
  return { width: ow, height: oh, rgba: out };
}

function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
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
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

// ------------------------------------------------------------------ 贴图写出
const texDir = join(RES, 'textures', 'entity');
mkdirSync(texDir, { recursive: true });
const texPath = join(texDir, opts.texture);
if (scale === 1) {
  writeFileSync(texPath, pngBytes);
} else {
  const decoded = decodePng(pngBytes);
  const resized = downscalePng(decoded, scale);
  if (resized.width !== outW || resized.height !== outH) {
    console.log(`⚠ 降采样得到 ${resized.width}x${resized.height}，与预期 ${outW}x${outH} 不同（贴图尺寸不能被 scale 整除？）`);
  }
  writeFileSync(texPath, encodePng(resized.width, resized.height, resized.rgba));
}
const finalBytes = readFileSync(texPath);
console.log(`写出 ${geoPath.replace(HERE + '\\\\', '')}（${(readFileSync(geoPath).length / 1024).toFixed(1)} KB）`);
console.log(`写出 textures/entity/${opts.texture}（${finalBytes.readUInt32BE(16)}x${finalBytes.readUInt32BE(20)}, ${(finalBytes.length / 1024).toFixed(1)} KB）`);
console.log('骨骼名速查（有几何的）: ' + withCubes.map(b => `${b.name}(${b.cubes.length})`).join(' '));
