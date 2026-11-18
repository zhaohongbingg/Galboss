import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// ============================================================================
// 黑山羊仆从 v6（外部交付模型）动画生成
//
// 输入：black_goat_servant_v6_gift.bbmodel（"羊首侍从_肩背后枕精修"，别人交付的
//       geckolib_model 工程：62 骨骼 / 363 立方体 / 1024² 贴图 / idle 4.8s + walk 1.6s）
// 输出：src/main/resources/assets/galboss/animations/black_goat_servant.animation.json
//
// 为什么要脚本而不是手写 JSON：
//   1. 交付模型自带的 idle / walk 是 20fps 烘焙曲线（每根骨骼几十个关键帧），
//      手抄没法保证不漏骨骼，重跑一次模型就能整体重生成。
//   2. 另外 4 段（attack / cast / hurt / death）用「BB 编辑空间写姿势」的方式写，
//      导出变换集中在这一个文件里做，符号写反只在这里改一处。
//
// 导出变换（不是猜的：Blockbench geckolib 插件 v4.2.5 的
//   checkAndPatchKeyframeValues()，见 %APPDATA%/Blockbench/plugins/geckolib.js）
//   - rotation: x、y 取反，z 保留
//   - position: x 取反，y、z 保留
//   - scale   : 原样
//   插件导出 geo 时对骨骼 pivot / 立方体 origin 也做 X 取反，GeckoLib 载入时再镜像回来，
//   所以「编辑器里看到的样子 = 游戏里的样子」，谁也不用反着写。
//
// BB 编辑空间的符号语义（本模型：+X = 角色左手侧，-Z = 正面，原点在脚底）：
//   朝下的骨骼（四肢）：rotation.x 为正 = 向前摆（-Z）；为负 = 向后摆
//   朝上的骨骼（chest / pelvis）：rotation.x 为正 = 后仰；为负 = 前倾
//   head（snout 朝 -Z 伸出）：rotation.x 为正 = 抬头，为负 = 低头
//   tail（朝 +Z 伸出）：rotation.x 为正 = 尾巴下压，为负 = 尾巴上翘
//   rotation.z：右臂（-X 侧）为正 = 内收、为负 = 外展；左臂（+X 侧）相反
// ============================================================================

const HERE = dirname(fileURLToPath(import.meta.url));
const RES = join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'galboss');
const SRC_BBMODEL = join(HERE, 'black_goat_servant_v6_gift.bbmodel');
/** GOAT_OUT 可覆盖输出路径（A/B 对照用），默认写回资源目录。 */
const OUT_ANIM = process.env.GOAT_OUT ? process.env.GOAT_OUT : join(RES, 'animations', 'black_goat_servant.animation.json');
const GEO = join(RES, 'geo', 'black_goat_servant.geo.json');

const PREFIX = 'animation.black_goat_servant.';
/** 交付工程与原 v1-v5 一致：20fps 网格（0.05s）。 */
const T = (n) => +(n / 20).toFixed(4);
const r4 = (v) => Math.round(v * 10000) / 10000;

// ---------------------------------------------------------------- 空间变换
const rotXf = ([x, y, z]) => [r4(-x), r4(-y), r4(z)];
const posXf = ([x, y, z]) => [r4(-x), r4(y), r4(z)];
const scaleXf = ([x, y, z]) => [r4(x), r4(y), r4(z)];

const CHANNEL_XF = { rotation: rotXf, position: posXf, scale: scaleXf };

/**
 * 预览变换（GOAT_PREVIEW=1）：输出一份「能喂 pose_tool 复现游戏画面」的动画文件。
 *
 * 为什么不是原样输出：pose_tool 直接按 file 数值渲染，而游戏里走了「导出取反 + GeckoLib
 * 载入再取反」这一对相互抵消的变换，最终 = BB 编辑空间的角度；同时 geo 里的骨骼位置是 BB 的
 * X 镜像。要把「游戏姿态」搬进 pose_tool 的镜像空间，旋转必须做镜像共轭 M·R·M：
 *
 *     rotation → (x, -y, -z)      position → (-x, y, z)
 *
 * （M = diag(-1,1,1)：M·Rx·M = Rx，M·Ry·M = Ry(-θ)，M·Rz·M = Rz(-θ)。）
 * 直接用原值喂 pose_tool 会把 y/z 轴动作画反 —— 实测顶撞「手臂后张」会被画成「手臂内收前挤」，
 * 于是凭空报出 16~30 对假穿模。左右会呈镜像（对称招式无差别，不对称的自知即可）。
 */
const PREVIEW = process.env.GOAT_PREVIEW === '1';
const RAW_XF = {
  rotation: ([x, y, z]) => [r4(x), r4(-y), r4(-z)],
  position: ([x, y, z]) => [r4(-x), r4(y), r4(z)],
  scale: (v) => v
};

