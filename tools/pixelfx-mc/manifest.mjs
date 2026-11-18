// GalBoss 像素特效烘焙清单（唯一来源）。
//
// 与 tools/pixelfx-mc/CONTRACT.md 第 3 节一一对应：slug 是 MC 侧字符串 ID，pixelfxId 是
// D:\game\mc\_refs\pixelfx\src\fx 下的特效路径。导出器（export-pixelfx.mjs）只认这份清单，
// README / 元数据都以它为准。
//
// 硬约束：这里只能收录 pixelfx 中 `meta.actor` 为空的特效。带 actor 的版本会在帧里画出
// 灰色替身人（stockActor），进游戏就是一块假人剪影，绝不能导入。导出器会再校验一次，
// 发现 actor 直接抛错退出。
//
// seed 逐条写死（11…20，与已落地产物一致），保证每次烘焙逐像素可复现。

// 深 → 亮（8 级 ramp）。pixelfx 的 effects 会把任意长度的 ramp 重采样到自己需要的级数，
// 所以这里统一给 8 级，和契约第 3 节完全一致。
export const PALETTES = {
  // 桐香·猩红（铁拳）
  tohkaCrimson: ["#1c0a10", "#3d0f18", "#6f1622", "#a8222c", "#d93a38", "#ff7a5c", "#ffc2a0", "#fff0e0"],
  // 桐香·纪律金（判决铃 / 召集 / 全校裁决）
  tohkaGold: ["#241703", "#4a2c08", "#7d4a0e", "#b87417", "#e0a12b", "#ffd25a", "#fff0a8", "#ffffff"],
  // 桐香·威压紫（禁足令 / 二阶段）
  tohkaViolet: ["#120a1e", "#241238", "#3f1f5e", "#5c2f86", "#7d49b0", "#a471d8", "#d3aef5", "#f4e6ff"],
  // 礼·琥珀（枪击 / 暴击）
  reiAmber: ["#1c1206", "#3a2408", "#6b410c", "#9c5f12", "#cc8420", "#ffb347", "#ffd98a", "#fff4d6"],
  // 郁子·天青（格挡 / 狂战震地）
  ikukoAzure: ["#081420", "#0f2942", "#1a4468", "#24628f", "#2f86bf", "#5cb3e0", "#a3dbf2", "#eafaff"],
};

/**
 * 10 条导出项。字段：
 * - slug         MC 侧字符串 ID（小写字母/数字/下划线）
 * - pixelfxId    pixelfx 特效路径（src/fx 下，不带 .js）
 * - palette      覆盖特效默认 ramp（深→亮 8 级）
 * - seed         固定随机种子；不同 slug 用不同 seed，避免同源特效形状完全雷同
 * - anchor       ground（贴地四边形）/ billboard（面向相机的立牌）
 * - defaultScale 该特效 1 像素对应的世界单位（Java 侧再乘调用方传入的倍数）
 * - options      除 palette 外的额外覆盖；不写就用特效 meta.options 的默认值
 * - maxDuration  可选，单位秒；渲染后按 round(maxDuration * 20) 截断帧数
 * - loopOverride 可选，覆盖元数据里的 loop 字段（比如源特效是 loop、但技能只要播一次）
 * - note         给人看的中文说明，不写进元数据 JSON
 *
 * 注意 `tohka_iron_fist` 与 `tohka_bell_wave` 同源（都是 impacts/ground-slam）：
 * 铁拳用猩红、判决铃用纪律金并截断到 2.25s / 45 帧贴合技能节奏。契约允许同一 pixelfx id
 * 用不同 palette 出多个 slug。
 */
