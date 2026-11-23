// 七大罪 YSM 源包 → GalBoss 精英怪资源转换器
//
// 输入（外部交付素材，不入库）：
//   交付 ZIP《七大罪YSM-七位角色与炼狱之柱》解压后的目录，默认
//   <repo>/../../_incoming/purgatory-stakes（即 D:\game\mc\_incoming\purgatory-stakes）。
//   用 --src 指到别处也可以。结构：
//     Characters/<Id>/YSM-source/models/main.json      bedrock geo（format 1.12.0）
//     Characters/<Id>/YSM-source/textures/satan.png    2048×1024 RGBA（源包统一叫 satan.png）
//     Characters/Satan/YSM-source/animations/main.animation.json  七份完全一致，只取一份
//     Purgatory-Pillar/purgatory-pillar.geo.json      炼狱之柱（7 骨 / 968 块）
//     Purgatory-Pillar/purgatory-pillar.png           512×256
//
// 输出（入库）：
//   assets/galboss/geo/purgatory_stake_<id>.geo.json        7 份角色 geo
//   assets/galboss/geo/item/purgatory_pillar.geo.json       柱子 geo（GeoItem 用）
//   assets/galboss/textures/entity/purgatory_stake_<id>.png 7 份角色贴图（原尺寸，不降采样）
//   assets/galboss/textures/item/purgatory_pillar.png
//   assets/galboss/animations/purgatory_stake.animation.json 共用的 6 段动画
//
// 三个必须知道的结论（都是实测/源码核对过的，别想当然）：
//   1. **贴图不做降采样**。geo 的 texture_width/height 是 UV 空间（512×256），
//      GeckoLib 把 uv 归一化后再采样，所以 PNG 保持 2048×1024 是白拿 4 倍纹理密度；
//      真降成 512×256 才是丢细节。
//   2. **动画里的 molang 必须清掉**。YSM 的 main.animation.json 有一个不存在的
//      `molang` 伪骨骼，run 的 Root 通道里还写着 `0.4*ysm.head_yaw`。
//      GeckoLib 4.4.2 的关键帧只认数字，非数字会被静默丢掉/报错，这里统一置 0 并计数。
//   3. **rotation 通道是绝对值**（GeckoLib 4.4.2，见 tools/blockbench/README.md §pose_tool）。
//      YSM 的写法也是绝对值，所以数值原样搬，不要把 geo 里的静止姿态加回去 —— 那会双层叠加。
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const ROOT = path.join(HERE, '..', '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'galboss');

const args = process.argv.slice(2);
let SRC = path.join(ROOT, '..', '_incoming', 'purgatory-stakes');
for (let i = 0; i < args.length; i++) if (args[i] === '--src') SRC = args[++i];

// 七位「炼狱之桩」。Id = 源包目录名（也是贴图作者用的名字），id = 注册名。
const CHARS = [
  { Id: 'Asmodeus',  id: 'asmodeus',  zh: '阿斯莫德', en: 'Asmodeus',  egg: null },
  { Id: 'Beelzebub', id: 'beelzebub', zh: '别西卜',   en: 'Beelzebub', egg: null },
  { Id: 'Belphegor', id: 'belphegor', zh: '贝尔芬格', en: 'Belphegor', egg: null },
  { Id: 'Leviathan', id: 'leviathan', zh: '利维坦',   en: 'Leviathan', egg: null },
  { Id: 'Lucifer',   id: 'lucifer',   zh: '路西法',   en: 'Lucifer',   egg: null },
  { Id: 'Mammon',    id: 'mammon',    zh: '玛门',     en: 'Mammon',    egg: null },
  { Id: 'Satan',     id: 'satan',     zh: '撒旦',     en: 'Satan',     egg: null }
];

// 主动画 → 我们只留 6 段。YSM 的 attacked 是「被打」，swing_hand 是「挥手（攻击）」。
const ANIM_MAP = [
  ['idle', 'idle'],
  ['walk', 'walk'],
  ['run', 'run'],
  ['attacked', 'hurt'],
  ['swing_hand', 'attack'],
  ['death', 'death']
];

const stats = { expr: 0, bad: 0, samples: [], droppedBones: [], droppedChannels: [] };

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

/** 单个数值：合法数字原样返回；表达式（molang / 字符串）置 0 并记账。 */
function num(v, where) {
  if (typeof v === 'number') {
    if (Number.isFinite(v)) return v;
    stats.bad++;
    return 0;
  }
  if (typeof v === 'string') {
    const t = v.trim();
    if (/^-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) return Number(t);
    stats.expr++;
    if (stats.samples.length < 12) stats.samples.push(`${where}: ${t}`);
    return 0;
  }
  stats.bad++;
  return 0;
}

/** 通道值：数字（scale 标量）或三元向量。 */
function vec(v, where) {
  if (typeof v === 'number') return num(v, where);
  if (Array.isArray(v)) return v.map((x) => num(x, where));
  stats.bad++;
  return 0;
}

/** 一个通道（常量 / 关键帧对象）→ 清掉 lerp_mode 与 molang 后的等价通道。 */
function cleanChannel(channel, where) {
  if (Array.isArray(channel) || typeof channel === 'number') {
    return vec(channel, where);
  }
  const out = {};
  for (const [k, frame] of Object.entries(channel)) {
    if (k === 'lerp_mode') continue;
    if (frame === null || typeof frame !== 'object' || Array.isArray(frame)) {
      out[k] = vec(frame, `${where}@${k}`);
      continue;
    }
    const nf = {};
    for (const [fk, fv] of Object.entries(frame)) {
      if (fk === 'lerp_mode') continue; // GeckoLib 4.4.2 不做 catmullrom，线性插值
      nf[fk] = vec(fv, `${where}@${k}.${fk}`);
    }
    out[k] = nf;
  }
  return out;
}

// ---------------------------------------------------------------- 角色 geo
function convertChar(ch) {
  const srcModel = path.join(SRC, 'Characters', ch.Id, 'YSM-source', 'models', 'main.json');
  const srcTex = path.join(SRC, 'Characters', ch.Id, 'YSM-source', 'textures', 'satan.png');
  if (!fs.existsSync(srcModel)) throw new Error(`缺少源模型: ${srcModel}`);

  // 只替换 identifier 这一个字符串，保留源文件排版（避免整份重新序列化带来的无谓 diff）
  let text = fs.readFileSync(srcModel, 'utf8');
  const m = text.match(/"identifier"\s*:\s*"([^"]+)"/);
  if (!m) throw new Error(`${ch.Id}: 找不到 geometry identifier`);
  const oldId = m[1];
  text = text.replace(`"identifier" : "${oldId}"`, `"identifier" : "geometry.purgatory_stake_${ch.id}"`);
  if (text.includes(oldId)) text = text.split(`"identifier": "${oldId}"`).join(`"identifier": "geometry.purgatory_stake_${ch.id}"`);
  if (text.includes(`"${oldId}"`)) throw new Error(`${ch.Id}: identifier 替换失败（${oldId} 仍未替换）`);

  const geo = JSON.parse(text)['minecraft:geometry'][0];
  const desc = geo.description;
  if (desc.texture_width !== 512 || desc.texture_height !== 256) {
    throw new Error(`${ch.Id}: 意外 UV 空间 ${desc.texture_width}×${desc.texture_height}`);
  }
  let cubes = 0;
  let rotated = 0;
  let boxUV = 0;
  let faceUV = 0;
  const names = new Set();
  for (const b of geo.bones) {
    if (!b.name || !b.pivot) throw new Error(`${ch.Id}: 骨骼缺 name/pivot`);
    if (names.has(b.name)) throw new Error(`${ch.Id}: 骨骼重名 ${b.name}`);
    names.add(b.name);
    for (const cu of b.cubes || []) {
      cubes++;
      if (cu.rotation && cu.rotation.some((v) => v !== 0)) rotated++;
      if (!cu.origin || !cu.size || !cu.uv) throw new Error(`${ch.Id}: 方块缺 origin/size/uv`);
      // uv 有两种合法形态：box（[u,v]）和逐面（{north:{uv,uv_size},...}）。
      // GeckoLib 4.4.2 的 UVUnion 两种都收（FaceUV/boxUVCoords），所以这里只做形态校验。
      if (Array.isArray(cu.uv)) {
        if (cu.uv.length !== 2) throw new Error(`${ch.Id}: box uv 长度异常 ${cu.uv.length}`);
        boxUV++;
      } else {
        const faces = Object.keys(cu.uv);
        if (!faces.length || !faces.every((f) => cu.uv[f] && cu.uv[f].uv)) {
          throw new Error(`${ch.Id}: 逐面 uv 结构异常 ${faces.join(',')}`);
        }
        faceUV++;
      }
    }
  }
  if (!names.has('RightHandLocator')) throw new Error(`${ch.Id}: 没有 RightHandLocator，柱子挂不上`);

  const outModel = path.join(ASSETS, 'geo', `purgatory_stake_${ch.id}.geo.json`);
  ensureDir(path.dirname(outModel));
  fs.writeFileSync(outModel, text);

  ensureDir(path.join(ASSETS, 'textures', 'entity'));
  const outTex = path.join(ASSETS, 'textures', 'entity', `purgatory_stake_${ch.id}.png`);
  fs.copyFileSync(srcTex, outTex);

  const xs = [], ys = [], zs = [];
  for (const b of geo.bones) for (const cu of b.cubes || []) {
    xs.push(cu.origin[0], cu.origin[0] + cu.size[0]);
    ys.push(cu.origin[1], cu.origin[1] + cu.size[1]);
    zs.push(cu.origin[2], cu.origin[2] + cu.size[2]);
  }
  const height = Math.max(...ys) - Math.min(...ys);

  console.log(
    `  ${ch.id.padEnd(10)} bones=${String(geo.bones.length).padStart(3)} cubes=${String(cubes).padStart(3)} ` +
    `rot=${String(rotated).padStart(3)} uv=逐面${faceUV}/box${boxUV} 高=${height.toFixed(2)}u (${(height / 16).toFixed(2)} 格) ` +
    `贴图=${fs.statSync(outTex).size}B`
  );
  return { height, cubes, bones: geo.bones.length, texBytes: fs.statSync(outTex).size };
}

