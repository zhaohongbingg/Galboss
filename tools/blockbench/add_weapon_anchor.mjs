import fs from 'node:fs';
import path from 'node:path';

// 给角色模型加「武器挂点」骨骼，并把渲染层改为挂到它上面。
//
// 依据 Mowzie's Mobs 的做法（RenderWroughtnaut / GeckoItemlayer）：
// 武器挂在自己模型里的一个专用骨骼上（他们的例子叫 sword），
// 位置与朝向**在 Blockbench 里摆好**，代码里不需要任何偏移常量。
//
// 挂点结构：
//   right_arm
//     └── weapon_anchor   pivot = 手心(-6,12,0)，rotation 可在 BB 里任意调
//
// ⚠ pivot 的 x 必须和父骨骼 right_arm 的立方体同号（那些立方体是 x −8..−4，手心 −6）。
//   曾经写成 +6，看着像「手心」其实是**另一条手臂**的位置 —— 挂点会被渲染在身体另一侧，
//   手臂摆动时武器还跟着乱甩。详见 fix_weapon_anchor_side.mjs 的说明。
//
// 之后在 Blockbench 里移动/旋转 weapon_anchor，就等于移动/旋转武器本身。

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const ROOT = path.join(HERE, '..');
const GEO_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'galboss', 'geo');
const LAYER_DIR = path.join(ROOT, 'src', 'main', 'java', 'com', 'example', 'galboss', 'client', 'renderer', 'layer');

const ANCHOR = 'weapon_anchor';
const HAND_PIVOT = [-6, 12, 0];

// ---- 1. 给所有含 right_arm 的角色模型加挂点骨骼 ------------------------------
let touched = 0;
for (const file of fs.readdirSync(GEO_DIR)) {
  if (!file.endsWith('.geo.json')) continue;
  const p = path.join(GEO_DIR, file);
  const geo = JSON.parse(fs.readFileSync(p, 'utf8'));
  const bones = geo['minecraft:geometry']?.[0]?.bones;
  if (!bones) continue;

  // 只处理有 right_arm 的角色模型（武器模型没有这个骨骼）
  if (!bones.some((b) => b.name === 'right_arm')) continue;
  if (bones.some((b) => b.name === ANCHOR)) {
    console.log(`  ${file}: 已有 ${ANCHOR}，跳过`);
    continue;
  }

  bones.push({
    name: ANCHOR,
    parent: 'right_arm',
    pivot: HAND_PIVOT.slice()
  });
  fs.writeFileSync(p, JSON.stringify(geo, null, 2));
  console.log(`  ${file}: 已加 ${ANCHOR} (parent=right_arm, pivot=${JSON.stringify(HAND_PIVOT)})`);
  touched++;
}

// ---- 2. 渲染层改挂到挂点骨骼，偏移归零 --------------------------------------
const PATCHES = [
  [/"right_arm"\.equals\(bone\.getName\(\)\)/g, `"${ANCHOR}".equals(bone.getName())`],
  [/FULL_BONE_CHAIN = false;/g, 'FULL_BONE_CHAIN = true;'],
  [
    /poseStack\.translate\(1\.0f \* UNIT, -10\.0f \* UNIT, 0\.0f\);/g,
    'poseStack.translate(0.0f, 0.0f, 0.0f);   // 位置由 weapon_anchor 骨骼决定，这里只做最终微调'
  ]
];

for (const file of fs.readdirSync(LAYER_DIR)) {
  if (!file.endsWith('.java')) continue;
  const p = path.join(LAYER_DIR, file);
  let src = fs.readFileSync(p, 'utf8');
  let n = 0;
  for (const [re, to] of PATCHES) {
    const before = src;
    src = src.replace(re, to);
    if (src !== before) n++;
  }
  if (n > 0) {
    fs.writeFileSync(p, src);
    console.log(`  ${file}: 已改 ${n} 处（挂点=${ANCHOR}、整链=true、偏移归零）`);
  }
}

console.log(`\n模型改动 ${touched} 个；渲染层改动见上。`);
