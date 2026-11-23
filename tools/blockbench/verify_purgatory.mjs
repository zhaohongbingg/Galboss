// 炼狱七桩 × 炼狱之柱 的文件级交叉校验（不需要启动游戏）。
//
// 为什么需要它：这七个角色是「注册名 = geo 文件名 = 贴图文件名 = 刷怪蛋名 = 语言键 = 战利品表名」
// 的同构矩阵，漏一处不会编译报错，只会在游戏里变成「少了名字 / 少了战利品 / 没渲染器」。
// 这个脚本把 8 个地方一次对完。
//
//   node verify_purgatory.mjs
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const ROOT = path.join(HERE, '..', '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'galboss');
const DATA = path.join(ROOT, 'src', 'main', 'resources', 'data', 'galboss');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'example', 'galboss');

const IDS = ['asmodeus', 'beelzebub', 'belphegor', 'leviathan', 'lucifer', 'mammon', 'satan'];
const ANIMS = ['idle', 'walk', 'run', 'hurt', 'attack', 'death'];

let fails = 0;
let checks = 0;
const ok = (cond, msg) => {
  checks++;
  if (!cond) {
    fails++;
    console.log(`  ✗ ${msg}`);
  }
  return cond;
};

const read = (p) => fs.readFileSync(p, 'utf8');
const exists = (p) => fs.existsSync(p);
const json = (p) => JSON.parse(read(p));

console.log('1) 七个角色：geo / 贴图 / identifier');
const geoIds = new Set();
for (const id of IDS) {
  const geoPath = path.join(ASSETS, 'geo', `purgatory_stake_${id}.geo.json`);
  const texPath = path.join(ASSETS, 'textures', 'entity', `purgatory_stake_${id}.png`);
  if (!ok(exists(geoPath), `${id}: 缺 geo ${path.relative(ROOT, geoPath)}`)) continue;
  ok(exists(texPath), `${id}: 缺贴图 ${path.relative(ROOT, texPath)}`);
  const g = json(geoPath)['minecraft:geometry'][0];
  ok(g.description.identifier === `geometry.purgatory_stake_${id}`,
    `${id}: identifier 应为 geometry.purgatory_stake_${id}，实际 ${g.description.identifier}`);
  ok(g.description.texture_width === 512 && g.description.texture_height === 256,
    `${id}: UV 空间应 512×256，实际 ${g.description.texture_width}×${g.description.texture_height}`);
  ok(g.bones.some((b) => b.name === 'RightHandLocator'), `${id}: 缺 RightHandLocator（柱子挂不上）`);
  geoIds.add(g.description.identifier);
}
console.log(`   ${IDS.length} 个角色 / ${geoIds.size} 个不同 identifier`);

console.log('2) 共用动画：6 段 + 无 molang 残留');
const animPath = path.join(ASSETS, 'animations', 'purgatory_stake.animation.json');
if (ok(exists(animPath), '缺 animations/purgatory_stake.animation.json')) {
  const anims = json(animPath).animations;
  for (const a of ANIMS) {
    ok(!!anims[`animation.purgatory_stake.${a}`], `动画缺 animation.purgatory_stake.${a}`);
  }
  const text = read(animPath);
  ok(!/ysm\.|query\.|math\.|(^|[^a-z])v\./i.test(text), '动画里仍有 molang 表达式（GeckoLib 只认数字）');
  ok(!/"molang"/.test(text), '动画里仍有 molang 伪骨骼');
  ok(!/"lerp_mode"/.test(text), '动画里仍有 lerp_mode（GeckoLib 4.4.2 不认 catmullrom）');
}