// ---------------------------------------------------------------- 动画
function convertAnimations() {
  const srcAnim = path.join(SRC, 'Characters', 'Satan', 'YSM-source', 'animations', 'main.animation.json');
  const all = readJson(srcAnim).animations;
  const out = { format_version: '1.8.0', animations: {} };

  for (const [from, to] of ANIM_MAP) {
    const a = all[from];
    if (!a) throw new Error(`源动画缺 ${from}`);
    const kept = {};
    for (const [boneName, channels] of Object.entries(a.bones || {})) {
      if (boneName === 'molang') {
        stats.droppedBones.push(`${from}/${boneName}`);
        continue;
      }
      const cleanBones = {};
      for (const [chanName, chanVal] of Object.entries(channels)) {
        if (chanName === 'particles' || chanName === 'sounds' || chanName === 'timeline') {
          stats.droppedChannels.push(`${from}/${boneName}/${chanName}`);
          continue;
        }
        cleanBones[chanName] = cleanChannel(chanVal, `${from}/${boneName}/${chanName}`);
      }
      if (Object.keys(cleanBones).length) kept[boneName] = cleanBones;
    }
    const anim = {
      loop: a.loop === undefined ? false : a.loop,
      animation_length: a.animation_length,
      bones: kept
    };
    out.animations[`animation.purgatory_stake.${to}`] = anim;
    console.log(
      `  动画 ${from.padEnd(12)} → animation.purgatory_stake.${to.padEnd(7)} ` +
      `len=${a.animation_length} loop=${JSON.stringify(a.loop)} bones=${Object.keys(kept).length}`
    );
  }

  ensureDir(path.join(ASSETS, 'animations'));
  const p = path.join(ASSETS, 'animations', 'purgatory_stake.animation.json');
  fs.writeFileSync(p, JSON.stringify(out, null, 2));
  console.log(`  写出 ${path.relative(ROOT, p)} (${fs.statSync(p).size}B)`);
  return out;
}

