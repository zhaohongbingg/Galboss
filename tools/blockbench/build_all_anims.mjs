import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { initialize, callTool } from './_mcp_client.mjs';
import { CHARACTER_IDS, animsFor, overriddenNames } from './anims.mjs';

/**
 * 一次更新三名 boss 的动画文件。
 *
 *   node build_all_anims.mjs
 *
 * 为什么需要它：三个角色现在有各自的专属动画（桐香空手 / 礼持枪 / 郁子持刀），
 * 不能像以前那样"导一份再替换前缀"。Blockbench 的 create_animation 与
 * geckolib_export_animations 都只作用于**当前活动项目**，所以要：
 *
 *   for 每个角色: 选中它的项目 -> make_anims（清空并重建该角色的动画集）-> export_anims
 *
 * 最后再统一校验三份文件的动画名集合与 anims.mjs 的期望一致。
 */

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const MOD_ANIM = path.join(HERE, '..', 'src', 'main', 'resources', 'assets', 'galboss', 'animations');

const textOf = (res) => {
  const c = res?.result?.content;
  return Array.isArray(c) ? c.map((x) => x.text ?? JSON.stringify(x)).join('\n') : JSON.stringify(res);
};

await initialize();

// ---- 0. 检查三个项目都在，并记下原来活动的那个（跑完恢复回去）--------------------
const before = textOf(await callTool('get_project_info', {}));
const origin = (before.match(/"name":\s*"([a-z_0-9]+)"/) || [])[1];
console.log(`原活动项目: ${origin}`);
for (const id of CHARACTER_IDS) {
  const probe = textOf(await callTool('risky_eval', {
    code: `(function () {
      var hit = null;
      ModelProject.all.forEach(function (p) { if (p.name === ${JSON.stringify(id)}) hit = p; });
      return hit ? { name: hit.name, uuid: hit.uuid } : { missing: ${JSON.stringify(id)} };
    })()`
  }));
  if (/"missing"/.test(probe)) {
    console.error(`找不到项目 ${id} —— 先在 Blockbench 里打开一次（或跑 node pipeline.mjs make ${id}）`);
    process.exit(1);
  }
  console.log(`  ${id} 已打开`);
}

// ---- 1. 逐个角色：选中 -> 建动画 -> 导出 ------------------------------------------
for (const id of CHARACTER_IDS) {
  console.log(`\n========== ${id} ==========`);
  const sel = textOf(await callTool('risky_eval', {
    code: `(function () {
      var hit = null;
      ModelProject.all.forEach(function (p) { if (p.name === ${JSON.stringify(id)}) hit = p; });
      if (!hit) return { error: ${JSON.stringify(id)} };
      hit.select();
      return { active: Project.name, format: Project.format.id };
    })()`
  }));
  console.log(sel);
  if (!new RegExp(`"active":\\s*"${id}"`).test(sel)) {
    console.error(`切换项目失败，停在 ${id}`);
    process.exit(1);
  }
  // 用子进程跑，保证每次都是干净的 MCP 连接（会话在 Blockbench 侧是全局的）
  execFileSync(process.execPath, ['make_anims.mjs'], { cwd: HERE, stdio: 'inherit' });
  execFileSync(process.execPath, ['export_anims.mjs'], { cwd: HERE, stdio: 'inherit' });
}

// ---- 2. 统一校验 ----------------------------------------------------------------
console.log('\n========== 校验 ==========');
let bad = 0;
for (const id of CHARACTER_IDS) {
  const p = path.join(MOD_ANIM, `${id}.animation.json`);
  if (!fs.existsSync(p)) { console.log(`  ! ${id}: 没有动画文件`); bad++; continue; }
  const data = JSON.parse(fs.readFileSync(p, 'utf8'));
  const got = Object.keys(data.animations || {}).sort();
  const want = animsFor(id).map((a) => `animation.${id}.${a.name}`).sort();
  const same = got.length === want.length && got.every((k, i) => k === want[i]);
  // 时长也必须与 anims.mjs 一致（Java 按名字算 tick）
  const lenBad = [];
  for (const a of animsFor(id)) {
    const anim = data.animations[`animation.${id}.${a.name}`];
    if (!anim) continue;
    if (Math.abs((anim.animation_length ?? -1) - a.len) > 1e-4) {
      lenBad.push(`${a.name}: 文件 ${anim.animation_length} != 期望 ${a.len}`);
    }
  }
  const own = overriddenNames(id);
  console.log(`  ${id}: ${got.length} 段${same ? ' ✓' : ' ✗ 集合不符'}${own.length ? `（专属: ${own.join(', ')}）` : '（全部基础版）'}`);
  if (!same) { console.log(`      缺少 ${want.filter((k) => !got.includes(k)).join(', ') || '无'}`); bad++; }
  for (const l of lenBad) { console.log(`      ! ${l}`); bad++; }
}

// ---- 3. 恢复原来的活动项目 -------------------------------------------------------
if (origin && CHARACTER_IDS.includes(origin)) {
  await callTool('risky_eval', {
    code: `(function () {
      var hit = null;
      ModelProject.all.forEach(function (p) { if (p.name === ${JSON.stringify(origin)}) hit = p; });
      if (hit) hit.select();
      return Project.name;
    })()`
  });
  console.log(`\n活动项目已恢复为 ${origin}`);
}

console.log(bad ? `\n${bad} 个问题` : '\n三份动画全部通过');
process.exit(bad ? 1 : 0);