/**
 * 交付模型动画的导出变换。GOAT_IMPORTED_MODE=identity 时不做任何取反，
 * 只用于 A/B 实拍对照（确认「插件导出取反」这一条到底对不对）。
 */
const IMPORTED_XF = PREVIEW || process.env.GOAT_IMPORTED_MODE === 'identity' ? RAW_XF : CHANNEL_XF;
/** 手写动画的导出变换：正式产物取反；预览模式原样。 */
const AUTHORED_XF = PREVIEW ? RAW_XF : CHANNEL_XF;

// ---------------------------------------------------------------- 读交付工程
const bb = JSON.parse(readFileSync(SRC_BBMODEL, 'utf8'));
const nameByUuid = new Map((bb.groups ?? []).map((g) => [g.uuid, g.name ?? '']));
const geo = JSON.parse(readFileSync(GEO, 'utf8'))['minecraft:geometry'][0];
const geoBones = new Set(geo.bones.map((b) => b.name));

const warnings = [];

/** bbmodel 烘焙动画 → GeckoLib animation 对象。 */
function fromBbmodel(anim) {
  const bones = {};
  let keys = 0;
  for (const [uuid, animator] of Object.entries(anim.animators ?? {})) {
    const bone = nameByUuid.get(uuid);
    if (!bone) { warnings.push(`动画 ${anim.name} 里的骨骼 ${uuid} 在 outliner 里找不到`); continue; }
    for (const channel of ['rotation', 'position', 'scale']) {
      const kfs = (animator.keyframes ?? []).filter((k) => k.channel === channel);
      if (!kfs.length) continue;
      const xf = IMPORTED_XF[channel];
      const sorted = kfs.slice().sort((a, b) => a.time - b.time);
      if (sorted.length === 1) {
        const dp = sorted[0].data_points[0];
        bones[bone] ??= {};
        bones[bone][channel] = { vector: xf([Number(dp.x), Number(dp.y), Number(dp.z)]) };
        keys++;
        continue;
      }
      const out = {};
      for (const kf of sorted) {
        const dp = kf.data_points[0];
        out[String(r4(kf.time))] = { vector: xf([Number(dp.x), Number(dp.y), Number(dp.z)]) };
        keys++;
      }
      bones[bone] ??= {};
      bones[bone][channel] = out;
    }
  }
  const loop = anim.loop === 'loop' ? true
    : anim.loop === 'hold' ? 'hold_on_last_frame'
    : anim.loop === 'once' ? false
    : true;
  return { length: r4(anim.length), loop, bones, keys };
}

// ---------------------------------------------------------------- 手写动画
// 姿势一律在 BB 编辑空间写（见文件头符号语义），[时间(帧), [x,y,z]]。
const K = (frame, v) => [T(frame), v];

/** 把 BB 空间的通道关键帧表转成 GeckoLib 通道对象。 */
function channel(keys, xf) {
  if (keys.length === 1) return { vector: xf(keys[0][1]) };
  const out = {};
  for (const [t, v] of keys) out[String(r4(t))] = { vector: xf(v) };
  return out;
}

/** 左右镜像一个姿势向量：绕 YZ 平面翻面 → rotation 的 y/z 取反，position 的 x 取反。 */
const mirrorVector = (ch, v) => ch === 'rotation' ? [v[0], -v[1], -v[2]]
  : ch === 'position' ? [-v[0], v[1], v[2]]
  : v;

/**
 * authored 骨架：{ bone: { rotation: K(...), position: K(...) } } → bones 对象。
 *
 * 规则：
 *   - 只写右侧（r_*）时，自动镜像补出对应的 l_*（保证左右对称的招式不用写两遍）；
 *   - **显式写了 l_\* 的以手写为准**，不再镜像 —— 顶撞这类需要「前腿撑、后腿蹬」的不对称站姿靠这条；
 *   - 中轴骨骼（pelvis / chest / head / tail / root…）原样。
 */
function authoredBones(spec) {
  const provided = new Set(Object.keys(spec));
  const entries = [];
  for (const [bone, channels] of Object.entries(spec)) entries.push([bone, channels, false]);
  for (const [bone, channels] of Object.entries(spec)) {
    if (!bone.startsWith('r_')) continue;
    const mate = `l_${bone.slice(2)}`;
    if (!provided.has(mate)) entries.push([mate, channels, true]);
  }
  const bones = {};
  for (const [bone, channels, mirror] of entries) {
    bones[bone] = {};
    for (const [ch, keys] of Object.entries(channels)) {
      const mapped = mirror ? keys.map(([t, v]) => [t, mirrorVector(ch, v)]) : keys;
      bones[bone][ch] = channel(mapped, AUTHORED_XF[ch]);
    }
  }
  return bones;
}

