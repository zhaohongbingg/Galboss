import fs from 'node:fs';
import { initialize, callTool } from './_mcp_client.mjs';
import { CHARACTER_IDS, animsFor, overriddenNames } from './anims.mjs';

/**
 * 把**当前 Blockbench 项目**的动画导出成 `assets/galboss/animations/<项目名>.animation.json`。
 *
 * ⚠ v1 这里会拿导出结果做字符串替换、顺手写出另外两个角色的文件（"骨架一致，只是前缀不同"）。
 *   现在三人有各自的专属动画（桐香空手 / 礼持枪 / 郁子持刀，见 anims.mjs 的 CHARACTER_ANIMS），
 *   那种替换会把专属内容抹掉，所以**去掉了**。要一次更新三份请用：
 *
 *       node build_all_anims.mjs
 *
 *   它会依次切换项目、跑 make_anims + export_anims，最后统一校验。
 */

const MOD_ANIM = 'd:/game/mc/bossmod/src/main/resources/assets/galboss/animations';

const textOf = (res) => {
  const c = res?.result?.content;
  return Array.isArray(c) ? c.map((x) => x.text ?? JSON.stringify(x)).join('\n') : JSON.stringify(res);
};

await initialize();

const info = textOf(await callTool('get_project_info', {}));
const proj = (info.match(/"name":\s*"([a-z_0-9]+)"/) || [])[1];
if (!CHARACTER_IDS.includes(proj)) {
  console.error(`当前 Blockbench 项目是 "${proj}"，不是三个 boss 之一，中止`);
  process.exit(1);
}
console.log(`当前项目: ${proj}`);

// 1. 导出
const res = await callTool('geckolib_export_animations', {
  mode: 'compile',
  path: `${MOD_ANIM}/${proj}.animation.json`,
  max_content_length: 0
});
console.log(textOf(res));

// 2. 校验：导出的动画名必须与 animsFor(proj) 完全对应
const data = JSON.parse(fs.readFileSync(`${MOD_ANIM}/${proj}.animation.json`, 'utf8'));
const got = Object.keys(data.animations || {});
const want = animsFor(proj).map((a) => `animation.${proj}.${a.name}`);
const missing = want.filter((k) => !got.includes(k));
const extra = got.filter((k) => !want.includes(k));
console.log(`${proj}: 导出 ${got.length} 段 / 期望 ${want.length} 段`);
if (missing.length) console.log('  缺少: ' + missing.join(', '));
if (extra.length) console.log('  多余: ' + extra.join(', '));
console.log(missing.length || extra.length ? '  ✗ 不一致' : '  ✓ 一致');
if (missing.length || extra.length) process.exit(1);

// 3. 报告另外两份的状态。注意：**不能**用"是否与本次导出相同"来判断好坏 ——
//    三人本来就该有各自的专属内容，只比名字集合是看不出来的。
//    真正的一致性判断（名字集合 + 每段时长）在 build_all_anims.mjs 末尾统一做。
for (const id of CHARACTER_IDS) {
  if (id === proj) continue;
  const p = `${MOD_ANIM}/${id}.animation.json`;
  if (!fs.existsSync(p)) { console.log(`  ${id}: 还没有动画文件（跑 build_all_anims.mjs 生成）`); continue; }
  const own = overriddenNames(id);
  console.log(`  ${id}: 已存在，${own.length ? '有专属版 ' + own.join(', ') : '全部用基础版'} —— 要同步请跑 build_all_anims.mjs`);
}