// ---------------------------------------------------------------- 炼狱之柱
function convertPillar() {
  const srcGeo = path.join(SRC, 'Purgatory-Pillar', 'purgatory-pillar.geo.json');
  const srcTex = path.join(SRC, 'Purgatory-Pillar', 'purgatory-pillar.png');
  let text = fs.readFileSync(srcGeo, 'utf8');
  const m = text.match(/"identifier"\s*:\s*"([^"]+)"/);
  if (!m) throw new Error('柱子 geo 缺 identifier');
  text = text.split(`"${m[1]}"`).join('"geometry.purgatory_pillar"');

  const geo = JSON.parse(text)['minecraft:geometry'][0];
  let cubes = 0;
  let rotated = 0;
  const xs = [], ys = [], zs = [];
  for (const b of geo.bones) for (const cu of b.cubes || []) {
    cubes++;
    if (cu.rotation && cu.rotation.some((v) => v !== 0)) rotated++;
    xs.push(cu.origin[0], cu.origin[0] + cu.size[0]);
    ys.push(cu.origin[1], cu.origin[1] + cu.size[1]);
    zs.push(cu.origin[2], cu.origin[2] + cu.size[2]);
  }
  const outGeo = path.join(ASSETS, 'geo', 'item', 'purgatory_pillar.geo.json');
  ensureDir(path.dirname(outGeo));
  fs.writeFileSync(outGeo, text);
  ensureDir(path.join(ASSETS, 'textures', 'item'));
  const outTex = path.join(ASSETS, 'textures', 'item', 'purgatory_pillar.png');
  fs.copyFileSync(srcTex, outTex);

  console.log(
    `  pillar     bones=${geo.bones.length} cubes=${cubes} rot=${rotated} ` +
    `UV=${geo.description.texture_width}×${geo.description.texture_height} ` +
    `bbox X[${Math.min(...xs).toFixed(2)},${Math.max(...xs).toFixed(2)}] ` +
    `Y[${Math.min(...ys).toFixed(2)},${Math.max(...ys).toFixed(2)}] ` +
    `Z[${Math.min(...zs).toFixed(2)},${Math.max(...zs).toFixed(2)}] 长=${(Math.max(...ys) - Math.min(...ys)).toFixed(2)}u`
  );
  console.log(`  注意：${rotated}/${cubes} 块带自由角度旋转 —— 原版物品模型只接受 0/±22.5/±45，`);
  console.log(`        所以柱子走 GeoItem（GeoItemRenderer），不走 models/item/*.json 的 elements。`);
}

