# 三个生产模组的实测数据与横向对比

做架构决策（共享骨架还是独立文件、控制器怎么分层、特效放哪）前读这份。
所有数字都是把这几个仓库稀疏克隆下来、用脚本扫描全部动画 JSON 得到的，不是估计值。

## 样本

| 样本 | 定位 | 技术栈 | 规模 |
|---|---|---|---|
| **KnY-Multiplayer**（`YeeticusFinch/KimetsunoyaibaTweaks`） | 日式角色 + 呼吸法体系，**动画系统的宿主** | Forge 1.20.1 / 47.4.0，Java 17 | ~900 java / 22 个动画文件 |
| **KnY-Extra-Additions**（`YeeticusFinch/KnY-Extra-Additions`） | 上面那个的二次扩展包 | 同上（依赖宿主 jar） | 55 java / 15 个动画文件 |
| **SLR**（`Efkrdnz/SLR-Minecraft-Mod`） | Solo Leveling 主题的大型 Boss/怪物库 | **Forge 1.20.1-47.2.0 + GeckoLib 4.4.2**，Java 17 | 2551 java / **267 个实体类** / 106 个动画文件 / 107 个 geo |
| （对照）**自建流水线模组** | 3 个 Boss，脚本生成模型与动画 | Forge 47.4.10 + GeckoLib 4.4.2，Java 17 | 3 个动画文件 |

## 一、动画库规模

| 指标 | KnY-Multiplayer | SLR | 自建流水线 |
|---|---|---|---|
| 动画条数 | **183 条挤在 1 个 `biped.animation.json`（456KB）** | 379 条 / 106 个文件（一实体一文件） | 12 条 / 3 个文件 |
| 单文件最多 | 183 条（还有 `kanroji_sword` 单文件 6.2MB） | 12 条 | 12 条 |
| `format_version` | 1.8.0 / 1.12.0 混用 | 全部 1.8.0 | 1.8.0 |
| `loop` 分布 | `true` 47 / `hold_on_last_frame` 58 / 省略 78 | `true` 200 / 省略 171 / `hold` 8 | `true` 6 / 省略 30 |
| 时长 p50 | **0.75s** | **2.0s**（p95 5.3s，max 20s） | 1.0s |
| position 通道使用率 | 92%（168/183） | 75%（285/379） | 100%（36 处） |
| scale 通道使用率 | 偶用 | **26%（99/379）** | 0 |
| Molang | 14 条，**全部是移动循环** | 8 条 / 2 个文件 | 0 |
| 关键帧特效通道 | **0**（22 个文件零命中） | **1**（仅 `elder_beast` 用 `particle_effects` + locator） | 0 |
| 缓动 | 默认 catmullrom | `lerp_mode: catmullrom` 6492 处 + **17 种 `easing` 曲线** | 无（全默认） |

**结论 1：动画的"粒度"取决于玩法。** KnY 是「单次挥砍/单次姿态」型（0.25–0.75s，大量
`hold_on_last_frame` 等 Java 切状态）；SLR 是「整段招式演出」型（2s 起步，起手/蓄力/命中/收招
全在一段里，所以几乎不用 `hold_on_last_frame`）。**先决定你的动画是"单拍"还是"整段演出"，
再决定时长档位与 loop 策略。**

**结论 2：特效不上动画关键帧。** 两个独立团队、跨越两种玩法，都选择在 Java 侧按 tick
播音效与粒子。`sound_effects` / `particle_effects` / `timeline` 一共只被用了 1 次。
理由很实际：跨版本易碎、参数不能动态化、调试要重导动画文件。

**结论 3：缓动要统一。** SLR 堆了 17 种曲线，是"多个作者各写各的"的典型产物。
建议全项目 1~2 种。

## 二、骨架策略：这是最大的分水岭