// 攻击：前臂连续刺拳（三连击）。Java 侧 ATTACK_WINDUP_TICKS = 8，即**第 0.4s 那一下**结算伤害，
// 所以三连击里最重的一记（左前臂全力伸出）正好落在 f8。
//
// 这一版和上一版（羊角冲锋）的区别：上一版本质是"突进"——骨盆前推 1.5、后腿蹬直，读起来是快速移动；
// 攻击动作应该**主体几乎不前移**，靠肘的收/伸把拳送出去，节奏是"收—伸—收—伸"。
//
// 节拍（0.8s / 20fps，帧 → 秒 = /20）：
//   f0  中立
//   f2  举拳（双肘收 85°，拳到胸前）—— 起手就把"拳架"立起来
//   f4  **刺拳 1（右前臂）**：上臂前送 74°、前臂**伸直** 2°，一帧完成
//   f5  回收（一帧，快收才有打击感）
//   f6  拳架（右肘再收 82°）
//   f8  **刺拳 2（左前臂，重击 = 伤害结算帧）**：上臂 80°、前臂 4°、躯干反向拧 11°、脚下压 0.7
//   f9  回收
//   f10 拳架
//   f12 **刺拳 3（右前臂，短一点）**：上臂 68°、前臂 8°
//   f13 回收
//   f16 归位
//
// 关键点：
//   1. **前臂是主打**（r_forearm/l_forearm 从 +85° 收到 +2°，等于把肘"弹开"），上臂只做辅助前送；
//   2. 躯干用 Y 轴反向拧（右拳 → chest.y 负、头 y 正补回来，保持朝前），不是整体前倾；
//   3. 骨盆只在 z 上小幅前压 0.5~1.1（1 格 = 16 单位，视觉几乎不位移），腿只做轻微屈膝吸收；
//   4. 左右臂全部显式写，不用镜像（三连击本来就是左右交替）。

// 握拳：交付模型每只手 4 指 ×3 段 + 拇指 ×3 段；出手时收成拳，收招时松开。
// 手指链朝下伸，+x 即往手心卷；拇指天生偏位，卷的角度小一点。
const FIST = (() => {
  const spec = {};
  for (const side of ['r', 'l']) {
    for (const f of ['index', 'middle', 'ring', 'little']) {
      spec[`${side}_${f}`] = { rotation: [K(0, [0, 0, 0]), K(2, [80, 0, 0]), K(13, [80, 0, 0]), K(16, [0, 0, 0])] };
      spec[`${side}_${f}_mid`] = { rotation: [K(0, [0, 0, 0]), K(2, [75, 0, 0]), K(13, [75, 0, 0]), K(16, [0, 0, 0])] };
      spec[`${side}_${f}_tip`] = { rotation: [K(0, [0, 0, 0]), K(2, [45, 0, 0]), K(13, [45, 0, 0]), K(16, [0, 0, 0])] };
    }
    spec[`${side}_thumb`] = { rotation: [K(0, [0, 0, 0]), K(2, [40, 0, 0]), K(13, [40, 0, 0]), K(16, [0, 0, 0])] };
    spec[`${side}_thumb_mid`] = { rotation: [K(0, [0, 0, 0]), K(2, [45, 0, 0]), K(13, [45, 0, 0]), K(16, [0, 0, 0])] };
    spec[`${side}_thumb_tip_bone`] = { rotation: [K(0, [0, 0, 0]), K(2, [30, 0, 0]), K(13, [30, 0, 0]), K(16, [0, 0, 0])] };
  }
  return spec;
})();

