import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 离线体检 models/item/*.json：不开游戏就能看出「UV 是不是采错了」。
//
// 检查三件事：
//   1. uv 是否落在 0..16 的归一化空间（原版就是这个空间，见 geo_to_item_model.mjs 注释【1】）
//      出现 > 16 的值 = 模型是按贴图像素写的，游戏里必然采到 sprite 之外 —— 就是「像没贴图」的元凶
//   2. 每个面的 uv 矩形去贴图上采样，报平均色和最小 alpha
//      整面全透明 = 采到了空白区（贴图布局或 uv 换算错）
//   3. 顺带报模型在物品空间（16³ 格里）的包围盒
//
// 用法: node verify_item_model.mjs <模型文件名...>      例如: node verify_item_model.mjs ikuko_katana.json
//      不带参数时检查 models/item 下所有由 geo_to_item_model.mjs 生成了 credit 的模型

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const RES = path.join(HERE, '..', 'src', 'main', 'resources', 'assets', 'galboss');

// ---------------------------------------------------------------- 极简 PNG 解码
// 只支持 8bit、非隔行的 RGB/RGBA（本项目的武器贴图都是这种）
function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error(`${file} 不是 PNG`);
  let pos = 8;
  let width = 0;
  let height = 0;
  let colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      if (data[8] !== 8) throw new Error('只支持 8bit PNG');
      if (data[12] !== 0) throw new Error('不支持隔行 PNG');
      colorType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (!channels) throw new Error(`不支持的 PNG colorType=${colorType}`);

  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? line[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      switch (filter) {
        case 1: line[x] = (line[x] + a) & 0xff; break;
        case 2: line[x] = (line[x] + b) & 0xff; break;
        case 3: line[x] = (line[x] + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          line[x] = (line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff;
          break;
        }
      }
    }
    for (let x = 0; x < width; x++) {
      const s = x * channels;
      const d = (y * width + x) * 4;
      out[d] = line[s];
      out[d + 1] = line[s + 1];
      out[d + 2] = line[s + 2];
      out[d + 3] = channels === 4 ? line[s + 3] : 255;
    }
    prev = line;
  }
  return { width, height, data: out };
}

// ---------------------------------------------------------------- 采样
function sample(img, u, v) {
  const x = Math.min(img.width - 1, Math.max(0, Math.floor(u)));
  const y = Math.min(img.height - 1, Math.max(0, Math.floor(v)));
  const d = (y * img.width + x) * 4;
  return [img.data[d], img.data[d + 1], img.data[d + 2], img.data[d + 3]];
}

/** 只统计不透明采样点的平均色，避免被矩形边缘的透明像素带偏。 */
function describe(px) {
  const opaque = px.filter((p) => p[3] > 8);
  if (!opaque.length) return '全透明';
  const avg = [0, 1, 2].map((c) => Math.round(opaque.reduce((s, p) => s + p[c], 0) / opaque.length));
  const a = Math.round(opaque.reduce((s, p) => s + p[3], 0) / opaque.length);
  return `#${avg.map((v) => v.toString(16).padStart(2, '0')).join('')} a≈${a}`;
}

// ---------------------------------------------------------------- 主流程
const argFiles = process.argv.slice(2);
const modelDir = path.join(RES, 'models', 'item');
const files = argFiles.length
  ? argFiles
  : fs.readdirSync(modelDir).filter((f) => {
      const t = fs.readFileSync(path.join(modelDir, f), 'utf8');
      return t.includes('geo_to_item_model.mjs');
    });