// ---------------------------------------------------------------- main
console.log(`源目录: ${SRC}`);
if (!fs.existsSync(SRC)) {
  console.error(`\n找不到源目录。请先解压素材 ZIP 到 ${SRC}，或用 --src 指定。`);
  process.exit(1);
}
console.log('\n[1/3] 七位角色 geo + 贴图');
const per = {};
for (const ch of CHARS) per[ch.id] = convertChar(ch);

console.log('\n[2/3] 共用动画');
convertAnimations();

console.log('\n[3/3] 炼狱之柱');
convertPillar();

console.log('\n清洗统计：');
console.log(`  molang / 非数字关键帧值 → 置 0 共 ${stats.expr} 个，非法数值 ${stats.bad} 个`);
if (stats.samples.length) console.log(`  示例: ${stats.samples.join(' | ')}`);
console.log(`  丢弃的伪骨骼: ${stats.droppedBones.join(', ') || '（无）'}`);
console.log(`  丢弃的通道: ${stats.droppedChannels.join(', ') || '（无）'}`);

const hs = Object.values(per).map((v) => v.height);
console.log(`\n身高范围 ${Math.min(...hs).toFixed(2)}–${Math.max(...hs).toFixed(2)}u，` +
  `按 YSM height_scale 0.7 换算 = ${(Math.min(...hs) * 0.7 / 16).toFixed(2)}–${(Math.max(...hs) * 0.7 / 16).toFixed(2)} 格`);

fs.writeFileSync(path.join(HERE, '_purgatory_stats.json'), JSON.stringify(per, null, 2));
console.log('\n完成。');