| | KnY-Multiplayer | SLR | 自建流水线 |
|---|---|---|---|
| 骨骼命名 | 严格统一：`body / head / torso / right_arm / left_arm / right_leg / left_leg`（17 根，只有 7 根参与动画） | **733 个互不相同的骨骼名** | 20 根，统一 |
| 同义异名 | 无 | `head` / `Head` / `h_head`、`right_arm` / `RightArm` / `right-arm` / `rightarm`、`leftleg` / `left_leg` / `leftleglower`、`bone2`~`bone10` 占位名 | 无 |
| 动画复用 | **183 条动画被 7 个以上角色共用**（7 个实体指向同一份 `animations/biped.animation.json`，只换贴图） | 379 条几乎全是一次性消耗品；仅 `portalgate` 被 3 个传送门共用、`beru_lucid` 被 Boss 与 Shadow 共用 | 3 个角色共用 1 份骨架，动画按角色做基础版+专属版 |
| 关节分段 | 无（只有整条手臂/腿） | 部分模型有（`upperbody`、`leftarmlower`、`righthand`…但命名不统一） | 有（肘/膝/腕/踝都分段） |

**结论 4：骨架规范决定动画的复用率。** SLR 的 733 个骨骼名意味着几乎每条动画都只能给一个实体用；
KnY 靠 17 根规范骨骼让 183 条动画服务 7 个以上角色。**"换贴图就是新角色"是这类模组最省的做法，
但前提是骨架从一开始就定死。**

**结论 5：四肢必须分段。** 只有整条手臂时，所有攻击都只能是"整条手臂抡"，
做不出「屈肘蓄力 → 放开命中」这个最关键的战斗节拍。分段是低成本的（只是把立方体切成两半、
UV 各取一半，外观完全不变），收益很大。

**结论 6：`body` 必须带动四肢。** 让腿挂在根骨骼上会导致：① 转 `root` 时腿不动；
② 一旦用 `body.position` 做重心起伏，腰部就会裂开 —— 于是 `position` 通道被彻底放弃，
动画立刻变"木"。实测那个自建项目最初就踩了这个（`hurt` / `phase` 两段动画实际是空动画，
因为 `root` 是空骨骼）。

## 三、控制器写法对比

| | KnY-Multiplayer | SLR | 自建流水线 |
|---|---|---|---|
| controller 数量 | 1 个（`"controller"`） | **2~3 个：`movement` / `attacking` / `procedure`** | 1 个（`"controller"`） |
| transition | 2 tick | **统一 4 tick**（特例 2） | 2 tick |
| 一次性招式 | `triggerableAnim` + `triggerAnim`（官方机制） | **自研同步字符串 + 一个 600+ 行的全局 `LivingTickEvent` if/else 映射表** | `triggerableAnim`（官方机制） |
| 变速 | `SpeedControlledAnimation` 包层 | 在 predicate 里 `setAnimationSpeed()`（全仓仅 1 处） | 无 |
| 上下半身叠加 | 无 | **有**：`movement` 与 `attacking` 同时 CONTINUE，跑动时叠播 `runattack` | 无 |
| 攻击超时兜底 | — | 有（7 tick） | — |

**结论 7：`movement` / `attacking` / `procedure` 三分层是目前见到最清晰的划分。**
好处：上下半身叠加免费；一次性招式与常驻状态互不干扰。transition 统一 4 tick。

**结论 8：不要自建"动画名 → 状态"的映射表。** SLR 放弃了官方 `triggerableAnim`，
改用一个 600+ 行、在全局 `LivingTickEvent` 里做 if/else 的巨型工厂 —— 每加一个动画都要改那个文件。
官方机制的存在就是为了避免这件事。

**结论 9：攻击判定必须有超时兜底。** `getAttackAnim() > 0` 只持续很短，
不配 tick 计数器会让动画卡在攻击态。

**结论 10：不要用线程 sleep 结束动画。** KnY 的 `ClientAnimationHelper` 用
`new Thread(() -> { sleep(50ms × n); layer.setAnimation(null); })` —— 不受游戏 tick 驱动，
暂停/掉帧/卡顿时全乱。用 tick 调度器。

## 四、技能 ↔ 伤害的时序编排

| | KnY-Multiplayer | SLR | 自建流水线 |
|---|---|---|---|
| 调度机制 | **自研 `AbilityScheduler`**（`scheduleOnce` / `scheduleRepeating`） | 实体自维护 `stateTicks` / `cooldown` 计数器 | 自研 `SkillScheduler` |
| 起手播动画 | tick 0 | tick 0 | tick 0 |
| 首次伤害 | tick 0（`AABB.inflate(5.0)` 结算 22 点） | tick 8（`stateTicks >= 8` 结算 12 点） | 动画内固定 tick |
| 持续伤害 | 每 10 tick 重复结算 | tick 8–32 每 tick 结算 5 点 | — |
| 复位 / 清 guard | tick 15 / 18 / 20 | tick 18 | — |
| 时长契约 | `BreathingForm.cooldownSeconds` → 物品冷却 ×20 | Java 常量（`POINT_BLANK_AIM_TICKS = 5` 等） | Java 常量（`CHARGE_TICKS = 16` ↔ 动画 0.8s） |
| 防御窗口 | 动画 = 防御窗口（`GuardStateHelper`） | 无 | 无 |

