/**
 * 三名 boss 的动画定义（v2）。
 *
 * 三者骨架完全一致，所以这里只维护一份；由 make_anims.mjs 逐个项目生成，
 * 或由 export_anims.mjs 导出后按角色前缀批量写出。
 *
 * ============================ 坐标与符号约定 ============================
 * 编辑空间（Blockbench 里看到的数值）—— **编辑器视口里看到的姿态就是游戏里的姿态**，
 * 所以下面这些值只按编辑器里的直觉写就行。
 *
 * 导出到文件时会做一层"表示法转换"（已用导出的 json 实测）：
 *   旋转的 X 与 Y 取反、旋转的 Z 保留、position 原样保留。
 * 例：这里写 right_shin -26，文件里是 +26；这里写 body.position [0,0.4,-0.8]，
 *     文件里还是 [0,0.4,-0.8]。这层转换是为了对齐 Bedrock 坐标系，
 *     编辑器与游戏画面一致，不要为了"让文件里的数值看起来对"而改这里的符号。
 *
 *   +Y 向上；-Z 是角色正面（脸的贴图在 -Z 侧）；角色右手在 +X 侧。
 *
 *   下垂的四肢（手臂 / 腿）    +X 旋转 = 向前摆，-X = 向后甩
 *   朝上的部位（root / body / head）  +X 旋转 = 向后仰，-X = 向前倾
 *
 *   由此推出的两条惯性写法：
 *     · 肘：前臂 +X 是"屈肘"（手往身前收）；膝：小腿 -X 才是"屈膝"（脚跟往后收），
 *       写成 +X 会得到反关节的腿。
 *     · 走路的对侧律：右腿向前（+X）时右臂要向后（-X）。
 *   验证方式：`node verify_anims.mjs` 出的侧视图，屏幕右侧就是角色正面
 *   （相机在 +X 看向原点）。历史素材 anim_attack_side.png 里右臂 +80 指向屏幕右侧，
 *   就是这个约定的实测依据。
 *
 * ============================ 与 v1 的差别 ============================
 * v1 的骨架有 3 个结构性缺陷（详见 pipeline.mjs 的 BASE_GROUPS 注释）：
 * root 是空骨骼（转它没反应）、body 不带动腿（转它会腰部裂开）、没有肘和膝。
 * v2 修好之后，这里同步做了三件事：
 *   1. **补上肘 / 膝 / 腕的关键帧**，攻击和走路的姿态才立得住。
 *   2. **body.position 开始参与**（重心起伏、下蹲、后坐）。腿现在挂在 body 下面，
 *      动 position 不会再把腰拉断 —— v1 那句"只写 rotation"的限制已经不成立了。
 *      注意 root.position 依然不要用：那是整体位移，会和 Java 的 setDeltaMovement 打架。
 *   3. **修正 charge / dash 的躯干方向**：v1 里这两段的 body X 符号写反了，
 *      结果是"蓄力往后"变成前倾、"冲刺前倾"变成后仰（已用 _preview 侧视图核实），
 *      和注释里"拉开反差"的意图正好相反。现在 charge 后仰、dash 前倾。
 *
 * 时长(len)与 loop 标志**保持 v1 原值不动** —— Java 侧的 CHARGE_TICKS 等常量按
 * 秒数换算 tick，改长度会让伤害判定和动画错位。
 *
 * 关键帧时间统一用 T(n) 写：n 是 24 帧率下的帧号，正好落在 Blockbench 的导出网格上
 * （导出器会把任意时间吸附到 1/24 的倍数，用 T() 就不会出现"源码写 0.35、导出变
 * 0.3333"这种对不上的情况）。
 */

/** T(n) = 第 n 帧的时间（秒），24 帧率。例如 T(6) === 0.25s。 */
const T = (n) => +(n / 24).toFixed(4);

const r = (x, y, z) => [x, y, z];