console.log('3) 炼狱之柱：geo / 贴图 / 物品模型 / 自转动画');
const pillarGeo = path.join(ASSETS, 'geo', 'item', 'purgatory_pillar.geo.json');
if (ok(exists(pillarGeo), '缺 geo/item/purgatory_pillar.geo.json')) {
  const g = json(pillarGeo)['minecraft:geometry'][0];
  ok(g.description.identifier === 'geometry.purgatory_pillar', '柱子 identifier 不对');
  const rot = g.bones.flatMap((b) => b.cubes ?? []).filter(
    (c) => c.rotation && c.rotation.some((v) => v !== 0)).length;
  ok(rot > 500, `柱子应保留大量自由角度旋转（实际 ${rot}），否则说明被 item-model 削平了`);
}
ok(exists(path.join(ASSETS, 'textures', 'item', 'purgatory_pillar.png')), '缺 textures/item/purgatory_pillar.png');
const pillarModel = path.join(ASSETS, 'models', 'item', 'purgatory_pillar.json');
if (ok(exists(pillarModel), '缺 models/item/purgatory_pillar.json')) {
  const m = json(pillarModel);
  ok(m.parent === 'builtin/entity', '柱子物品模型必须是 builtin/entity（GeoItem 走自定义渲染器）');
  ok(!!m.display?.['thirdperson_righthand'], '柱子物品模型缺 thirdperson_righthand display');
  // 回归护栏：三套手持帧的朝向（这三个数是**实机标定**出来的，不是推的，别凭感觉改）
  //
  //   head（NPC 专用槽）→ rotation.x = -85：把 geo 的 +Y（柱尖）转到骨骼帧的前方
  //   thirdperson_*      → 恒等：原版手持帧本身已经让 +Y 朝向「前方横持」（盔甲架实测）
  //   firstperson_*      → x = -55：原版第一人称手里 +Y 是竖的，压 -55° 才落到「端枪」的角度
  //
  // 历史坑：把 -85 同时用在玩家帧上，玩家手上/身上看到的是一根**朝下吊着**的柱子。
  const head = m.display?.head?.rotation ?? [0, 0, 0];
  ok(head[0] <= -60, `NPC 槽（head）应把柱尖转到前方（rotation.x ≈ -85，实际 ${head[0]}）`);
  // 平移必须为 0：柱子模型原点 = 握把中心，骨骼 RightHandLocator 就在手心，
  // 一旦给偏移，握把就离开手心（实机表现为「武器错位」）。
  const headT = m.display?.head?.translation ?? [0, 0, 0];
  ok(headT.every((v) => Math.abs(v) < 1e-6),
    `NPC 槽（head）的 translation 必须为 0，否则握把离开手心（实际 ${JSON.stringify(headT)}）`);
  const third = m.display?.['thirdperson_righthand']?.rotation ?? [0, 0, 0];
  ok(Math.abs(third[0]) < 15, `第三人称应为恒等朝向（实测水平横持，实际 x=${third[0]}）`);
  const first = m.display?.['firstperson_righthand']?.rotation ?? [0, 0, 0];
  ok(first[0] <= -30 && first[0] >= -75, `第一人称应压在 -30..-75 之间（实测 -55 最像端枪，实际 ${first[0]}）`);
}
const pillarAnim = path.join(ASSETS, 'animations', 'item', 'purgatory_pillar.animation.json');
if (ok(exists(pillarAnim), '缺 animations/item/purgatory_pillar.animation.json')) {
  const anims = json(pillarAnim).animations;
  ok(!!anims['animation.purgatory_pillar.spin'], '柱子缺 spin 动画');
  ok(!!anims['animation.purgatory_pillar.use'], '柱子缺 use 动画（右键动作）');
  ok(anims['animation.purgatory_pillar.use']?.loop === false, 'use 动画必须是一次性（loop:false）');
}
console.log('4) 右键动作：use 动画与触发链路');
const hashCode = read(path.join(JAVA, 'item', 'PurgatoryPillarItem.java'));
ok(/InteractionResultHolder<ItemStack> use\(/.test(hashCode), 'PurgatoryPillarItem 没有重写 use()（右键动画不会触发）');
ok(/triggerAnim\(player, GeoItem\.getOrAssignId/.test(hashCode), 'use() 里没有服务端 triggerAnim');
ok(/triggerableAnim\(ANIM_USE/.test(hashCode), 'controller 没有注册 use 的 triggerableAnim');

console.log('5) 战斗数值：左右键都是 10 伤害、节奏 4 tick');
ok(/getDefaultAttributeModifiers\(/.test(hashCode), '物品没有属性修饰符（左键伤害不会生效）');
ok(/Attributes\.ATTACK_DAMAGE/.test(hashCode), '缺 ATTACK_DAMAGE 修饰符');
ok(/Attributes\.ATTACK_SPEED/.test(hashCode), '缺 ATTACK_SPEED 修饰符（左键 4 tick 节奏靠它）');
ok(/ATTACK_DAMAGE_BONUS = 9\.0/.test(hashCode), '攻击力加成应为 +9.0（玩家基础 1 → 合计 10）');
ok(/ATTACK_SPEED_BONUS = 1\.0/.test(hashCode), '攻速加成应为 +1.0（基础 4.0 → 5.0 → 冷却 4 tick）');

console.log('6) 右键突刺：按住 = 每 4 tick 结算，且不推玩家');
ok(/getUseDuration\(/.test(hashCode), '物品没有 getUseDuration（不会进入"使用中"，突刺无从谈起）');
ok(/onUseTick\(/.test(hashCode), '物品没有 onUseTick（突刺每 tick 的逻辑没地方跑）');
ok(/startUsingItem\(hand\)/.test(hashCode), 'use() 没有 startUsingItem（原版不会进 using 状态）');
ok(/InteractionResultHolder\.consume/.test(hashCode), 'use() 必须返回 consume，否则不会持续使用');
ok(/releaseUsing\(/.test(hashCode), '没有 releaseUsing（松手不会清状态，会留下幽灵突刺）');
const thrust = path.join(JAVA, 'item', 'PurgatoryPillarThrust.java');
if (ok(exists(thrust), '缺 item/PurgatoryPillarThrust.java（右键突刺实现）')) {
  const src = read(thrust);
  // 判定体必须来自模型实测尺寸（geo bbox），不是拍脑袋的 3.5×1.5 大盒子
  ok(/TIP_REACH = 20\.73D \/ 16\.0D/.test(src), '判定体前端应取 geo 柱尖 20.73u / 16');
  ok(/BUTT_REACH = 8\.68D \/ 16\.0D/.test(src), '判定体后端应取 geo 柱尾 8.68u / 16');
  ok(/BODY_RADIUS = 2\.80D \/ 16\.0D/.test(src), '判定体横截面应取 geo 2.8u / 16');
  ok(/DAMAGE_INTERVAL_TICKS = 4/.test(src), '同目标结算间隔应为 4 tick');
  ok(/THRUST_DAMAGE = 10\.0f/.test(src), '突刺伤害应为 10.0');
  ok(/overlapsOrientedBox/.test(src), '缺定向盒三轴分离判定（会退化成"膨胀 AABB"误伤）');
  ok(/invulnerableTime = 0/.test(src), '命中没清无敌帧（4 tick 节奏会被吞）');
  // 核心回归：右键不许再把玩家推着走
  ok(!/setDeltaMovement/.test(src), '突刺类里出现了 setDeltaMovement —— 右键会自己推玩家（需求：不移动）');
  ok(!/hurtMarked/.test(src), '突刺类里出现了 hurtMarked —— 说明还在同步自推速度');
}
ok(!/setDeltaMovement/.test(hashCode), '物品类里出现了 setDeltaMovement —— 右键会自己推玩家（需求：不移动）');
const combat = path.join(JAVA, 'event', 'PurgatoryPillarCombat.java');
if (ok(exists(combat), '缺 event/PurgatoryPillarCombat.java（左键 4 tick 节奏要靠它清无敌帧）')) {
  const src = read(combat);
  ok(/LivingAttackEvent/.test(src), '战斗规则没挂 LivingAttackEvent');
  ok(/invulnerableTime = 0/.test(src), '战斗规则没有清目标无敌帧');
  ok(/getDirectEntity\(\) != player/.test(src), '战斗规则没有限定「直接近战」（会连带让箭矢/法术绕过无敌帧）');
}
if (exists(pillarAnim)) {
  const use = json(pillarAnim).animations['animation.purgatory_pillar.use'];
  const root = use?.bones?.pillar_root?.position ?? {};
  // 柱尖方向 = geo 的 +Y；前突必须朝 +Y（写成负数是"后坐"，不是"突刺"）
  let maxY = -Infinity;
  for (const v of Object.values(root)) {
    const vec = Array.isArray(v) ? v : v.post ?? v.pre ?? [0, 0, 0];
    maxY = Math.max(maxY, vec[1] ?? 0);
  }
  ok(maxY >= 1.5, `use 动画的前突位移应在 +Y（柱尖方向），实测最大 ${maxY}`);
}

console.log('7) 黄金乡行为：进入即登场 + 贝阿朵复活');
const stakes = path.join(JAVA, 'event', 'PurgatoryStakes.java');
if (ok(exists(stakes), '缺 event/PurgatoryStakes.java（黄金乡登场与复活）')) {
  const src = read(stakes);
  ok(/PlayerChangedDimensionEvent/.test(src), '没挂进入维度的钩子');
  ok(/LivingDeathEvent/.test(src), '没挂死亡钩子（复活无从触发）');
  ok(/TickEvent\.ServerTickEvent/.test(src), '复活队列没有 server tick 处理');
  ok(/GoldenLand\.SPAWN/.test(src), '登场圆环应以 GoldenLand.SPAWN 为圆心（玩家坐标在事件触发时还没落地）');
  ok(!/player\.position\(\)/.test(src), '登场圆环别再以玩家实时坐标为准（实测会偏十几格）');
  ok(/hasLivingBeatrice/.test(src), '复活没有校验「贝阿朵是否活着」——这条是玩家唯一的反制点');
  ok(/REVIVE_DELAY_TICKS = 40/.test(src), '复活延迟应为 40 tick');
  for (const id of ['ASMODEUS', 'BEELZEBUB', 'BELPHEGOR', 'LEVIATHAN', 'LUCIFER', 'MAMMON', 'SATAN']) {
    ok(new RegExp(`ModEntities\\.${id}\\.get\\(\\)`).test(src), '登场列表缺 ' + id);
  }
}

console.log('8) 七位技能表：每人 1 被动 + 2 主动');
// 被动有两种实现位：`stakePassiveTick`（周期性）与 `onStakeHit`（命中时）。
// 玛门的「黄金律法」是命中抽经验，就该写在 onStakeHit 里，不算漏项。
const ACTIVE_HOOKS = ['castSkillOne', 'castSkillTwo'];
const PASSIVE_HOOKS = ['stakePassiveTick', 'onStakeHit'];
const CLASS_NAME = {
  asmodeus: 'Asmodeus', beelzebub: 'Beelzebub', belphegor: 'Belphegor',
  leviathan: 'Leviathan', lucifer: 'Lucifer', mammon: 'Mammon', satan: 'Satan'
};
for (const id of IDS) {
  const file = path.join(JAVA, 'entity', `${CLASS_NAME[id]}Entity.java`);
  if (!ok(exists(file), `${id}: 找不到实体类`)) continue;
  const src = read(file);
  for (const hook of ACTIVE_HOOKS) {
    ok(new RegExp(hook).test(src), `${id}: 没有实现 ${hook}（技能表漏了一项）`);
  }
  ok(PASSIVE_HOOKS.some((hook) => new RegExp(hook).test(src)), `${id}: 没有实现核心被动`);
}
ok(exists(path.join(JAVA, 'stake', 'StakeFx.java')), '缺 stake/StakeFx.java（技能共用原语）');

console.log('9) 注册矩阵：刷怪蛋模型 / 语言 / 战利品表 / Java');
const zh = json(path.join(ASSETS, 'lang', 'zh_cn.json'));
const en = json(path.join(ASSETS, 'lang', 'en_us.json'));
const entitiesJava = read(path.join(JAVA, 'entity', 'ModEntities.java'));
const itemsJava = read(path.join(JAVA, 'item', 'ModItems.java'));
const clientJava = read(path.join(JAVA, 'client', 'ClientSetup.java'));
for (const id of IDS) {
  ok(exists(path.join(ASSETS, 'models', 'item', `${id}_spawn_egg.json`)), `${id}: 缺刷怪蛋物品模型`);
  ok(exists(path.join(DATA, 'loot_tables', 'entities', `${id}.json`)), `${id}: 缺战利品表`);
  ok(!!zh[`entity.galboss.${id}`], `zh_cn 缺 entity.galboss.${id}`);
  ok(!!en[`entity.galboss.${id}`], `en_us 缺 entity.galboss.${id}`);
  ok(!!zh[`item.galboss.${id}_spawn_egg`], `zh_cn 缺 item.galboss.${id}_spawn_egg`);
  ok(!!en[`item.galboss.${id}_spawn_egg`], `en_us 缺 item.galboss.${id}_spawn_egg`);
  ok(new RegExp(`stake\\("${id}"`).test(entitiesJava), `ModEntities 未注册 ${id}`);
  ok(new RegExp(`"${id}_spawn_egg"`).test(itemsJava), `ModItems 缺 ${id}_spawn_egg`);
  ok(new RegExp(`ModEntities\\.${id.toUpperCase()}\\.get\\(\\), PurgatoryStakeRenderer::new`)
    .test(clientJava), `ClientSetup 未注册 ${id} 的渲染器`);
  ok(!!zh['item.galboss.purgatory_pillar'], '语言缺 item.galboss.purgatory_pillar');
  ok(/"purgatory_pillar"/.test(itemsJava), 'ModItems 未注册 purgatory_pillar');
}

console.log(`\n${fails === 0 ? 'PASS' : 'FAIL'}：${checks} 项检查，${fails} 处失败`);
process.exit(fails === 0 ? 0 : 1);