**结论 11：伤害时机是 Java 侧的 tick 常量，和动画时长是硬耦合的契约。**
三个模组都这样。所以：**同名动画在所有角色上时长必须一致**，改动画时长必须同步改常量
（校验脚本会检查跨文件同名动画的时长一致性）。

**结论 12：连段要做成一整段动画，不要在每次命中触发一次。** 否则动画会不断被打断重播。

## 五、两个"没人做过"的空白

调研这三个模组时专门找了这两样，**三个都没有先例**。不要把它们当成"通行做法"：

1. **动画里没有"命中帧"的显式标记。** 所有模组都是把伤害 tick 硬编码在 Java 里
   （`stateTicks >= 8`、`tick % 10 == 0`）。没有任何一个把命中时机写进动画数据。
2. **没有数据表驱动。** 没有把「动画 + 音效 + 粒子 + 伤害帧」抽成 json/datapack 配置表的先例，
   全是 Java 代码里的常量与方法调用。

如果要做这两件事，属于**超出当前生态实践**的改进，需要自己承担风险 —— 好处是能消除
"改动画要全局搜常量"这个反模式。

## 六、一个脚本化流水线的实战样本

上面第三、四个样本里，有一个模组的模型/动画**不是手工在 Blockbench 里点出来的**，
而是用脚本 + Blockbench MCP 生成的。这套做法对"同一骨架的多个角色"特别划算，
值得作为工程参考：

```
pipeline.mjs          # 定义骨架（20 根骨骼）、立方体与 UV、贴图色板
builder_body.js       # 送进 Blockbench 执行的建模脚本（读 pipeline 注入的 SPEC）
anims.mjs             # 动画定义（基础版 + 按角色的专属覆盖）
make_anims.mjs        # 清空并重建当前项目的动画
verify_anims.mjs      # 渲染每段动画的关键拍点为图，逐帧目视
export_anims.mjs      # 导出为 animation.json
build_all_anims.mjs   # 逐个角色切项目 -> 重建 -> 导出 -> 统一校验
pipeline.mjs --check  # 离线自检（不连 Blockbench）
```

关键工程经验：

1. **模型产物不要手改。** 所有 geo / 动画 JSON 都由脚本生成，手改的改动会在下次重建时被覆盖。
   要改就改定义文件，重跑。
2. **一定要有离线自检。** 脚本渲染一次要连 Blockbench、要几十秒；而"骨骼名拼错 / 立方体重名 /
   UV 越界 / 分段接缝不齐 / 关键帧越界"这些问题**纯静态就能查出来**。把静态检查做在前面，
   能把"改了 → 重跑 → 看图 → 发现不对 → 再改"的循环从几分钟压到一秒。
   本 skill 的 `scripts/check_animation.py` 就是这条思路的产物。
3. **立方体不能重名。** 生成脚本通常按名字查重复用，重名会让后一块**静默覆盖**前一块
   （实测后果：四肢的第二层被第一层吃掉、被 `inflate 0.25` 撑大，导出体积从 37.7KB 掉到 24.4KB）。
   这条一定要进静态检查。
4. **同一骨架的多个角色，用帧号函数书写关键帧时间**（`T(n) = n/24`），
   避免"源码写 0.35、导出变 0.3333"导致源码与产物对不上。
5. **验证"外观没变"要靠数值比对，不要靠肉眼。** 拆分段骨骼后，写脚本确认：
   两段的体积和 = 原体积、四个侧面的 UV 上下相接且总高等于原矩形、其余立方体逐字节不变。
   肉眼在缩略图上根本看不出 6 像素的差异。
6. **导出流程注意"当前活动项目"这个隐式状态。** Blockbench MCP 的
   `create_animation` / `geckolib_export_animations` 都只作用于当前活动项目，
   多角色批处理必须先显式切换项目、跑完再恢复，否则会往错误的项目里写数据。