const ATTACK = {
  ...FIST,
  pelvis: {
    // y 轴与胸口反向拧一点，避免上身"一块板"
    rotation: [K(0, [0, 0, 0]), K(2, [-2, 2, 0]), K(4, [-3, 4, 0]), K(8, [-4, -5, 0]), K(12, [-3, 3, 0]), K(16, [0, 0, 0])],
    // 只有很小的前后压，不做位移
    position: [K(0, [0, 0, 0]), K(2, [0, -0.4, 0.2]), K(4, [0, -0.5, -0.5]), K(6, [0, -0.4, 0]), K(8, [0, -0.7, -1.1]), K(10, [0, -0.4, 0]), K(12, [0, -0.5, -0.6]), K(13, [0, -0.3, 0]), K(16, [0, 0, 0])]
  },
  chest: {
    // 右拳（f4/f12）→ y 负；左拳（f8）→ y 正
    rotation: [K(0, [0, 0, 0]), K(2, [-3, 0, 0]), K(4, [-5, -9, 0]), K(6, [-3, 2, 0]), K(8, [-9, 11, 0]), K(10, [-3, -2, 0]), K(12, [-5, -7, 0]), K(16, [0, 0, 0])]
  },
  head: {
    // 头用 y 反向补回，始终保持朝前盯着目标
    rotation: [K(0, [0, 0, 0]), K(2, [-3, 0, 0]), K(4, [-5, 8, 0]), K(6, [-3, -1, 0]), K(8, [-7, -10, 0]), K(10, [-3, 2, 0]), K(12, [-4, 6, 0]), K(16, [0, 0, 0])]
  },
  r_ear: { rotation: [K(0, [0, 0, 0]), K(2, [-6, 0, -8]), K(4, [10, 0, -18]), K(6, [4, 0, -10]), K(8, [14, 0, -22]), K(10, [4, 0, -10]), K(12, [8, 0, -16]), K(16, [0, 0, 0])] },
  // ---- 右臂：拳架把拳收到胸前（肘 105°）→ 刺出时肘几乎伸直（2°），收得越紧出得越狠
  r_arm: { rotation: [K(0, [0, 0, 0]), K(2, [22, 0, -8]), K(4, [78, 0, -6]), K(5, [58, 0, -7]), K(6, [22, 0, -8]), K(8, [12, 0, -10]), K(10, [22, 0, -8]), K(12, [72, 0, -6]), K(13, [48, 0, -7]), K(16, [0, 0, 0])] },
  r_forearm: { rotation: [K(0, [0, 0, 0]), K(2, [105, 0, 0]), K(4, [2, 0, 0]), K(5, [45, 0, 0]), K(6, [105, 0, 0]), K(8, [112, 0, 0]), K(10, [105, 0, 0]), K(12, [8, 0, 0]), K(13, [50, 0, 0]), K(16, [0, 0, 0])] },
  r_hand: { rotation: [K(0, [0, 0, 0]), K(2, [-18, 0, 0]), K(4, [12, 0, 0]), K(5, [-8, 0, 0]), K(6, [-16, 0, 0]), K(8, [-22, 0, 0]), K(10, [-16, 0, 0]), K(12, [10, 0, 0]), K(13, [-6, 0, 0]), K(16, [0, 0, 0])] },
  // ---- 左臂：手写镜像版（缺省镜像会让三连击变成"双手同时出拳"，所以显式写）
  l_arm: { rotation: [K(0, [0, 0, 0]), K(2, [22, 0, 8]), K(4, [12, 0, 10]), K(6, [22, 0, 8]), K(8, [84, 0, 6]), K(9, [58, 0, 7]), K(10, [22, 0, 8]), K(12, [12, 0, 10]), K(16, [0, 0, 0])] },
  l_forearm: { rotation: [K(0, [0, 0, 0]), K(2, [105, 0, 0]), K(4, [112, 0, 0]), K(6, [105, 0, 0]), K(8, [4, 0, 0]), K(9, [45, 0, 0]), K(10, [105, 0, 0]), K(12, [112, 0, 0]), K(16, [0, 0, 0])] },
  l_hand: { rotation: [K(0, [0, 0, 0]), K(2, [-18, 0, 0]), K(4, [-22, 0, 0]), K(6, [-16, 0, 0]), K(8, [14, 0, 0]), K(9, [-6, 0, 0]), K(10, [-16, 0, 0]), K(12, [-20, 0, 0]), K(16, [0, 0, 0])] },
  // ---- 腿：只做屈膝吸收，不做蹬地（蹬地就变成突进了）
  r_thigh: { rotation: [K(0, [0, 0, 0]), K(2, [-2, 0, 0]), K(4, [4, 0, 0]), K(8, [2, 0, 0]), K(12, [4, 0, 0]), K(16, [0, 0, 0])] },
  r_shin: { rotation: [K(0, [0, 0, 0]), K(2, [-6, 0, 0]), K(4, [-8, 0, 0]), K(8, [-10, 0, 0]), K(12, [-8, 0, 0]), K(16, [0, 0, 0])] },
  r_foot: { rotation: [K(0, [0, 0, 0]), K(2, [4, 0, 0]), K(4, [6, 0, 0]), K(8, [8, 0, 0]), K(12, [6, 0, 0]), K(16, [0, 0, 0])] },
  // ---- 尾巴：跟着拧身甩，给"发力"一点惯性反馈
  tail: { rotation: [K(0, [0, 0, 0]), K(4, [-12, 0, -8]), K(8, [-20, 0, 10]), K(12, [-12, 0, -7]), K(16, [0, 0, 0])] },
  tail_tip: { rotation: [K(0, [0, 0, 0]), K(4, [-9, 0, -6]), K(8, [-15, 0, 8]), K(12, [-9, 0, -5]), K(16, [0, 0, 0])] },
  // ---- 燕尾：每次出拳向前兜一下（身后气流），幅度小，别抢戏
  r_coat_front: { rotation: [K(0, [0, 0, 0]), K(4, [10, 0, 0]), K(6, [6, 0, 0]), K(8, [14, 0, 0]), K(10, [6, 0, 0]), K(12, [10, 0, 0]), K(16, [0, 0, 0])] },
  r_coat_front_tip: { rotation: [K(0, [0, 0, 0]), K(4, [14, 0, 0]), K(6, [8, 0, 0]), K(8, [19, 0, 0]), K(10, [8, 0, 0]), K(12, [14, 0, 0]), K(16, [0, 0, 0])] },
  r_coat_back: { rotation: [K(0, [0, 0, 0]), K(4, [-8, 0, 0]), K(8, [-10, 0, 0]), K(12, [-8, 0, 0]), K(16, [0, 0, 0])] },
  r_coat_back_tip: { rotation: [K(0, [0, 0, 0]), K(4, [-11, 0, 0]), K(8, [-14, 0, 0]), K(12, [-11, 0, 0]), K(16, [0, 0, 0])] }
};