for (const file of files) {
  const model = JSON.parse(fs.readFileSync(path.join(modelDir, file), 'utf8'));
  const texRef = Object.values(model.textures).find((v) => !v.startsWith('#'));
  const texPath = path.join(RES, 'textures', texRef.replace('galboss:', '').replace('/', path.sep) + '.png');
  const img = decodePng(texPath);
  console.log(`\n================ ${file}`);
  console.log(`  贴图 ${texRef} -> ${img.width}x${img.height}  (model.texture_size=${JSON.stringify(model.texture_size)})`);

  // 0) 贴图的不透明内容范围 —— 很多枪械贴图（TACZ 等）是"海报图"：
  //    模型 UV 只用左上角一小块，剩下大片区域画的是不透明的 logo/文字。
  //    UV 一旦跑到那块海报区，模型上就会出现纯白/纯黑的大色块。
  let cx0 = img.width, cy0 = img.height, cx1 = -1, cy1 = -1;
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      if (img.data[(y * img.width + x) * 4 + 3] > 8) {
        if (x < cx0) cx0 = x;
        if (y < cy0) cy0 = y;
        if (x > cx1) cx1 = x;
        if (y > cy1) cy1 = y;
      }
    }
  }
  console.log(`  不透明内容范围 x ${cx0}..${cx1}  y ${cy0}..${cy1}`);

  // 1) uv 空间检查
  const allUv = [];
  for (const e of model.elements) for (const f of Object.values(e.faces)) allUv.push(...f.uv);
  const maxUv = Math.max(...allUv.map(Math.abs));
  const px = allUv.map((v) => (v / 16) * img.width);
  console.log(`  uv 最大值 = ${maxUv}  ${maxUv > 16 ? '✗ 超出 0..16（按像素写的，采不到贴图）' : '✓ 在 0..16 归一化空间内'}`);
  console.log(`  uv 换算成像素 u/v 范围 ${Math.min(...px).toFixed(0)}..${Math.max(...px).toFixed(0)}（另一轴按高度换算）`);

  // 2) 逐面采样
  const bad = [];
  const report = [];
  for (const [i, e] of model.elements.entries()) {
    for (const [name, f] of Object.entries(e.faces)) {
      const [u0, v0, u1, v1] = f.uv;
      const us = [u0, (u0 + u1) / 2, u1];
      const vs = [v0, (v0 + v1) / 2, v1];
      const px = [];
      for (const u of us) for (const v of vs) px.push(sample(img, (u / 16) * img.width, (v / 16) * img.height));
      const opaque = px.filter((p) => p[3] > 8);
      const rec = `    #${String(i).padStart(3)} ${name.padEnd(5)} uv=${JSON.stringify(f.uv)} 采样=${describe(px)} 不透明 ${opaque.length}/9`;
      report.push(rec);
      if (opaque.length <= 3) bad.push(rec);
    }
  }
  for (const r of report) console.log(r);
  console.log(`  ---- 采样基本全透明的面: ${bad.length} / ${report.length}`);
  for (const r of bad) console.log(`  !!${r.trim()}`);

  // 3) 包围盒 / 握把
  const lo = [0, 1, 2].map((i) => Math.min(...model.elements.map((e) => e.from[i])));
  const hi = [0, 1, 2].map((i) => Math.max(...model.elements.map((e) => e.to[i])));
  console.log(`  物品空间 from=${JSON.stringify(lo.map((v) => +v.toFixed(3)))} to=${JSON.stringify(hi.map((v) => +v.toFixed(3)))}`);
  console.log(`  尺寸=${JSON.stringify(hi.map((v, i) => +(v - lo[i]).toFixed(2)))} 单位（16 单位 = 1 格）`);
  console.log(`  第三方持有握把应落在模型中心 (8,8,8)；旋转轴就在这儿，见脚本注释【3】`);

  // 4) 原版 element 合法性（超了会让整个模型加载失败，比贴图错还严重）
  let withRot = 0;
  const problems = [];
  const ALLOWED_ANGLE = new Set([0, 22.5, -22.5, 45, -45]);
  const inExtent = (v) => v >= -16 && v <= 32;
  for (const [i, e] of model.elements.entries()) {
    for (const v of [...e.from, ...e.to]) {
      if (!inExtent(v)) problems.push(`#${i} from/to 超出 -16..32: ${v}`);
    }
    if (!e.rotation) continue;
    withRot++;
    const { origin, axis, angle } = e.rotation;
    if (!ALLOWED_ANGLE.has(angle)) problems.push(`#${i} angle=${angle} 不是 0/±22.5/±45，原版会抛异常`);
    if (!['x', 'y', 'z'].includes(axis)) problems.push(`#${i} axis=${axis} 非法`);
    for (const v of origin) {
      if (!inExtent(v)) problems.push(`#${i} rotation.origin 超出 -16..32: ${v}`);
    }
  }
  console.log(`  带 element rotation 的元素: ${withRot} 个`);
  console.log(`  原版合法性: ${problems.length ? '✗ ' + problems.slice(0, 8).join(' | ') : '✓ angle/axis/范围都合法'}`);
}
