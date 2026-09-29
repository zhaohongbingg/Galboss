import fs from 'node:fs';

// 计算 katana 模型里「刀把中心」在**模型空间**（即经过骨骼旋转后）的真实位置。
// 这个值就是渲染层 IkukoKatanaLayer 里 GRIP_X/Y/Z 该填的锚点：
// 代码会把它平移回原点，等效于「以刀把为原点」，于是刀把正好落在手心。
//
// 为什么不能直接用 Blockbench 里读到的坐标：katana 骨骼带 [90,10,90] 旋转，
// Blockbench 面板显示的是**骨骼局部坐标**，经过旋转后世界位置完全不同。

const GEO = 'D:/game/mc/bossmod/src/main/resources/assets/galboss/geo/ikuko_katana.geo.json';
const geo = JSON.parse(fs.readFileSync(GEO, 'utf8'));
const bone = geo['minecraft:geometry'][0].bones.find((b) => b.name === 'katana');
if (!bone) {
  console.error('找不到 katana 骨骼');
  process.exit(1);
}

const pivot = bone.pivot || [0, 0, 0];
const rot = bone.rotation || [0, 0, 0];
const rad = (d) => (d * Math.PI) / 180;

// Bedrock 旋转顺序 Z -> Y -> X，即 R = Rx * Ry * Rz
function rotate(v) {
  let [x, y, z] = v;
  const rz = rad(rot[2]);
  const x1 = x * Math.cos(rz) - y * Math.sin(rz);
  const y1 = x * Math.sin(rz) + y * Math.cos(rz);
  const ry = rad(rot[1]);
  const x2 = x1 * Math.cos(ry) + z * Math.sin(ry);
  const z2 = -x1 * Math.sin(ry) + z * Math.cos(ry);
  const rx = rad(rot[0]);
  const y3 = y1 * Math.cos(rx) - z2 * Math.sin(rx);
  const z3 = y1 * Math.sin(rx) + z2 * Math.cos(rx);
  return [x2, y3, z3];
}

// 长轴：所有立方体跨度最大的那根轴
const spans = [0, 1, 2].map((i) => {
  const lo = Math.min(...bone.cubes.map((c) => c.origin[i]));
  const hi = Math.max(...bone.cubes.map((c) => c.origin[i] + c.size[i]));
  return hi - lo;
});
const AXIS = spans.indexOf(Math.max(...spans));
console.log(`长轴 = ${'XYZ'[AXIS]}   骨骼 pivot=${JSON.stringify(pivot)}  rotation=${JSON.stringify(rot)}`);

// 刀把在刀尖的反方向，即长轴最小端
const sorted = [...bone.cubes].sort((a, b) => a.origin[AXIS] - b.origin[AXIS]);
const handle = sorted[0];
const center = handle.origin.map((v, i) => v + handle.size[i] / 2);
const rel = center.map((v, i) => v - pivot[i]);
const abs = rotate(rel).map((v, i) => v + pivot[i]);

console.log(`\n刀把那一节: origin=${JSON.stringify(handle.origin)}  size=${JSON.stringify(handle.size.map((v) => +v.toFixed(2)))}`);
console.log(`局部中心（面板显示值）= ${JSON.stringify(center.map((v) => +v.toFixed(2)))}`);
console.log(`旋转后的模型空间位置   = ${JSON.stringify(abs.map((v) => +v.toFixed(2)))}   ← 这就是锚点`);
console.log(`\n填进 IkukoKatanaLayer:\n  GRIP_X = ${abs[0].toFixed(2)}f;\n  GRIP_Y = ${abs[1].toFixed(2)}f;\n  GRIP_Z = ${abs[2].toFixed(2)}f;`);