// 失明咒誓：Java 侧 CURSE_CAST_TICKS = 16（0.8s 定格结算），长度 1.1s
const CAST = {
  pelvis: {
    rotation: [K(0, [0, 0, 0]), K(5, [-2, 0, 0]), K(11, [-3, 0, 0]), K(16, [-6, 0, 0]), K(22, [-1, 0, 0])],
    position: [K(0, [0, 0, 0]), K(5, [0, 0.4, 0]), K(11, [0, 0.6, 0]), K(16, [0, 0.2, -0.6]), K(22, [0, 0, 0])]
  },
  chest: {
    rotation: [K(0, [0, 0, 0]), K(5, [-3, 0, 0]), K(11, [-4, 0, 0]), K(16, [-11, 0, 0]), K(22, [-3, 0, 0])]
  },
  head: {
    rotation: [K(0, [0, 0, 0]), K(5, [-8, 0, 0]), K(11, [-11, 0, 0]), K(16, [-18, 0, 0]), K(22, [-5, 0, 0])]
  },
  r_ear: { rotation: [K(0, [0, 0, 0]), K(5, [-10, 0, -12]), K(11, [-14, 0, -16]), K(16, [-6, 0, -6]), K(22, [0, 0, 0])] },
  l_ear: { rotation: [K(0, [0, 0, 0]), K(5, [-10, 0, 12]), K(11, [-14, 0, 16]), K(16, [-6, 0, 6]), K(22, [0, 0, 0])] },
  // 双臂前举念咒，第 16 帧推到最前
  // 双臂高举过肩念咒（x 超过 90 = 举过水平线），第 16 帧推到最前
  r_arm: { rotation: [K(0, [0, 0, 0]), K(5, [104, 0, -14]), K(11, [112, 0, -17]), K(16, [124, 0, -20]), K(22, [58, 0, -8])] },
  r_forearm: { rotation: [K(0, [0, 0, 0]), K(5, [-52, 0, 0]), K(11, [-48, 0, 0]), K(16, [-30, 0, 0]), K(22, [-30, 0, 0])] },
  r_hand: { rotation: [K(0, [0, 0, 0]), K(5, [-30, 0, 0]), K(11, [-36, 0, 0]), K(16, [6, 0, 0]), K(22, [-14, 0, 0])] },
  l_arm: { rotation: [K(0, [0, 0, 0]), K(5, [104, 0, 14]), K(11, [112, 0, 17]), K(16, [124, 0, 20]), K(22, [58, 0, 8])] },
  l_forearm: { rotation: [K(0, [0, 0, 0]), K(5, [-52, 0, 0]), K(11, [-48, 0, 0]), K(16, [-30, 0, 0]), K(22, [-30, 0, 0])] },
  l_hand: { rotation: [K(0, [0, 0, 0]), K(5, [-30, 0, 0]), K(11, [-36, 0, 0]), K(16, [6, 0, 0]), K(22, [-14, 0, 0])] },
  // 双足踏稳、膝盖微屈
  r_thigh: { rotation: [K(0, [0, 0, 0]), K(5, [6, 0, 0]), K(16, [8, 0, 0]), K(22, [2, 0, 0])] },
  r_shin: { rotation: [K(0, [0, 0, 0]), K(5, [-8, 0, 0]), K(16, [-10, 0, 0]), K(22, [-3, 0, 0])] },
  r_foot: { rotation: [K(0, [0, 0, 0]), K(5, [4, 0, 0]), K(16, [5, 0, 0]), K(22, [1, 0, 0])] },
  l_thigh: { rotation: [K(0, [0, 0, 0]), K(5, [6, 0, 0]), K(16, [8, 0, 0]), K(22, [2, 0, 0])] },
  l_shin: { rotation: [K(0, [0, 0, 0]), K(5, [-8, 0, 0]), K(16, [-10, 0, 0]), K(22, [-3, 0, 0])] },
  l_foot: { rotation: [K(0, [0, 0, 0]), K(5, [4, 0, 0]), K(16, [5, 0, 0]), K(22, [1, 0, 0])] },
  tail: { rotation: [K(0, [0, 0, 0]), K(5, [-12, 0, 0]), K(11, [-16, 0, 0]), K(16, [-6, 0, 0]), K(22, [0, 0, 0])] },
  tail_tip: { rotation: [K(0, [0, 0, 0]), K(5, [-9, 0, 0]), K(11, [-12, 0, 0]), K(16, [-4, 0, 0]), K(22, [0, 0, 0])] },
  // 下摆随上举的手臂逐渐张开
  r_coat_front: { rotation: [K(0, [0, 0, 0]), K(5, [-4, 0, 0]), K(16, [-10, 0, 0]), K(22, [-3, 0, 0])] },
  r_coat_front_tip: { rotation: [K(0, [0, 0, 0]), K(5, [-6, 0, 0]), K(16, [-14, 0, 0]), K(22, [-4, 0, 0])] },
  r_coat_side: { rotation: [K(0, [0, 0, 0]), K(5, [0, 0, -8]), K(16, [0, 0, -14]), K(22, [0, 0, -4])] },
  r_coat_side_tip: { rotation: [K(0, [0, 0, 0]), K(5, [0, 0, -12]), K(16, [0, 0, -20]), K(22, [0, 0, -6])] },
  r_coat_back: { rotation: [K(0, [0, 0, 0]), K(5, [2, 0, 0]), K(16, [-8, 0, 0]), K(22, [-2, 0, 0])] },
  r_coat_back_tip: { rotation: [K(0, [0, 0, 0]), K(5, [3, 0, 0]), K(16, [-11, 0, 0]), K(22, [-3, 0, 0])] }
};