export const ANIMS = [
  // ---------------------------------------------------------------- 待机
  // 呼吸 + 极轻微的手臂摆动。body.position.y 抬 0.12 就是"吸气时胸口起来"，
  // 幅度再大就会看出脚离地。
  {
    name: 'idle', loop: true, len: 2,
    bones: {
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(24), rotation: r(0, 5, 0), position: r(0, -0.12, 0) },
        { time: T(48), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(24), rotation: r(5, 0, 0) },
        { time: T(48), rotation: r(0, 0, 0) }
      ],
      right_arm: [{ time: T(24), rotation: r(0, 0, 2) }],
      left_arm: [{ time: T(24), rotation: r(0, 0, -2) }]
    }
  },

  // ---------------------------------------------------------------- 走路
  // 五帧步态：0 左脚触地 / 6 过渡 / 12 右脚触地 / 18 过渡 / 24 回到起点。
  // 触地时两腿夹角最大、重心最低；过渡时支撑腿伸直、重心最高（body.position.y 抬起来）。
  // 膝只在腿向后甩的时候弯（负 X），腿向前时几乎伸直 —— 反了就是"反关节"。
  // 参考实现里有更省事的路子：把这两条腿换成 Molang 正弦（"Math.sin(query.anim_time*360)*22.5"），
  // 零关键帧且天然无缝；本流水线的 create_animation 是否能透传表达式未经验证，暂不冒险。
  {
    name: 'walk', loop: true, len: 1,
    bones: {
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(6), rotation: r(0, -4, 0), position: r(0, 0.35, 0) },
        { time: T(12), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(18), rotation: r(0, 4, 0), position: r(0, 0.35, 0) },
        { time: T(24), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(22.5, 0, 0) },
        { time: T(6), rotation: r(0, 0, 0) },
        { time: T(12), rotation: r(-22.5, 0, 0) },
        { time: T(18), rotation: r(0, 0, 0) },
        { time: T(24), rotation: r(22.5, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(-22.5, 0, 0) },
        { time: T(6), rotation: r(0, 0, 0) },
        { time: T(12), rotation: r(22.5, 0, 0) },
        { time: T(18), rotation: r(0, 0, 0) },
        { time: T(24), rotation: r(-22.5, 0, 0) }
      ],
      right_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(6), rotation: r(-6, 0, 0) },
        { time: T(12), rotation: r(-26, 0, 0) },
        { time: T(18), rotation: r(-40, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_shin: [
        { time: T(0), rotation: r(-26, 0, 0) },
        { time: T(6), rotation: r(-40, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) },
        { time: T(18), rotation: r(-6, 0, 0) },
        { time: T(24), rotation: r(-26, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(-22.5, 0, 0) },
        { time: T(6), rotation: r(0, 0, 0) },
        { time: T(12), rotation: r(22.5, 0, 0) },
        { time: T(18), rotation: r(0, 0, 0) },
        { time: T(24), rotation: r(-22.5, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(22.5, 0, 0) },
        { time: T(6), rotation: r(0, 0, 0) },
        { time: T(12), rotation: r(-22.5, 0, 0) },
        { time: T(18), rotation: r(0, 0, 0) },
        { time: T(24), rotation: r(22.5, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(16, 0, 0) },
        { time: T(12), rotation: r(6, 0, 0) },
        { time: T(24), rotation: r(16, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(6, 0, 0) },
        { time: T(12), rotation: r(16, 0, 0) },
        { time: T(24), rotation: r(6, 0, 0) }
      ]
    }
  },

  // ---------------------------------------------------------------- 受击
  // root 在 v1 里是空骨骼，这段动画当年其实什么都没做。现在 root 真的在脚底，
  // 所以改成"整体向后仰 + 后撤半步"：挨打是往被打的方向退，不是往前扑。
  // 时长没变，Java 侧靠它做过场。
  {
    name: 'hurt', loop: false, len: 0.5,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(10, 0, 0) },
        { time: T(7), rotation: r(12, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(2), rotation: r(6, 0, 0), position: r(0, -0.3, 0.6) },
        { time: T(7), rotation: r(8, 0, 0), position: r(0, -0.3, 0.6) },
        { time: T(12), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(8, 0, 0) },
        { time: T(7), rotation: r(10, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-14, 0, -10) },
        { time: T(7), rotation: r(-16, 0, -12) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-14, 0, 10) },
        { time: T(7), rotation: r(-16, 0, 12) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      right_forearm: [{ time: T(2), rotation: r(20, 0, 0) }, { time: T(12), rotation: r(0, 0, 0) }],
      left_forearm: [{ time: T(2), rotation: r(20, 0, 0) }, { time: T(12), rotation: r(0, 0, 0) }],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(8, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-6, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) }
      ],
      right_shin: [{ time: T(2), rotation: r(-10, 0, 0) }, { time: T(12), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(2), rotation: r(-14, 0, 0) }, { time: T(12), rotation: r(0, 0, 0) }]
    }
  },

  // ------------------------------------------------------------ 近战挥击
  // 五个阶段：T(0) 起手 → T(5) 抬肘向后蓄力 → T(10) 挥出（肘几乎伸直）→ T(15) 收势 → T(19) 归位。
  // 肘的节拍是这段动画的关键：蓄力时弯得多（40°），命中瞬间要放开（5°），
  // 否则手臂像根棍子抡。root 也跟着前后各偏一点，重心才跟着拳头走。
  {
    name: 'attack', loop: false, len: 0.8,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(4, 0, 0) },
        { time: T(10), rotation: r(-6, 0, 0) },
        { time: T(15), rotation: r(-3, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(5), rotation: r(0, 10, 0), position: r(0, 0, 0.4) },
        { time: T(10), rotation: r(0, -10, 0), position: r(0, -0.2, -0.6) },
        { time: T(15), rotation: r(0, -3, 0), position: r(0, 0, -0.2) },
        { time: T(19), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(10), rotation: r(6, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(-30, 0, -12) },
        { time: T(10), rotation: r(80, 0, 6) },
        { time: T(15), rotation: r(30, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(40, 0, 0) },
        { time: T(10), rotation: r(5, 0, 0) },
        { time: T(15), rotation: r(18, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(10), rotation: r(-12, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(22, 0, 0) },
        { time: T(10), rotation: r(10, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(-10, 0, 0) },
        { time: T(10), rotation: r(12, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(8, 0, 0) },
        { time: T(10), rotation: r(-10, 0, 0) },
        { time: T(19), rotation: r(0, 0, 0) }
      ],
      right_shin: [{ time: T(5), rotation: r(-8, 0, 0) }, { time: T(19), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(5), rotation: r(-14, 0, 0) }, { time: T(19), rotation: r(0, 0, 0) }]
    }
  },

  // ------------------------------------------------------------ 举枪瞄准
  // 礼的枪械技能共用。T(3) 起举到位 → T(6) 后坐力（肘收回、重心后坐）→ T(12) 回瞄 → T(16) 放下。
  // （末尾这一帧原本落在 0.7083s，比 len 0.7s 还长、会被截断，所以压到 T(16)。）
  // 躯干是"前压"（-X）= 抵住后坐，头再抬起来保持准星，这是标准射击姿势。
  // 双肘留 10~18° 弯曲，枪才像被"握着"而不是插在手上。
  {
    name: 'shoot', loop: false, len: 0.7,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(6), rotation: r(3, 0, 0) },
        { time: T(12), rotation: r(0, 0, 0) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(3), rotation: r(-7, 0, 0), position: r(0, -0.4, 0) },
        { time: T(6), rotation: r(-13, 0, 0), position: r(0, -0.4, 0.4) },
        { time: T(12), rotation: r(-7, 0, 0), position: r(0, -0.4, 0) },
        { time: T(16), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(5, 0, 0) },
        { time: T(6), rotation: r(9, 0, 0) },
        { time: T(12), rotation: r(5, 0, 0) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(86, 0, -8) },
        { time: T(6), rotation: r(74, 0, -8) },
        { time: T(12), rotation: r(86, 0, -8) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(10, 0, 0) },
        { time: T(6), rotation: r(20, 0, 0) },
        { time: T(12), rotation: r(10, 0, 0) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(80, 0, 10) },
        { time: T(6), rotation: r(70, 0, 10) },
        { time: T(12), rotation: r(80, 0, 10) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(18, 0, 0) },
        { time: T(6), rotation: r(26, 0, 0) },
        { time: T(12), rotation: r(18, 0, 0) },
        { time: T(16), rotation: r(0, 0, 0) }
      ],
      right_leg: [{ time: T(3), rotation: r(6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
      left_leg: [{ time: T(3), rotation: r(-6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
      right_shin: [{ time: T(3), rotation: r(-8, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(3), rotation: r(-8, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }]
    }
  },

  // ---------------------------------------------------------------- 蓄力
  // 礼「肃清」/ 郁子「突击」的前摇。时长 0.8s = 代码里的 CHARGE_TICKS 16。
  // 姿态是"后仰 + 屈膝下蹲"：躯干 +X（往后）、重心沉下去 1.2 像素、双膝弯 12°。
  // 末尾 T(19) 直接甩向"前倾起跳"的姿态，接上 dash 不会有长长的混合过渡。
  //
  // ⚠ v1 这里写的是 body -14（会往前倾），和"前摇往后倾"的意图相反 —— 已按实测侧视图改正。
  {
    name: 'charge', loop: false, len: 0.8,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(5, 0, 0) },
        { time: T(16), rotation: r(5, 0, 0) },
        { time: T(19), rotation: r(-4, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(5), rotation: r(14, 0, 0), position: r(0, -1.2, 0.5) },
        { time: T(16), rotation: r(14, 0, 0), position: r(0, -1.2, 0.5) },
        { time: T(19), rotation: r(-10, 0, 0), position: r(0, 0.4, -0.4) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(10, 0, 0) },
        { time: T(16), rotation: r(10, 0, 0) },
        { time: T(19), rotation: r(-6, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(38, 0, -16) },
        { time: T(16), rotation: r(38, 0, -16) },
        { time: T(19), rotation: r(-12, 0, -4) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(30, 0, 0) },
        { time: T(16), rotation: r(30, 0, 0) },
        { time: T(19), rotation: r(8, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(38, 0, 16) },
        { time: T(16), rotation: r(38, 0, 16) },
        { time: T(19), rotation: r(-12, 0, 4) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(30, 0, 0) },
        { time: T(16), rotation: r(30, 0, 0) },
        { time: T(19), rotation: r(8, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(-14, 0, 0) },
        { time: T(16), rotation: r(-14, 0, 0) },
        { time: T(19), rotation: r(14, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(14, 0, 0) },
        { time: T(16), rotation: r(14, 0, 0) },
        { time: T(19), rotation: r(-14, 0, 0) }
      ],
      right_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(-12, 0, 0) },
        { time: T(16), rotation: r(-12, 0, 0) },
        { time: T(19), rotation: r(-4, 0, 0) }
      ],
      left_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(5), rotation: r(-12, 0, 0) },
        { time: T(16), rotation: r(-12, 0, 0) },
        { time: T(19), rotation: r(-4, 0, 0) }
      ]
    }
  },

  // ---------------------------------------------------------------- 冲刺
  // 只在位移开始的那一 tick 触发，演的就是"冲过去"这段：T(2) 前倾到位（位移同时启动）、
  // T(9)/T(15) 完成一个跨步、T(16) 收势、T(24) 归位。位移最长 18 tick(0.9s)，不会超出这段。
  // 前倾拆成 root -10 + body -20（总 30°），只压 body 会让腰和腿脱节。
  //
  // ⚠ v1 这里写的是 body +30（会往后仰），和"前倾到位"的意图相反 —— 已按实测侧视图改正。
  //   头部同步改成 +14：身体前倾时头要抬回来保持看向前方。
  {
    name: 'dash', loop: false, len: 1.0,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-10, 0, 0) },
        { time: T(16), rotation: r(-10, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(2), rotation: r(-20, 0, 0), position: r(0, 0.4, -0.8) },
        { time: T(16), rotation: r(-22, 0, 0), position: r(0, 0, -0.8) },
        { time: T(24), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(14, 0, 0) },
        { time: T(16), rotation: r(14, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-58, 0, -10) },
        { time: T(16), rotation: r(-58, 0, -10) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(30, 0, 0) },
        { time: T(16), rotation: r(30, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-58, 0, 10) },
        { time: T(16), rotation: r(-58, 0, 10) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(30, 0, 0) },
        { time: T(16), rotation: r(30, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(34, 0, 0) },
        { time: T(9), rotation: r(0, 0, 0) },
        { time: T(15), rotation: r(-22, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-34, 0, 0) },
        { time: T(9), rotation: r(0, 0, 0) },
        { time: T(15), rotation: r(22, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-8, 0, 0) },
        { time: T(9), rotation: r(-34, 0, 0) },
        { time: T(15), rotation: r(-10, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(2), rotation: r(-34, 0, 0) },
        { time: T(9), rotation: r(-8, 0, 0) },
        { time: T(15), rotation: r(-10, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ]
    }
  },

  // ---------------------------------------------------------------- 连罚
  // 礼的四连段：右手 T(3) 蓄力 → T(7) 挥出 → T(12) 再蓄 → T(16) 再挥，
  // 左手紧接着在 T(11) → T(15) → T(20) → T(24) 补上两下。
  // 躯干跟着交替扭（body 的 Y 轴），双脚前后换重心；肘每一下都是"蓄力弯 38°、命中放开到 6°"。
  {
    name: 'combo', loop: false, len: 1.2,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(10), rotation: r(-4, 0, 0) },
        { time: T(20), rotation: r(-4, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(7), rotation: r(0, -8, 0), position: r(0, 0, 0.3) },
        { time: T(12), rotation: r(0, 8, 0), position: r(0, -0.2, -0.4) },
        { time: T(16), rotation: r(0, -8, 0), position: r(0, 0, 0.3) },
        { time: T(20), rotation: r(0, 8, 0), position: r(0, -0.2, -0.4) },
        { time: T(28), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(10), rotation: r(4, 0, 0) },
        { time: T(20), rotation: r(4, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(-28, 0, -12) },
        { time: T(7), rotation: r(82, 0, 6) },
        { time: T(12), rotation: r(-28, 0, -12) },
        { time: T(16), rotation: r(82, 0, 6) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(3), rotation: r(38, 0, 0) },
        { time: T(7), rotation: r(6, 0, 0) },
        { time: T(12), rotation: r(38, 0, 0) },
        { time: T(16), rotation: r(6, 0, 0) },
        { time: T(24), rotation: r(16, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(-28, 0, 12) },
        { time: T(15), rotation: r(82, 0, -6) },
        { time: T(20), rotation: r(-28, 0, 12) },
        { time: T(24), rotation: r(82, 0, -6) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(38, 0, 0) },
        { time: T(15), rotation: r(6, 0, 0) },
        { time: T(20), rotation: r(38, 0, 0) },
        { time: T(24), rotation: r(6, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(10, 0, 0) },
        { time: T(16), rotation: r(-8, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-8, 0, 0) },
        { time: T(16), rotation: r(10, 0, 0) },
        { time: T(28), rotation: r(0, 0, 0) }
      ],
      right_shin: [{ time: T(7), rotation: r(-10, 0, 0) }, { time: T(28), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(7), rotation: r(-10, 0, 0) }, { time: T(28), rotation: r(0, 0, 0) }]
    }
  },

  // ------------------------------------------------------------ 施法 / 召唤
  // 桐香「纪律检查」「召集」、郁子「战斗狂欢」。双臂上举 150°（正值是向前举，写成负值
  // 会把双臂甩到身后），同时整体浮起来一点：body.position.y 抬 0.8、双膝伸直。
  {
    name: 'cast', loop: false, len: 1.0,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(-3, 0, 0) },
        { time: T(19), rotation: r(-3, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(8), rotation: r(-8, 0, 0), position: r(0, 0.8, 0) },
        { time: T(19), rotation: r(-8, 0, 0), position: r(0, 0.8, 0) },
        { time: T(24), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(-14, 0, 0) },
        { time: T(19), rotation: r(-14, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(150, 0, 12) },
        { time: T(19), rotation: r(150, 0, 12) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(18, 0, 0) },
        { time: T(19), rotation: r(18, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(150, 0, -12) },
        { time: T(19), rotation: r(150, 0, -12) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(8), rotation: r(18, 0, 0) },
        { time: T(19), rotation: r(18, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_leg: [{ time: T(8), rotation: r(4, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
      left_leg: [{ time: T(8), rotation: r(-4, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
      right_shin: [{ time: T(8), rotation: r(-2, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(8), rotation: r(-2, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }]
    }
  },

  // ------------------------------------------------------------ 驳回 / 格挡
  // 桐香：T(4) 先微微后让（蓄势）→ T(9) 单臂前伸制止 + 身体前压 → 停住 1 拍 → T(14) 收。
  // 只用前伸的那一侧手臂，比双臂交叉更容易读。
  {
    name: 'guard', loop: false, len: 0.6,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(3, 0, 0) },
        { time: T(9), rotation: r(-4, 0, 0) },
        { time: T(12), rotation: r(-4, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(4), rotation: r(8, 0, 0), position: r(0, 0, 0.5) },
        { time: T(9), rotation: r(-10, 0, 0), position: r(0, -0.2, -0.6) },
        { time: T(12), rotation: r(-10, 0, 0), position: r(0, -0.2, -0.6) },
        { time: T(14), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(4, 0, 0) },
        { time: T(9), rotation: r(-6, 0, 0) },
        { time: T(12), rotation: r(-6, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(-28, 0, 0) },
        { time: T(9), rotation: r(92, 0, 0) },
        { time: T(12), rotation: r(92, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(30, 0, 0) },
        { time: T(9), rotation: r(12, 0, 0) },
        { time: T(12), rotation: r(12, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(14, 0, 0) },
        { time: T(9), rotation: r(-34, 0, 0) },
        { time: T(12), rotation: r(-34, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      left_forearm: [{ time: T(4), rotation: r(20, 0, 0) }, { time: T(9), rotation: r(34, 0, 0) }, { time: T(14), rotation: r(0, 0, 0) }],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(-10, 0, 0) },
        { time: T(9), rotation: r(12, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(4), rotation: r(6, 0, 0) },
        { time: T(9), rotation: r(-8, 0, 0) },
        { time: T(14), rotation: r(0, 0, 0) }
      ],
      right_shin: [{ time: T(4), rotation: r(-16, 0, 0) }, { time: T(9), rotation: r(-6, 0, 0) }, { time: T(14), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(4), rotation: r(-16, 0, 0) }, { time: T(9), rotation: r(-6, 0, 0) }, { time: T(14), rotation: r(0, 0, 0) }]
    }
  },

  // ------------------------------------------------------------ 咆哮 / 狂暴
  // 郁子 Rage 满层，也可给 P2 用。姿态：上身向前压（-X）、低头、双拳向后张开（-X + Z 外扩），
  // 重心沉 1.5 像素、双膝弯 18°。v1 没有屈膝，看着像"站着不动只是抬手"。
  {
    name: 'rage', loop: false, len: 1.0,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-5, 0, 0) },
        { time: T(18), rotation: r(-5, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(7), rotation: r(-14, 0, 0), position: r(0, -1.5, 0) },
        { time: T(12), rotation: r(-14, 0, 0), position: r(0, -1.2, 0) },
        { time: T(18), rotation: r(-14, 0, 0), position: r(0, -1.5, 0) },
        { time: T(24), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-30, 0, 0) },
        { time: T(12), rotation: r(-33, 0, 0) },
        { time: T(18), rotation: r(-30, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-40, 0, 45) },
        { time: T(18), rotation: r(-40, 0, 45) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(35, 0, 0) },
        { time: T(18), rotation: r(35, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-40, 0, -45) },
        { time: T(18), rotation: r(-40, 0, -45) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(35, 0, 0) },
        { time: T(18), rotation: r(35, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(0, 0, 8) },
        { time: T(18), rotation: r(0, 0, 8) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_leg: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(0, 0, -8) },
        { time: T(18), rotation: r(0, 0, -8) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      right_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-18, 0, 0) },
        { time: T(18), rotation: r(-18, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ],
      left_shin: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(7), rotation: r(-18, 0, 0) },
        { time: T(18), rotation: r(-18, 0, 0) },
        { time: T(24), rotation: r(0, 0, 0) }
      ]
    }
  },

  // ------------------------------------------------------------ 进入 P2 爆发
  // 时长 1.4s，是这套里最长的一段，用来盖过阶段切换的演出。
  // root 与 body 一起前压，整体浮起 1.5 像素，双臂高举 155°。v1 里 root 的 -10 是空转的，
  // 现在它真的会把整个人绕脚底往前压。
  {
    name: 'phase', loop: false, len: 1.4,
    bones: {
      root: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(-10, 0, 0) },
        { time: T(24), rotation: r(-10, 0, 0) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      body: [
        { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
        { time: T(11), rotation: r(-12, 0, 0), position: r(0, 1.5, 0) },
        { time: T(24), rotation: r(-12, 0, 0), position: r(0, 1.5, 0) },
        { time: T(33), rotation: r(0, 0, 0), position: r(0, 0, 0) }
      ],
      head: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(-24, 0, 0) },
        { time: T(24), rotation: r(-24, 0, 0) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      right_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(155, 0, 28) },
        { time: T(24), rotation: r(155, 0, 28) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      right_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(20, 0, 0) },
        { time: T(24), rotation: r(20, 0, 0) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      left_arm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(155, 0, -28) },
        { time: T(24), rotation: r(155, 0, -28) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      left_forearm: [
        { time: T(0), rotation: r(0, 0, 0) },
        { time: T(11), rotation: r(20, 0, 0) },
        { time: T(24), rotation: r(20, 0, 0) },
        { time: T(33), rotation: r(0, 0, 0) }
      ],
      right_leg: [{ time: T(11), rotation: r(6, 0, 0) }, { time: T(33), rotation: r(0, 0, 0) }],
      left_leg: [{ time: T(11), rotation: r(-6, 0, 0) }, { time: T(33), rotation: r(0, 0, 0) }],
      right_shin: [{ time: T(11), rotation: r(-4, 0, 0) }, { time: T(33), rotation: r(0, 0, 0) }],
      left_shin: [{ time: T(11), rotation: r(-4, 0, 0) }, { time: T(33), rotation: r(0, 0, 0) }]
    }
  }
];

/** 每段动画的时长，供下面的角色专属版本引用 —— 同名动画三人必须一致，见文件头约束。 */
const ANIM_LEN = Object.fromEntries(ANIMS.map((a) => [a.name, a.len]));

/**
 * 角色专属动画。
 *
 * 三人共用一套骨架，但**手里的东西不一样**：桐香空手、礼持枪、郁子持刀。
 * 基础的 `attack` / `combo` / `cast` / `shoot` 是按空手写的，直接套到持械角色上
 * 会出现"握着手枪打空手连击""举着刀双手结印"。这里按角色覆盖掉那几段。
 *
 * 规则：
 *   - key 是**项目名**（= pipeline.mjs 里 CHARACTERS 的 key = 实体动画名前缀）。
 *   - 只需要写"要覆盖的动画"；没写的沿用 ANIMS 的基础版（桐香基本就是基础版）。
 *   - `len` 必须引用 ANIM_LEN.<名字>，不要写字面量：Java 侧按动画名算 tick
 *     （CHARGE_TICKS=16 对应 len 0.8），三人时长不一致会让伤害判定和动画错位。
 *     pipeline.mjs --check 会强制校验这一点。
 *
 * 关于 right_hand / left_hand：这两个是**空的腕骨骼**（不带动网格，只带动
 * weapon_anchor 上的武器）。它们的旋转只会被应用一次（weapon_anchor 才是
 * BlockAndItemGeoLayer 的目标骨骼、会被重复应用，所以它必须保持 0），
 * 因此可以拿来做"转腕"：小角度（≤25°）用来调刀身/枪口朝向，视觉上就是手腕的动作。
 * 角度别开太大 —— 手部网格并不会跟着转，超过 30° 就会像武器在手心里自转。
 */
export const CHARACTER_ANIMS = {
  // ------------------------------------------------------------------ 桐香（空手）
  // 她的 shoot 不是"开枪"，而是「禁足令」：抬手指认目标、宣布处分。
  // 基础版是举枪瞄准的姿势，空着手做很难看，所以换成单臂指认。
  reizein_tohka: {
    shoot: {
      name: 'shoot', loop: false, len: ANIM_LEN.shoot,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-3, 0, 0) },
          { time: T(12), rotation: r(-3, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(3), rotation: r(-6, 0, 0), position: r(0, -0.2, -0.3) },
          { time: T(6), rotation: r(-9, 0, 0), position: r(0, -0.2, -0.5) },
          { time: T(12), rotation: r(-9, 0, 0), position: r(0, -0.2, -0.5) },
          { time: T(16), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-8, 0, 0) },
          { time: T(6), rotation: r(-11, 0, 0) },
          { time: T(12), rotation: r(-11, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        // 右臂前伸指认，肘绷直
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(95, 0, 0) },
          { time: T(6), rotation: r(100, 0, 0) },
          { time: T(12), rotation: r(100, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(4, 0, 0) },
          { time: T(12), rotation: r(0, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        // 手腕下压 = 指尖朝下的"指认"手势
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-10, 0, 0) },
          { time: T(6), rotation: r(-16, 0, 0) },
          { time: T(12), rotation: r(-16, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        // 左臂背到身后
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-14, 0, 0) },
          { time: T(12), rotation: r(-14, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(28, 0, 0) },
          { time: T(12), rotation: r(28, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        right_leg: [{ time: T(3), rotation: r(6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        left_leg: [{ time: T(3), rotation: r(-6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        right_shin: [{ time: T(3), rotation: r(-6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        left_shin: [{ time: T(3), rotation: r(-6, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }]
      }
    }
  },

  // ------------------------------------------------------------------ 礼（手枪）
  tadasugawa_rei: {
    // 双手持枪：右臂抬到 78°、左手托在下面（左肘弯得更多），双肘留 8~30°。
    // 后坐力表现为肘回收 + 腕部把枪口往上抬（right_hand 的 -X）。
    // 手臂的 Z（右手 -8 / 左手 +10）是基础版就验证过的"双手往中间收"的值，别乱改符号。
    shoot: {
      name: 'shoot', loop: false, len: ANIM_LEN.shoot,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(6), rotation: r(3, 0, 0) },
          { time: T(12), rotation: r(0, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(3), rotation: r(-5, 0, 0), position: r(0, -0.5, 0) },
          { time: T(6), rotation: r(-11, 0, 0), position: r(0, -0.5, 0.5) },
          { time: T(12), rotation: r(-5, 0, 0), position: r(0, -0.5, 0) },
          { time: T(16), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(7, 0, 0) },
          { time: T(6), rotation: r(11, 0, 0) },
          { time: T(12), rotation: r(7, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(78, 0, -8) },
          { time: T(6), rotation: r(66, 0, -8) },
          { time: T(12), rotation: r(78, 0, -8) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(8, 0, 0) },
          { time: T(6), rotation: r(18, 0, 0) },
          { time: T(12), rotation: r(8, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(0, 0, 0) },
          { time: T(6), rotation: r(-12, 0, 0) },
          { time: T(12), rotation: r(0, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(70, 0, 10) },
          { time: T(6), rotation: r(60, 0, 10) },
          { time: T(12), rotation: r(70, 0, 10) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(30, 0, 0) },
          { time: T(6), rotation: r(38, 0, 0) },
          { time: T(12), rotation: r(30, 0, 0) },
          { time: T(16), rotation: r(0, 0, 0) }
        ],
        // 注：没有 left_hand —— 左手边上什么也没挂（只有 right_hand 下面有 weapon_anchor），
        // 转左手是**空操作**，--check 会直接把这种键报出来。
        right_leg: [{ time: T(3), rotation: r(8, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        left_leg: [{ time: T(3), rotation: r(-8, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        right_shin: [{ time: T(3), rotation: r(-10, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }],
        left_shin: [{ time: T(3), rotation: r(-10, 0, 0) }, { time: T(16), rotation: r(0, 0, 0) }]
      }
    },

    // 连罚：握着手枪就不该打空手连击，改成**枪托下砸**。
    // 手臂摆幅比基础版大一截（-50° 抬到身后 → +95° 砸下），腕部 -18° 让枪托朝下。
    combo: {
      name: 'combo', loop: false, len: ANIM_LEN.combo,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-5, 0, 0) },
          { time: T(16), rotation: r(-5, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(3), rotation: r(4, 0, 0), position: r(0, 0, 0.3) },
          { time: T(7), rotation: r(-10, 0, 0), position: r(0, -0.3, -0.5) },
          { time: T(12), rotation: r(4, 0, 0), position: r(0, 0, 0.3) },
          { time: T(16), rotation: r(-10, 0, 0), position: r(0, -0.3, -0.5) },
          { time: T(28), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(5, 0, 0) },
          { time: T(16), rotation: r(5, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-50, 0, -6) },
          { time: T(7), rotation: r(95, 0, 4) },
          { time: T(12), rotation: r(-50, 0, -6) },
          { time: T(16), rotation: r(95, 0, 4) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(55, 0, 0) },
          { time: T(7), rotation: r(8, 0, 0) },
          { time: T(12), rotation: r(55, 0, 0) },
          { time: T(16), rotation: r(8, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-18, 0, 0) },
          { time: T(7), rotation: r(6, 0, 0) },
          { time: T(12), rotation: r(-18, 0, 0) },
          { time: T(16), rotation: r(6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(-20, 0, 6) },
          { time: T(11), rotation: r(30, 0, 6) },
          { time: T(20), rotation: r(0, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(35, 0, 0) },
          { time: T(11), rotation: r(20, 0, 0) },
          { time: T(20), rotation: r(0, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-6, 0, 0) },
          { time: T(7), rotation: r(14, 0, 0) },
          { time: T(16), rotation: r(-6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(6, 0, 0) },
          { time: T(7), rotation: r(-12, 0, 0) },
          { time: T(16), rotation: r(6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_shin: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-12, 0, 0) },
          { time: T(16), rotation: r(-12, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_shin: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-12, 0, 0) },
          { time: T(16), rotation: r(-12, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ]
      }
    }
  },

  // ------------------------------------------------------------------ 郁子（武士刀）
  onabuta_ikuko: {
    // 横斩：比基础版多两样东西 —— 躯干拧得更狠（Y ±12/-14）、腕部把刀身摆平再收回。
    attack: {
      name: 'attack', loop: false, len: ANIM_LEN.attack,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(4, 0, 0) },
          { time: T(10), rotation: r(-7, 0, 0) },
          { time: T(15), rotation: r(-3, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(5), rotation: r(0, 12, 0), position: r(0, 0, 0.4) },
          { time: T(10), rotation: r(0, -14, 0), position: r(0, -0.2, -0.6) },
          { time: T(15), rotation: r(0, -4, 0), position: r(0, 0, -0.2) },
          { time: T(19), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(3, 0, 0) },
          { time: T(10), rotation: r(7, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(-38, 0, -14) },
          { time: T(10), rotation: r(78, 0, 10) },
          { time: T(15), rotation: r(28, 0, 2) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(48, 0, 0) },
          { time: T(10), rotation: r(8, 0, 0) },
          { time: T(15), rotation: r(20, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        // 转腕：斩出去的时候把刀身摆平（-24° → +14°）
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(-24, 0, 0) },
          { time: T(10), rotation: r(14, 0, 0) },
          { time: T(15), rotation: r(4, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(16, 0, 0) },
          { time: T(10), rotation: r(-18, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(30, 0, 0) },
          { time: T(10), rotation: r(44, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        right_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(-12, 0, 0) },
          { time: T(10), rotation: r(14, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        left_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(10, 0, 0) },
          { time: T(10), rotation: r(-12, 0, 0) },
          { time: T(19), rotation: r(0, 0, 0) }
        ],
        right_shin: [{ time: T(5), rotation: r(-10, 0, 0) }, { time: T(10), rotation: r(-4, 0, 0) }, { time: T(19), rotation: r(0, 0, 0) }],
        left_shin: [{ time: T(5), rotation: r(-18, 0, 0) }, { time: T(10), rotation: r(-8, 0, 0) }, { time: T(19), rotation: r(0, 0, 0) }]
      }
    },

    // 连罚：两记刀斩（上段劈 + 返身斩），躯干左右拧着把刀甩出去。
    combo: {
      name: 'combo', loop: false, len: ANIM_LEN.combo,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-5, 0, 0) },
          { time: T(16), rotation: r(-6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(3), rotation: r(5, 0, 0), position: r(0, 0, 0.3) },
          { time: T(7), rotation: r(-12, 0, 0), position: r(0, -0.3, -0.5) },
          { time: T(12), rotation: r(4, -8, 0), position: r(0, 0, 0.2) },
          { time: T(16), rotation: r(-12, 6, 0), position: r(0, -0.3, -0.5) },
          { time: T(28), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(6, 0, 0) },
          { time: T(16), rotation: r(6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-45, 0, -10) },
          { time: T(7), rotation: r(92, 0, 8) },
          { time: T(12), rotation: r(-18, 0, -4) },
          { time: T(16), rotation: r(86, 0, 10) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(52, 0, 0) },
          { time: T(7), rotation: r(8, 0, 0) },
          { time: T(12), rotation: r(38, 0, 0) },
          { time: T(16), rotation: r(6, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-26, 0, 0) },
          { time: T(7), rotation: r(12, 0, 0) },
          { time: T(12), rotation: r(-14, 0, 0) },
          { time: T(16), rotation: r(10, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(18, 0, 0) },
          { time: T(11), rotation: r(-22, 0, 0) },
          { time: T(20), rotation: r(-10, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(5), rotation: r(26, 0, 0) },
          { time: T(11), rotation: r(40, 0, 0) },
          { time: T(20), rotation: r(20, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(-10, 0, 0) },
          { time: T(7), rotation: r(16, 0, 0) },
          { time: T(16), rotation: r(-8, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_leg: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(3), rotation: r(10, 0, 0) },
          { time: T(7), rotation: r(-14, 0, 0) },
          { time: T(16), rotation: r(8, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        right_shin: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-14, 0, 0) },
          { time: T(16), rotation: r(-14, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ],
        left_shin: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(7), rotation: r(-14, 0, 0) },
          { time: T(16), rotation: r(-14, 0, 0) },
          { time: T(28), rotation: r(0, 0, 0) }
        ]
      }
    },

    // 战斗狂欢：基础版是空手双臂上举结印，持刀角色改成**举刀 + 左臂横展**。
    cast: {
      name: 'cast', loop: false, len: ANIM_LEN.cast,
      bones: {
        root: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(-5, 0, 0) },
          { time: T(19), rotation: r(-5, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        body: [
          { time: T(0), rotation: r(0, 0, 0), position: r(0, 0, 0) },
          { time: T(8), rotation: r(-10, 0, 0), position: r(0, 1.2, 0) },
          { time: T(19), rotation: r(-10, 0, 0), position: r(0, 1.2, 0) },
          { time: T(24), rotation: r(0, 0, 0), position: r(0, 0, 0) }
        ],
        head: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(-16, 0, 0) },
          { time: T(19), rotation: r(-16, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        // 右臂把刀举过头顶
        right_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(155, 0, 0) },
          { time: T(19), rotation: r(155, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        right_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(14, 0, 0) },
          { time: T(19), rotation: r(14, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        right_hand: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(-12, 0, 0) },
          { time: T(19), rotation: r(-12, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        // 左臂横在体侧张开
        left_arm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(42, 0, 34) },
          { time: T(19), rotation: r(42, 0, 34) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        left_forearm: [
          { time: T(0), rotation: r(0, 0, 0) },
          { time: T(8), rotation: r(40, 0, 0) },
          { time: T(19), rotation: r(40, 0, 0) },
          { time: T(24), rotation: r(0, 0, 0) }
        ],
        right_leg: [{ time: T(8), rotation: r(6, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
        left_leg: [{ time: T(8), rotation: r(-6, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
        right_shin: [{ time: T(8), rotation: r(-3, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }],
        left_shin: [{ time: T(8), rotation: r(-3, 0, 0) }, { time: T(24), rotation: r(0, 0, 0) }]
      }
    }
  }
};

/** 某个角色的完整动画集：基础版 + 该角色的专属覆盖。 */
export function animsFor(characterId) {
  const overrides = CHARACTER_ANIMS[characterId] || {};
  return ANIMS.map((a) => overrides[a.name] || a);
}

/** 某个角色有专属版本的动画名（供 --check 与文档用）。 */
export function overriddenNames(characterId) {
  return Object.keys(CHARACTER_ANIMS[characterId] || {});
}

export const CHARACTER_IDS = Object.keys(CHARACTER_ANIMS);

/** 动画时长（秒）→ 播放时占用的 tick 数。三人一致（--check 强制）。 */
export function animTicks(name) {
  const a = ANIMS.find((x) => x.name === name) || Object.values(CHARACTER_ANIMS).map((m) => m[name]).find(Boolean);
  return a ? Math.ceil(a.len * 20) : 20;
}
