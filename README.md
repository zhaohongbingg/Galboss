# GalBoss

> Minecraft Forge 1.20.1 的 Boss 模组：**FS Big Three** —— 冷泉院 桐香 / 糺川 礼 / 女部田 郁子。

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.10 |
| Java | 17 |
| 前置 | [GeckoLib](https://modrinth.com/mod/geckolib) 4.x（必装） |
| 版本 | 1.0.0 |

三名 Boss，各自带着一套技能、对白与阶段转换。附带一座可放置的教学楼结构，顶层教室里放着三个
**待机状态**的 Boss，右键即可发起挑战。

## 三个 Boss

| 角色 | 定位 | 武器 | 血量 | 攻击 | 进入 P2 |
|---|---|---|---|---|---|
| 冷泉院 桐香 | 学生会 · 规则 | 空手 | 400 | 8 | 50% |
| 糺川 礼 | 风纪 · 枪械 | 手枪 | 500 | 12 | 50% |
| 女部田 郁子 | 一番队 · 近战 | 武士刀 | 600 | 15 | 30% |

**冷泉院 桐香** 的核心是「纪律检查」—— 定期宣布一条禁令，在这期间违反就会被记过（详见下节）。
技能：铁拳制裁 / 判决铃 / 禁足令 / 召集 / 驳回 / 全校裁决 / 学生会处分。

**糺川 礼** 以枪械为主：压制射击 / 警告射击 / 狙击指令 / 近距离射击 / 交叉火力 /
肃清（蓄力后瞬移突进）/ 连罚（四连段）。

**女部田 郁子** 以近战为主，带「战斗狂欢」Rage 层数：一番队突击 / 连击 / 战斗狂欢 / 狂暴。
血量低于 1/3 时每次命中吸取 30%。

### FS Guard

桐香「召集」召唤出的护卫：80 血 / 6 攻击，复用桐香的模型与贴图（渲染时缩小）。
**桐香死亡后会自动消散**，不会在战斗结束后残留一堆小怪。

### 黄金碑文（贝阿朵莉切）

贝阿朵莉切第一次被玩家攻击（或右键）时进入「黄金碑文」遭遇：召出 **17 位祭品**
（13 只黑山羊 + 4 位原版宾客），全部一次性苏醒、一起扑上来。碑文按**献祭数量**推进 ——
不认名字、不认顺序、也不认是谁杀的，只看场上倒下了几个。
「十八人」= 17 位祭品 + 你自己。

| 献祭数 | 夜 | 发生什么 |
|---|---|---|
| 6 | 第一夜 · 六名活祭 | 播报，进入第二夜 |
| 8 | 第二夜 · 紧靠的两人 | 播报，进入第三夜 |
| 10 | 第三夜 · 高贵之名 | **剩下的祭品集体狂暴**（力量 I + 速度 I） |
| 11 | 第四夜 · 剜头 | 玩家失明 |
| 12 | 第五夜 · 剜胸 | 举不起盾 |
| 13 | 第六夜 · 剜腹 | 无法进食 |
| 14 | 第七夜 · 剜膝 | 跳不起来 |
| 15 | 第八夜 · 剜足 | 禁足（移速归零） |
| 16 | 第九夜 · 魔女复苏 | 「无人生还」：她开始苏醒（法术冷却减半 + 力量 II / 速度 II），但仍然打不动 |
| 17 | 第九夜 · 魔女复苏 | 最后一位祭品倒下 → 解除全部封印、回满血、狂暴，全体参战玩家被传进**黄金乡** |
| 她倒下 | 第十夜 · 黄金之乡 | 四件宝物落地 |

**为什么改成数量判定**：旧版按「第几夜」分组（13 只羊分批唤醒）、第三夜要玩家在聊天栏打出她的名字。
联机时这套很容易卡住 —— 有人不看聊天栏、有人掉线、两只「必须靠近」的羊被拉扯到地图两头。
现在进度是绝对客观的数字，HUD 与 Boss 血条都直接显示「祭品 x/17」。

祭品数量会被巡检维护：祭品离开她 64 格会被召唤者存活检查清掉，所以每 2 秒检查一次，
场上少了就补召（单次最多 4 只，且只在有玩家在 64 格内时才补），保证碑文一定数得满 17。

#### 负面 buff 是怎么下到玩家身上的

五个部位分成两类实现 —— **能用原版效果表达的就用效果，不能的就拦事件**：

| 部位 | 给玩家的效果 | 实现方式 |
|---|---|---|
| 头 | 失明（`DARKNESS`，屏幕周期性发黑而不是全瞎） | 持续型：`EpitaphHud` 每 10 tick 续一次（时长 60 tick，过期自然消失） |
| 胸 | 举盾无效 | 行为型：`ShieldBlockEvent` 直接取消 = 这一下没挡住 |
| 腹 | 无法进食 | 行为型：`LivingEntityUseItemEvent.Start` 取消（只拦 `isEdible` 的物品） |
| 膝 | 跳不起来 | 行为型：`LivingEvent.LivingJumpEvent` 不可取消，改为把垂直速度按回去 |
| 足 | 禁足（移速 -100%，每 5 tick 清水平速度） | 持续型：本模组的 `CONFINEMENT` 效果 |

要点：

- **持续型** debuff 必须按周期续期，`addEffect` 一次就永久是不安全的 ——
  玩家掉线、跑出战场、战斗结束时都可能留下永久失明。这里统一 60 tick 时长、10 tick 一续。
- **行为型** debuff 原版没有对应效果（没有「禁盾」「禁食」「禁跳」的药水），
  硬塞一个不相干的效果只会让人看不懂，正确做法是拦对应事件。
- 所有封印都先过 `BodySeal.appliesTo(player)`：只对**碑文遭遇半径 64 格内、且她还在世**的玩家生效，
  跑出战场立刻失效。
- 封印挂**玩家**身上、全队共享（同一场遭遇的所有人一起被剜）；第 9 夜复苏时全部解除。

黄金乡（`galboss:golden_land`）是数据包定义的超平坦全金块维度（第 0 层基岩 + 3 层金块），
第九夜传送过去打最终战，胜利后可以留在那里探索；用 `/galboss return` 回主世界出生点。
四件宝物用原版物品承载：金块 ×64 / 不死图腾 / 附魔金苹果 / 贝阿朵莉切的烟斗。

**十八人**：17 位祭品（13 只黑山羊 + 4 位原版宾客：僵尸 / 骷髅 / 尸壳 / 流浪者 / 蜘蛛 /
女巫里随机抽 4 种）+ 你。宾客<b>和黑山羊一样计入献祭数</b>（碑文数的是人，不是羊），
亡灵会戴一顶皮革帽防止白天被晒死。

> 宾客池里**刻意没有苦力怕**：它一旦在羊群里自爆就会替玩家「免费」推进碑文
> （实机验证时确实发生过：一只苦力怕炸死了骷髅宾客，献祭数自己 +1）。

**棋盘竞技场**（`galboss:chess_arena`，66×20×66）已经改成「不可破坏」版本：结构里的
stone / polished_deepslate / 三色混凝土分别换成 `galboss:arena_stone / arena_deepslate /
arena_gray / arena_white / arena_black`。这 5 个方块外观复刻原版贴图，但硬度是 **-1**
（与基岩同级）、爆炸抗性拉满、活塞推不动 —— 放下去就拆不掉（`/setblock` 仍可清）。
它们也作为 BlockItem 出现在「FS Big Three」创造页签里，方便手搭。
旧存档里已经放好的混凝土竞技场需要重新 `/place template galboss:chess_arena` 才会变成保护版。

调试命令（OP 2）：

```
/galboss epitaph [kills]    # 让最近的贝阿朵莉切开碑文；带 kills 时把献祭数设到 0~17
/galboss seal <part|clear>  # 手动挂 / 清一个部位封印（head/chest/belly/knee/foot/clear）
/galboss redtruth <text>    # 在屏幕上打一句「红字」（纯表现，验描边/缩放/淡出用）
/galboss butterflies [n]    # 让最近的贝阿朵莉切放 n 只「黄金蝶」（发射物，验飞行与命中）
/galboss golden             # 传送到黄金乡
/galboss return             # 回主世界出生点
```

### 黄金蝶（技能：齐射 → 追踪 → 命中爆炸）

贝阿朵莉切的远程法术不是「凭空扣血的粒子」，而是一整段**技能**：预警 → 两波齐射 → 飞行追踪 →
命中爆炸。打出去的是真正的发射物（`entity/ButterflyMissileEntity`，注册名 `galboss:butterfly_missile`），
飞行尾迹**和命中爆炸用的是同一只 ER 橙蝶**，所以从出手到砸中读得出是同一招。

技能状态机在 `BeatriceEntity.ButterflyVolleySkill`（自带冷却，约 20 行）：

| 阶段 | 时长 | 表现 |
|---|---|---|
| 预警 | 20 tick | 身前聚起一小撮橙蝶 + 地面紫色光圈逐渐扩大 + 施法者语音；不造成伤害 |
| 第一波 | — | 齐射 3 只（复苏后 5 只） |
| 第二波 | 预警后 12 tick | 再齐射 2 只（复苏后 4 只） |
| 收招 | 30 tick | 冷却起算 12 秒（复苏后 6 秒）；开场/复苏/换阶段后另有 6 秒起手延迟 |

> 为什么不继承 `boss.BossSkill`：那套框架的泛型上界是 `AbstractFSBossEntity`（冷却要吃 `boss.cd()` 倍率），
> 而贝阿朵莉切 `extends Monster`。为一条技能放宽整个框架的上界不划算，所以用结构对齐的状态机。

### 谢斯塔黄金弓狙击术（技能）

碑文前半段的处刑人招式：**祭品少于 10 时才会用**，**每次间隔 1 分钟**（1200 tick）。
祭品数到 10（第六夜之后）这条技能就永久退场，留给后半段的近战/法术；复苏发生在第 17 位，
与「<10」互斥，所以也没有「复苏后冷却减半」。

| 阶段 | 时长 | 表现 |
|---|---|---|
| 锁定 | **40 tick（2 秒）** | 受害者屏幕上出现准星四角括号 + 「被瞄准 x.x s」+ 进度条；世界空间一条红色红外线（关深度测试，**穿过目标再延伸 2.5 格**，旁观者也看得见） |
| 射出 | 2 秒到点 | `SniperArrowEntity` 光箭：1.7 格/tick、追踪、**不撞方块**（无视障碍）；本体不渲染，外观 = 金色拖尾 |
| 命中 | 飞行 0.1~0.3 秒 | 扣 **当前生命的一半**（`indirectMagic`，护甲无效）；命中处溅血：`blood_splash` ×3 + `blood_drop` ×14 |

调试：`/galboss sniper`（最近的贝阿朵莉切对执行者放一次）。

> 实现上踩过的两个坑（都已修，注释里留了痕）：① `ServerLevel.getEntity(UUID)` **查不到玩家**
> （玩家不在实体表），必须用 `PlayerList.getPlayer(uuid)`，否则「锁定了但 2 秒后什么都不发生」；
> ② 命中判定不能用 `distanceTo`（脚对脚），箭在眼睛高度飞，垂直差就 1.5 格 > 阈值，会从人身上穿过去却不判定 ——
> 改成对身体包围盒判定，并把每 tick 位移拆成 8 个子步防隧穿。

| 项 | 值 |
|---|---|
| 形态 | 实体本身不渲染模型（客户端 `NoopRenderer`），外观 = 每 6 tick 一只 `galboss:golden_butterfly` 粒子 + 偶发光点 |
| 追踪 | 朝主人当前目标修正，转向率 0.26、速度 0.62 格/tick（12.4 格/秒）；主人没目标时改追 40 格内最近的玩家 |
| 伤害 | 6 点魔法（`indirectMagic`，主人是她）+ 30 tick 失明 |
| 命中 | 撞墙 / 撞人 / 目标贴身都会结算，命中后一小群橙蝶螺旋炸开 + 紫水晶音；**不会**打她自己和她自己的祭品 |
| 寿命 | 70 tick（3.5 秒）后自然消散，只留一把余晖 |

调试：`/galboss butterflies [count]`（1~12，默认 3）。

> 两个坑都写在代码注释里了：① **贴脸生成会被自己的碰撞箱吃掉**，所以出生点在她身前 1.2 格；
> ② 出手处**不要**放 `GoldenButterflyBurst` 全套（星芒+光环+蝶群），8~10 格距离上那团爆发
> 会把刚飞出来的蝴蝶糊掉 —— 出手只散几只蝶，命中才炸。

### 蝴蝶命中特效（Burst）

命中时在命中点播放：星屑 + 光环（0~2 tick）→ 3~6 只橙蝶螺旋飞出（2~8）→ 盘旋（8~14）→ 淡出（14~20）。
实现在 `client/particle/` 下，**自发光**、不进粒子图集（自定义 RenderType 直接绑贴图）。

2026-11-17 换素材：命中蝶从原来的 `butterfly_burst.png`（2×2 四帧金色蝶、加色混合）换成
**ER 预览表的 `butterfly_orange_flap`**（2 帧 × 32px，普通 alpha）—— 与飞行尾迹同一只，
加色会把这只有描边的实心橙蝶烧成白斑、丢掉蝶形，所以改用 alpha 混合，闪光交给同时发出的星屑。

**螺旋前进**（2026-11-17 加）分两层，缺一层都不像"在前进"：

| 层 | 位置 | 做法 |
|---|---|---|
| 单只蝶 | `AbstractButterflyParticle#tick` | 绕自己的中心转小圈（半径 0.10~0.20 格、角速度 0.30~0.45 rad/tick），中心同时沿随机方向匀速前移 0.020~0.035 格/tick —— 只绕圈像原地打转，只前移是直线，两层叠加才是"边转边飞" |
| 整条尾迹 | `ButterflyMissileEntity#helixOffset` | 每 6 tick 的落点让相位推进 0.9 rad、半径 0.3 格，绕飞行轴排出螺线；约 2.1 秒一条完整螺圈 |
| 命中爆 | `ButterflyBurstParticle` | 沿用原本的螺旋外扩（半径渐大 + 上升），尺寸同步缩半 |

> 为什么"沿轴螺旋"必须由发射物算：`sendParticles` 的速度通道是「随机单位向量 × 速率」，
> 客户端粒子拿不到飞行方向，所以方向信息只能编码在**落点**上。

按设计图核对过一遍，几处数值是按实拍修正的（设计图的字面值在 6 格外读不出效果）：

| 参数 | 设计图 | 实装 | 说明 |
|---|---|---|---|
| 蝴蝶数量 | 3~6 | 3~6 | 一致 |
| 蝴蝶大小 | 0.16~0.22 | **0.16~0.24**（尾巴/飞行蝶 0.22~0.45） | 曾按设计图放大到 0.30~0.45，后按用户要求整体缩半；再嫌小就调回 0.30~0.45 |
| 扩散范围 | 0.6~1.0 格 | **1.15~1.70 格** | 同上 |
| 生命周期 | 16~20 tick | 20 tick | 一致 |
| 滚动速度 | 0.08~0.12 格/tick | 螺旋角速度 `0.15 + 0.006·age` rad/tick | 慢一档才看得出螺旋 |
| 帧动画 | 32×32 ×4 帧 | `Butterflies.ORANGE_BODY`：64×32 = 2 帧 × 32px（与尾迹同一套） | 帧长 3 tick，20 tick 内拍 3 轮 |
| 配色 | `#FFD76A` / `#FFF4C9` / `#B77423` | ER 原画 4 色板（橙 `rgb(244,146,52)` / 暗橙 / 深褐轮廓 / 亮黄高光），**不额外上色** | 再乘色偏就偏离原画了 |
| 螺旋轨迹 | 有 | 每 2 tick 在蝶身后留一颗星屑光点 | 单张蝶贴图做不出轨迹，靠光点补 |
| 蝶身碎屑 | 图里每只蝶都被碎金包住 | 奇数 tick 在翅膀外圈 0.12~0.30 格撒一颗光点（轻微上飘） | 让蝶不是「孤零零一片贴图」 |
| 飞行体碎屑 | — | 每 3 tick 在蝴蝶发射物周围 0.4 格撒一颗光点 | 空中的蝴蝶同样有碎屑跟随 |
| 星芒 / 光环 | 4 角星 + 椭圆环 | 星 0.6~0.95 格 / 6 tick；环 0.22→1.0~1.2 格 / 12 tick | 环原来只到 0.84 格，偏小 |

调参入口：`ButterflyBurstParticle`（命中蝶）、`GoldenStarParticle`（星）、`GoldenRingParticle`（环）、
`GoldenDotParticle`（星屑，已上 `#F8C84E`），数量与节奏在 `fx/GoldenButterflyBurst`；
蝶的图集/帧长在 `client/particle/Butterflies`（换素材只改那里的一行 sprite）。

### 法术系统（`spell/`）——不绑在 boss 身上

这些是**魔法本身**，不是某个 boss 的部件：任何会施法的东西都只调一行
`SpellEngine.cast(new XxxSpell(level, origin, caster, target))`。boss 只是调用者之一，
调试命令也是调用者；以后加法杖 / 法阵 / 会施法的宠物都不用改这套。

| id | 法术 | 预警 | 伤害 | 时长 |
|---|---|---|---|---|
| `lightning_strike` | L1 落雷 | 地面圈 16 tick（外圈固定 + 内圈收缩） | 半径 2.2，9 点 + 8 tick 麻痹 | 22 |
| `chain_lightning` | L2 链雷 | 无（脉冲） | 9 / 6 / 4 三跳，每跳间隔 6 tick，跳跃半径 8 | 22 |
| `storm` | L3 雷云 | 天幕 + 每 30 tick 随机落雷（预警压到 10 tick） | 由它召唤的落雷结算（6 点） | 160 |
| `gungnir` | 奥丁的永恒之枪 | 地面两条平行线（24×3 判定带） | 14 点 + 击退 1.2 | 38 |
| `mjolnir` | 索尔的雷霆之锤 | 内 6 格 / 外 12 格双圈 + 十字 | 内 10 点 + 缓慢 II，外 5 点 | 34 |
| `aegis` | 雅典娜的神盾 | 无（罩在目标身上） | 远程伤害 ×0.25（`#is_projectile` 才吃） | 60 |

要点：

- **预警形状 = 判定形状**：线打线、圈打圈。`gungnir` 侧身一步就能躲，是整套的教学关。
- **同一次施法对同一目标只结算一次**（每个法术自带 `Set<UUID>`），多段特效不会变成多段伤害。
- 目标链 / 落点在**构造时定死**（链雷的链、锤的落点），不追人 —— 这是它们能被躲开的原因。
- 全部复用**已有 pixelfx slug**（零新增美术）：雷柱 `tohka_verdict_smite`、地面冲击
  `tohka_bell_wave`、锤落 `tohka_iron_fist`、贯穿爆点 `rei_crit_hit`、链雷节点 `rei_bullet_impact`、
  天幕 `tohka_phase2_eclipse`、盾 `tohka_confinement_sigil`。等 v1 图集烘好只改 `SpellFx` 里几行字符串。

**屏震**：`S2CCameraShakePacket` → `client/ScreenShake`，在 `ViewportEvent.ComputeCameraAngles` 里用
「37 / 53 Hz 双正弦 × 线性衰减」推相机（随机抖动会晕，正弦更像冲击波）。只动相机角度，不参与判定。

调试：`/galboss spell <strike|chain|storm|gungnir|mjolnir|aegis>`（以自己为目标，打到自己身上）。

### 红字

贝阿朵莉切作为「游戏主宰」的权能：一句话<b>单独</b>压在屏幕正中（`client/gui/RedTruthOverlay`）。

- 服务端只发 `S2CRedTruthPacket`，<b>传的是语言键而不是渲染好的文本</b>——专用服务器没有客户端语言，
  在服务端拼字符串会让中英文客户端一起看到英文。
- 客户端在 `RenderGuiEvent.Post` 里画：对角描边（不透明度封顶 0.72，否则放大后会把中文笔画吃掉）、
  血红色本体、淡入淡出 + 低频闪烁。**同时只显示一行**：新的一句直接替换旧的
  （2026-11-17 改：原先最多 4 行向下堆叠，一次死几个祭品会连推好几条，挤在屏幕上方互相压字）。
- **只有「事实」上红字**，碑文原文一律进聊天栏：

| 触发 | 红字内容 |
|---|---|
| 第一个祭品倒下 | 「右代宫金藏，已死。」→ 2.5 秒后接一句随机「真实」（排队播放，否则会被顶掉） |
| 之后每个祭品倒下 | 三句里随机一句：六轩岛上除这 18 人外没有人类 / 没有任何人出入过这个房间 / 本次事件没有使用任何机关・暗道・遥控装置或毒药 |
| 玩家死亡（碑文进行中、96 格内） | 「<玩家名>，已死。」留 100 tick，重生后仍看得见 |
| 她倒下（第十夜） | 「六轩岛上，不存在任何幸存者。」 |

- 碑文原文（开场两句、第一~十夜、胜利）走 `say(...)` 进聊天栏：开场时是暗红两句 +
  淡紫「那就开始吧」+ 金色「十八位客人，都到齐了」，与屏幕上的红字互不遮挡。

魔法大战那套（神兵再现 / 万千雷枪 / 巴比伦塔坠落）哪些能只靠特效做、哪些必须做成实体或方块，
见 [`docs/MAGIC-FX-FEASIBILITY.md`](docs/MAGIC-FX-FEASIBILITY.md)；闪电原语与神话兵器谱的设计见
[`docs/ARSENAL-AND-LIGHTNING.md`](docs/ARSENAL-AND-LIGHTNING.md)（分「零美术 v0 / 烘 7 张 v1」两档）。

碑文是数量判定的，所以调试参数也是「已经献上几个」而不是「第几夜」：
`/galboss epitaph 11` = 前 10 位都已献上、第 11 位刚倒下（剜头已生效、夜数推进到第五夜）。

## 战斗机制

### 挑战流程

- 结构里的 Boss 处于**待机状态**：不主动攻击、不消失、打不动它，头顶显示「右键挑战 · 角色名」
- 右键发起挑战 → 开场对白 → 3 秒后开战；**潜行右键可跳过对话**
- 开战瞬间会把 **32 格内**还在待机的另外两个一起拉进战斗
- 用刷怪蛋或命令放的 Boss 不带待机标记，行为与普通 Boss 一致

### 纪律检查与记过

桐香在场时会周期性发起「纪律检查」，随机宣布一条禁令并持续 **8 秒**：

> 禁止近战 / 禁止远程 / 禁止跳跃 / 禁止盾牌 / 禁止药水 / 禁止进食

违反规则立刻吃 **6 点**惩罚伤害（同一玩家 1 秒冷却），并累积一条**记过**：

| 规则 | 数值 |
|---|---|
| 记过上限 | 3 条 |
| 每条记过使**礼 / 郁子对你的伤害 +15%** | 最多 +45% |
| 连续 **20 秒**没有新违纪 | 自动消掉一条 |
| 记过满 3 条 | 桐香执行「学生会处分」：禁足 + 额外惩罚 |

屏幕上常驻 HUD 显示当前禁令与剩余时间、以及记过数。桐香的「全校裁决」会对身上带记过的玩家逐个结算。

### 状态效果

| 效果 | 来源 |
|---|---|
| **禁足** | 桐香「禁足令」标记后站住 |
| **流血** | 持续伤害 |

### 血量随参战人数缩放

Boss 的基础血量会按参战玩家数放大：**×1 / ×1.5 / ×2 / ×2.5**。

## 物品

| 物品 | 来源 |
|---|---|
| 学生会徽章 / 风纪臂章 / 战斗勋章 | 桐香 / 礼 / 郁子 的掉落物（各自 1 个，必掉） |
| FS Big Three Core | 三个徽章合成（无序） |
| FS 徽章 | 核心 + 下界合金锭合成（无序） |
| 冷泉院 桐香 / 糺川 礼 / 女部田 郁子 生成蛋 | 创造模式「FS Big Three」页签，同时也挂进了原版「生成蛋」页签 |
| 礼的手枪 / 郁子的武士刀 | 渲染在对应 Boss 手上；创造模式「战斗」页签可取（它们也是实际物品） |

```
桐香 → 学生会徽章 ┐
礼   → 风纪臂章   ├→ FS Big Three Core ──(+ 下界合金锭)──→ FS 徽章
郁子 → 战斗勋章   ┘
```

**FS 徽章**：放在主手或副手生效。血量低于 30% 时获得速度 II / 力量 I / 抗性 I 各 8 秒，冷却 60 秒。

## 教学楼结构

标识符 `galboss:school_building`

- 尺寸 **133 × 35 × 141**，6 层楼
- 每层 **18 间教室**（3 翼 × 6 间）
- 每间教室一个箱子，共 **108 个**（战利品表 `minecraft:chests/end_city_treasure`）
- 顶层中间翼正中间的那一间是 Boss 房：
  `(69,26,63)` 礼 ／ `(69,26,66)` 桐香 ／ `(69,26,69)` 郁子

```
/place template galboss:school_building
```

结构里的 Boss 名字不写死在 nbt 里（否则英文客户端会看到中文），而是由实体按
`message.galboss.challenge_label` 拼成「右键挑战 · 角色名」，开战后自动换成角色本名。

## 配置

`config/galboss-common.toml`

| 键 | 默认 | 作用 |
|---|---|---|
| `boss_defense.nonPlayerDamageMultiplier` | `0.10` | 非玩家来源（环境 / 野怪 / 其它模组的非玩家单位）的伤害倍率 |
| `boss_defense.playerMagicDamageMultiplier` | `0.50` | 玩家魔法 / 法术伤害倍率 |
| `boss_defense.petsCountAsPlayer` | `true` | 玩家的宠物与召唤物是否按「玩家伤害」处理 |
| `damage_cap.perHit` | `100` | 单次伤害上限（乘完所有倍率之后封顶），`≤0` 关闭 |

前三个是为整合包准备的减伤档：原版武器打这三个 Boss 本来正常，整合包里动辄几十倍伤害会直接秒掉。
想彻底回到「原版硬碰硬」，把两个倍率改成 `1.0`、上限改成 `0`。
`/kill` 与虚空伤害不受上限约束（否则 Boss 清不掉）。

魔法伤害的判定含原版 `magic` / `indirect_magic` 以及 `witch_resistant_to` 标签，
整合包可以通过数据包把自家法术伤害类型加进那个标签。

## 构建

```powershell
# 面向开发者的运行环境
gradlew.bat runClient

# 打包
gradlew.bat build          # 产物在 build/libs/
```

`run/` 已加进 `.gitignore`，首次运行时会自动生成（存档、日志、崩溃报告都在里面）。

## 模型与动画

模型、贴图、动画**全部由脚本生成**，完整流程与踩坑记录见 **[`tools/blockbench/README.md`](tools/blockbench/README.md)**。

> ⚠ **不要手改 `assets/galboss/geo/*.geo.json` 与 `assets/galboss/animations/*.animation.json`** ——
> 它们由 `tools/blockbench/pipeline.mjs` 与 `build_all_anims.mjs` 产出，手改会在下次重建时被覆盖。
> 要改就改定义文件，重跑脚本。

要点：

- 骨架 **20 根骨骼**，三条角色共用：`root` / `body` / `head` / 手臂在肘分段 / 腿在膝分段 /
  腕踝 / `weapon_anchor` 武器挂点 / 4 根发型空骨骼占位
- **12 段动画**，基础版 + 按角色覆盖：桐香空手（指认手势）、礼持枪（双手持枪 + 枪托近战）、
  郁子持刀（横斩 / 二连斩 / 举刀）
- 武器走「物品 + `BlockAndItemGeoLayer`」，挂在 `weapon_anchor` 上
- 离线自检（不依赖 Blockbench）：

```powershell
node tools/blockbench/pipeline.mjs --check
```

## 项目结构

```
src/main/java/com/example/galboss/
├── GalBoss.java                    主类
├── boss/                           技能系统
│   ├── BossSkill.java              技能基类
│   ├── SkillScheduler.java         tick 调度
│   ├── BossTelegraph.java          预警圈
│   ├── DisciplineRules.java        纪律检查（禁令轮换与惩罚）
│   ├── DemeritLedger.java          记过账本（上限 / 增伤 / 衰减）
│   └── ConductHud.java             风纪 HUD
├── entity/
│   ├── AbstractFSBossEntity.java   三个 Boss 的公共骨架（动画控制器、阶段、缩放）
│   ├── ReizenTohkaEntity.java      桐香（学生会议题 + 纪律系统）
│   ├── TadasugawaReiEntity.java    礼（枪械）
│   ├── OnabutaIkukoEntity.java     郁子（近战 + Rage + 吸血）
│   ├── BeatriceEntity.java         贝阿朵莉切（黄金碑文：数量判定、17 位祭品、五处封印）
│   ├── BlackGoatServantEntity.java 黑山羊（祭品 / 郁子的召唤物）
│   ├── FsGuardEntity.java          桐香的召唤物
│   └── ModEntities.java
├── ritual/                         黄金碑文
│   ├── SacrificeLedger.java        祭品账本（按标签数死亡，碑文唯一推进入口）
│   ├── BodySeal.java               部位封印状态 + 禁盾 / 禁食 / 禁跳事件钩子
│   ├── EpitaphHud.java             actionbar + 持续型 debuff（失明 / 禁足）续期
│   └── GoldenLand.java             黄金乡维度的查找与传送
├── command/GalBossCommand.java     调试命令（/galboss …）
├── effect/                         Bleeding / Confinement
├── item/                           ModItems / ModCreativeTabs / FsBadgeEvents
├── config/GalBossConfig.java       整合包减伤旋钮
└── client/
    ├── ClientSetup.java
    ├── gui/                        自定义血条样式与覆盖层
    ├── model/                      GeoModel（三个角色 + 护卫）
    └── renderer/                   渲染器 + layer/（手枪 / 武士刀挂在右手）

src/main/resources/
├── assets/galboss/
│   ├── animations/   12 段动画 ×3 角色
│   ├── geo/          骨骼模型
│   ├── lang/         zh_cn / en_us
│   ├── models/item/  物品模型（武器由 geo 转换而来）
│   └── textures/     entity / item / bossbar / mob_effect
└── data/galboss/
    ├── loot_tables/entities/   三个 Boss 的掉落
    ├── recipes/                徽章合成链
    └── structures/             教学楼
```

根目录只放标准 mod project 该有的东西（`build.gradle` / `gradle.properties` / `gradlew.bat` /
`settings.gradle` / `src/` + `.gitignore` / `.gitattributes` / `README.md`）。模型流水线这类
开发工具统一收在 `tools/`：

```
tools/blockbench/                开发工具，不参与打包（gradlew build 出来的 jar 里没有它）
├── pipeline.mjs                 建模 / 动画主流程（make / rig / shot / export）
├── anims.mjs                    关键帧定义 —— 唯一需要手改的文件
├── *.bbmodel                    Blockbench 工程源文件（模型的唯一来源）
├── textures/  ref/              流水线输入：武器色板、参考图
└── README.md                    完整流程与踩坑记录
```

## 已知事项

- **碑文改成了数量判定，旧存档里进行中的碑文不会自动接上**：旧版存的是「第几夜 + 本夜剩余几只」，
  新版存的是「已献上几位（`RitualSacrificed`）」。升级后遇到进行中的碑文，请让它结束或直接
  `/galboss epitaph` 重开一场（会用新逻辑重新召 17 位祭品）。
- **`run/` 不在仓库里**（`.gitignore` 排除）。教学楼结构体已随仓库提供
  （`data/galboss/structures/school_building.nbt`）；要改它得先 `/place template` 放回世界、
  改完再用 `tools/blockbench/export_structure.mjs` 重新导出。
- **郁子武士刀的 `katana` 骨骼在 Blockbench 视口里不显示**（礼的手枪正常）。项目数据、场景图、
  导出的 geo 都验证过是完整正确的，游戏里渲染正常 —— 别为此重新建模。
- `mods.toml` 里声明的是 MIT，但仓库里目前还没有 `LICENSE` 文件。

## 许可

MIT