// 受击：0.5s，后仰 + 衣摆前甩
const HURT = {
  pelvis: {
    rotation: [K(0, [0, 0, 0]), K(2, [5, 0, 0]), K(6, [2, 0, 0]), K(10, [0, 0, 0])],
    position: [K(0, [0, 0, 0]), K(2, [0,     -0.5, 0.9]), K(6, [0, 0, 0.5]), K(10, [0, 0, 0])]
  },
  chest: { rotation: [K(0, [0, 0, 0]), K(2, [14, 0, 0]), K(6, [5, 0, 0]), K(10, [0, 0, 0])] },
  head: { rotation: [K(0, [0, 0, 0]), K(2, [20, 0, 0]), K(6, [7, 0, 0]), K(10, [0, 0, 0])] },
  r_ear: { rotation: [K(0, [0, 0, 0]), K(2, [10, 0, -10]), K(10, [0, 0, 0])] },
  l_ear: { rotation: [K(0, [0, 0, 0]), K(2, [10, 0, 10]), K(10, [0, 0, 0])] },
  r_arm: { rotation: [K(0, [0, 0, 0]), K(2, [-24, 0, -14]), K(6, [-8, 0, -5]), K(10, [0, 0, 0])] },
  r_forearm: { rotation: [K(0, [0, 0, 0]), K(2, [-16, 0, 0]), K(10, [0, 0, 0])] },
  l_arm: { rotation: [K(0, [0, 0, 0]), K(2, [-24, 0, 14]), K(6, [-8, 0, 5]), K(10, [0, 0, 0])] },
  l_forearm: { rotation: [K(0, [0, 0, 0]), K(2, [-16, 0, 0]), K(10, [0, 0, 0])] },
  tail: { rotation: [K(0, [0, 0, 0]), K(2, [-16, 0, 0]), K(6, [-5, 0, 0]), K(10, [0, 0, 0])] },
  tail_tip: { rotation: [K(0, [0, 0, 0]), K(2, [-12, 0, 0]), K(10, [0, 0, 0])] },
  r_coat_front: { rotation: [K(0, [0, 0, 0]), K(2, [12, 0, 0]), K(6, [4, 0, 0]), K(10, [0, 0, 0])] },
  r_coat_front_tip: { rotation: [K(0, [0, 0, 0]), K(2, [18, 0, 0]), K(6, [6, 0, 0]), K(10, [0, 0, 0])] },
  r_coat_back: { rotation: [K(0, [0, 0, 0]), K(2, [-6, 0, 0]), K(10, [0, 0, 0])] },
  r_coat_back_tip: { rotation: [K(0, [0, 0, 0]), K(2, [-9, 0, 0]), K(10, [0, 0, 0])] }
};