export const EFFECTS = [
  {
    slug: "tohka_iron_fist",
    pixelfxId: "impacts/fist-slam",
    palette: PALETTES.tohkaCrimson,
    seed: 11,
    anchor: "ground",
    defaultScale: 0.02,
    note: "桐香·铁拳制裁 IronFistSkill.hit()：两道月牙冲击波贴地摊开。",
  },
  {
    slug: "tohka_bell_wave",
    pixelfxId: "impacts/ground-slam",
    palette: PALETTES.tohkaGold,
    seed: 12,
    anchor: "ground",
    defaultScale: 0.021,
    maxDuration: 2.25,
    note: "桐香·判决铃冲击波 JudgmentBellSkill 波前：ground-slam 纪律金版，截断到 2.25s / 45 帧。",
  },
  {
    slug: "tohka_confinement_sigil",
    pixelfxId: "shadow/hex-sigil",
    palette: PALETTES.tohkaViolet,
    seed: 13,
    anchor: "billboard",
    defaultScale: 0.02,
    note: "桐香·禁足令 ConfinementSkill.start()：脚下符阵立起。",
  },
  {
    slug: "tohka_convocation_circle",
    // 原选型 arcane/magic-circle-cast 是「地面法阵 + 竖直光柱」，ground 投影后光柱会被压成一条射向
    // 远处的光带（Lead 实机复检确认）。改用 arcane/rune-circle：纯地面透视法阵 + 上升光点，无竖直元素。
    pixelfxId: "arcane/rune-circle",
    palette: PALETTES.tohkaGold,
    seed: 14,
    anchor: "ground",
    defaultScale: 0.02,
    // rune-circle 原 meta 是 loop=true；技能是一段一次性召唤，所以截到前 2.0s / 40 帧并强制 loop=false。
    maxDuration: 2.0,
    loopOverride: false,
    note: "桐香·学生会召集 ConvocationSkill.start()：地面透视法阵 + 上升光点（纪律金，一次性 40 帧）。",
  },
  {
    slug: "tohka_verdict_smite",
    pixelfxId: "holy/smite",
    palette: PALETTES.tohkaGold,
    seed: 15,
    anchor: "billboard",
    defaultScale: 0.02,
    note: "桐香·全校裁决 VerdictSkill 命中每人：金色圣裁砸落。",
  },
  {
    slug: "tohka_phase2_eclipse",
    pixelfxId: "shadow/eclipse",
    palette: PALETTES.tohkaViolet,
    seed: 16,
    anchor: "billboard",
    defaultScale: 0.02,
    note: "桐香·二阶段 enterPhase2()：循环日蚀（loop，寿命 50 tick 由 Java 侧控制）。",
  },
  {
    slug: "rei_bullet_impact",
    pixelfxId: "impacts/bullet-impact",
    palette: PALETTES.reiAmber,
    seed: 17,
    anchor: "billboard",
    // 内容稀疏、体积小，放大到 0.030 才在实机里读得清（像素放大更硬朗，符合像素风）
    defaultScale: 0.03,
    note: "礼·枪击命中：琥珀色弹着点迸溅（origin 偏心，pivot 按裁剪实算）。",
  },
  {
    slug: "rei_crit_hit",
    pixelfxId: "impacts/crit-hit",
    palette: PALETTES.reiAmber,
    seed: 18,
    anchor: "billboard",
    defaultScale: 0.028,
    note: "礼·连段终结 / 高伤命中：八向爆闪。",
  },
  {
    slug: "ikuko_parry_flash",
    pixelfxId: "impacts/parry",
    palette: PALETTES.ikukoAzure,
    seed: 19,
    anchor: "billboard",
    defaultScale: 0.03,
    note: "郁子·刀锋相交：天青色格挡火花。",
  },
  {
    slug: "ikuko_quake",
    pixelfxId: "nature/quake",
    palette: PALETTES.ikukoAzure,
    seed: 20,
    anchor: "ground",
    // 263×82 扁长条，低视角容易看成一条线，放大到 0.027 让裂谷更明显
    defaultScale: 0.027,
    note: "郁子·狂战·一斉突撃落地冲击波：地面裂开三道。",
  },
  {
    slug: "epitaph_lightning_bolt",
    pixelfxId: "lightning/lightning-strike",
    palette: PALETTES.reiAmber,
    seed: 21,
    anchor: "billboard",
    defaultScale: 0.025,
    note: "神话·天降雷霆：替换原版裁击。",
  },
  {
    slug: "epitaph_spear_fall",
    pixelfxId: "ice/icicle-fall",
    palette: PALETTES.reiAmber,
    seed: 22,
    anchor: "billboard",
    defaultScale: 0.025,
    note: "神话·天之陨枪：枪雨。",
  },
  {
    slug: "epitaph_spear_impact",
    pixelfxId: "explosions/plasma-explosion",
    palette: PALETTES.reiAmber,
    seed: 23,
    anchor: "ground",
    defaultScale: 0.03,
    note: "神话·枪击炸裂。",
  },
  {
    slug: "epitaph_storm_sky",
    pixelfxId: "lightning/storm-cloud",
    palette: PALETTES.tohkaViolet,
    seed: 24,
    anchor: "billboard",
    defaultScale: 0.027,
    maxDuration: 2.0,
    loopOverride: true,
    note: "神话·雷云天幕。",
  },
  {
    slug: "epitaph_gungnir",
    pixelfxId: "holy/divine-lance",
    palette: PALETTES.tohkaGold,
    seed: 25,
    anchor: "billboard",
    defaultScale: 0.035,
    note: "神话·永恒之枪 Gungnir。",
  },
  {
    slug: "epitaph_mjolnir",
    pixelfxId: "impacts/hammer-smash",
    palette: PALETTES.tohkaViolet,
    seed: 26,
    anchor: "billboard",
    defaultScale: 0.04,
    note: "神话·雷神之锤 Mjolnir。",
  },
  {
    slug: "epitaph_aegis",
    pixelfxId: "holy/shield-aura",
    palette: PALETTES.tohkaGold,
    seed: 27,
    anchor: "billboard",
    defaultScale: 0.04,
    note: "神话·雅典娜之盾 Aegis。",
  },
];