// 死亡：1.2s，僵直 → 屈膝 → 前扑倒地，最后停在贴地姿态
const DEATH = {
  pelvis: {
    rotation: [K(0, [0, 0, 0]), K(4, [3, 0, 0]), K(11, [-16, 0, 0]), K(19, [-56, 0, 0]), K(24, [-63, 0, 0])],
    position: [K(0, [0, 0, 0]), K(4, [0, 0.4, 0]), K(11, [0, -5, -1.2]), K(19, [0, -13.4, -5.5]), K(24, [0, -14.6, -6.4])]
  },
  chest: { rotation: [K(0, [0, 0, 0]), K(4, [12, 0, 0]), K(11, [-8, 0, 0]), K(19, [-14, 0, 0]), K(24, [-16, 0, 0])] },
  head: {
    rotation: [K(0, [0, 0, 0]), K(4, [16, 0, 0]), K(11, [-16, 0, 0]), K(19, [-24, 0, 0]), K(24, [-27, 0, 0])]
  },
  r_ear: { rotation: [K(0, [0, 0, 0]), K(4, [12, 0, -8]), K(19, [26, 0, -18]), K(24, [30, 0, -20])] },
  l_ear: { rotation: [K(0, [0, 0, 0]), K(4, [12, 0, 8]), K(19, [26, 0, 18]), K(24, [30, 0, 20])] },
  // 双臂：先僵直抬起，再向前摊开
  r_arm: {
    rotation: [K(0, [0, 0, 0]), K(4, [8, 0, -20]), K(11, [26, 0, -16]), K(19, [64, 0, -22]), K(24, [70, 0, -24])]
  },
  r_forearm: { rotation: [K(0, [0, 0, 0]), K(4, [-10, 0, 0]), K(11, [-24, 0, 0]), K(19, [-8, 0, 0]), K(24, [-4, 0, 0])] },
  r_hand: { rotation: [K(0, [0, 0, 0]), K(11, [-12, 0, 0]), K(24, [14, 0, 0])] },
  l_arm: {
    rotation: [K(0, [0, 0, 0]), K(4, [8, 0, 20]), K(11, [26, 0, 16]), K(19, [64, 0, 22]), K(24, [70, 0, 24])]
  },
  l_forearm: { rotation: [K(0, [0, 0, 0]), K(4, [-10, 0, 0]), K(11, [-24, 0, 0]), K(19, [-8, 0, 0]), K(24, [-4, 0, 0])] },
  l_hand: { rotation: [K(0, [0, 0, 0]), K(11, [-12, 0, 0]), K(24, [14, 0, 0])] },
  // 腿：僵直 → 撑不住 → 折叠跪倒
  r_thigh: { rotation: [K(0, [0, 0, 0]), K(4, [-4, 0, 0]), K(11, [26, 0, 0]), K(19, [52, 0, 0]), K(24, [56, 0, 0])] },
  r_shin: { rotation: [K(0, [0, 0, 0]), K(4, [4, 0, 0]), K(11, [-46, 0, 0]), K(19, [-78, 0, 0]), K(24, [-84, 0, 0])] },
  r_foot: { rotation: [K(0, [0, 0, 0]), K(4, [-4, 0, 0]), K(11, [24, 0, 0]), K(19, [40, 0, 0]), K(24, [44, 0, 0])] },
  l_thigh: { rotation: [K(0, [0, 0, 0]), K(4, [-4, 0, 0]), K(11, [30, 0, 0]), K(19, [56, 0, 0]), K(24, [60, 0, 0])] },
  l_shin: { rotation: [K(0, [0, 0, 0]), K(4, [4, 0, 0]), K(11, [-50, 0, 0]), K(19, [-80, 0, 0]), K(24, [-86, 0, 0])] },
  l_foot: { rotation: [K(0, [0, 0, 0]), K(4, [-4, 0, 0]), K(11, [26, 0, 0]), K(19, [42, 0, 0]), K(24, [46, 0, 0])] },
  tail: { rotation: [K(0, [0, 0, 0]), K(4, [-14, 0, 0]), K(11, [-24, 0, 0]), K(19, [-10, 0, 0]), K(24, [-6, 0, 0])] },
  tail_tip: { rotation: [K(0, [0, 0, 0]), K(4, [-10, 0, 0]), K(19, [-6, 0, 0]), K(24, [-4, 0, 0])] },
  // 燕尾：倒地时向上后方摊开
  r_coat_front: { rotation: [K(0, [0, 0, 0]), K(4, [4, 0, 0]), K(11, [-14, 0, 0]), K(19, [-34, 0, 0]), K(24, [-38, 0, 0])] },
  r_coat_front_tip: { rotation: [K(0, [0, 0, 0]), K(4, [6, 0, 0]), K(11, [-18, 0, 0]), K(19, [-42, 0, 0]), K(24, [-46, 0, 0])] },
  r_coat_back: { rotation: [K(0, [0, 0, 0]), K(4, [-4, 0, 0]), K(11, [-20, 0, 0]), K(19, [-40, 0, 0]), K(24, [-44, 0, 0])] },
  r_coat_back_tip: { rotation: [K(0, [0, 0, 0]), K(4, [-6, 0, 0]), K(11, [-26, 0, 0]), K(19, [-50, 0, 0]), K(24, [-54, 0, 0])] },
  r_coat_side: { rotation: [K(0, [0, 0, 0]), K(11, [-10, 0, -6]), K(24, [-30, 0, -14])] },
  r_coat_side_tip: { rotation: [K(0, [0, 0, 0]), K(11, [-14, 0, -9]), K(24, [-38, 0, -20])] }
};

const AUTHORED = {
  attack: { len: 0.8, loop: 'hold_on_last_frame', spec: ATTACK },
  cast: { len: 1.1, loop: 'hold_on_last_frame', spec: CAST },
  hurt: { len: 0.5, loop: 'hold_on_last_frame', spec: HURT },
  death: { len: 1.2, loop: 'hold_on_last_frame', spec: DEATH }
};

// ---------------------------------------------------------------- 组装
const animations = {};
const report = [];

for (const anim of bb.animations ?? []) {
  const suffix = anim.name.split('.').pop();
  const converted = fromBbmodel(anim);
  animations[PREFIX + suffix] = {
    loop: converted.loop,
    animation_length: converted.length,
    bones: converted.bones
  };
  report.push(`${suffix.padEnd(7)} 交付模型 ${String(converted.length).padEnd(5)}s loop=${String(converted.loop).padEnd(20)} 骨骼 ${String(Object.keys(converted.bones).length).padStart(2)} 关键帧 ${converted.keys}`);
}

for (const [name, def] of Object.entries(AUTHORED)) {
  // 镜像规则全在 authoredBones 里：写 r_* 自动补 l_*，显式写的 l_* 以手写为准（不对称站姿用）。
  const bones = authoredBones(def.spec);
  let keys = 0;
  for (const ch of Object.values(bones)) for (const c of Object.values(ch)) keys += c.vector ? 1 : Object.keys(c).length;
  animations[PREFIX + name] = { loop: def.loop, animation_length: def.len, bones };
  report.push(`${name.padEnd(7)} 手写     ${String(def.len).padEnd(5)}s loop=${String(def.loop).padEnd(20)} 骨骼 ${String(Object.keys(bones).length).padStart(2)} 关键帧 ${keys}`);
}

// ---------------------------------------------------------------- 自检
const problems = [];
for (const [name, anim] of Object.entries(animations)) {
  if (!name.startsWith(PREFIX)) problems.push(`${name}: 动画名缺前缀 ${PREFIX}`);
  for (const [bone, channels] of Object.entries(anim.bones)) {
    if (!geoBones.has(bone)) problems.push(`${name}: 骨骼 ${bone} 不在 geo 里（GeckoLib 会静默不动）`);
    for (const [ch, data] of Object.entries(channels)) {
      if (!CHANNEL_XF[ch]) problems.push(`${name}.${bone}: 未知通道 ${ch}`);
      const times = data.vector ? [] : Object.keys(data).map(Number);
      for (const t of times) {
        if (t > anim.animation_length + 1e-6) problems.push(`${name}.${bone}.${ch}: 关键帧 ${t}s 超出时长 ${anim.animation_length}s`);
      }
      if (anim.loop === true && !data.vector) {
        const first = Object.values(data)[0].vector;
        const last = Object.values(data)[data.vector ? 0 : times.length - 1].vector;
        const drift = Math.max(...first.map((v, i) => Math.abs(v - last[i])));
        if (drift > 1e-6) problems.push(`${name}.${bone}.${ch}: loop=true 但首尾不一致（差 ${drift.toFixed(3)}），接缝会跳`);
      }
    }
  }
  const animated = new Set(Object.keys(anim.bones));
  if (animated.size === 0) problems.push(`${name}: 没有动画到任何骨骼`);
}

const out = { format_version: '1.12.0', geckolib_format_version: 2, animations };
writeFileSync(OUT_ANIM, `${JSON.stringify(out)}\n`);

console.log(`源工程: ${SRC_BBMODEL}`);
console.log(report.join('\n'));
console.log(`写出 ${OUT_ANIM.replace(HERE + '\\', '')}`);
if (warnings.length) console.log(`⚠ ${warnings.length} 条提示: ${warnings.slice(0, 5).join(' | ')}`);
if (problems.length) {
  console.log(`✖ ${problems.length} 个问题:`);
  for (const p of problems.slice(0, 20)) console.log(`   ${p}`);
  process.exit(1);
}
console.log('✔ 骨骼名 / 时长 / 循环接缝检查通过');
