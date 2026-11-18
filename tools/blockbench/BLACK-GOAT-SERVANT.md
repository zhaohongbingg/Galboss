# 黑山羊仆从（Black Goat Servant）制作契约 v1

Lead 制定，两个工作流（`goat-model` / `goat-java`）共用。**不要修改本文件**，有异议先找 Lead。

## 0. 需求（用户已确认）

| 项 | 决定 |
|---|---|
| 定位 | **某个 boss 的技能召唤物**，不自然生成（仍然给刷怪蛋，供创造/调试） |
| 头部 | **黑山羊骷髅头**：黑色骨面 + 后弯羊角 + 发光的眼（深渊/黑山羊味） |
| 身体 | **幻魔者（Illusioner）身体**：深色长袍、兜帽领、宽袖、腰带；人形比例 |
| 名称 | 中文 **黑山羊仆从** / 英文 **Black Goat Servant** |
| 交付 | 完整可玩：`.bbmodel` 源工程 + geo + 贴图（+ 可选发光遮罩）+ idle/walk/attack/cast/hurt/death 动画 + 刷怪蛋 + 中英文名 + 战斗数值 + 被 boss 召唤的实际挂钩 |
| 项目 | `D:\game\mc\bossmod`（MC 1.20.1 + Forge 47.4.10 + GeckoLib 4.4.2，official mappings，Gradle 7.6.4，JDK17） |

**本机没有 Blockbench GUI/MCP**，所以建模走仓库既有离线链路：
`make_black_goat.mjs`（生成 .bbmodel，贴图以 base64 内嵌）→ `node import_bbmodel.mjs`（.bbmodel → geo + 贴图，
规则见文件头注释）→ `node pose_tool.mjs`（不启 Blockbench 出姿态 PNG 做视觉验收）。
`.bbmodel` 必须能作为合法 Blockbench bedrock 工程打开（用户以后可能用 Blockbench 手调）。

## 1. ID 与文件名（冻结）

| 用途 | 值 |
|---|---|
| 实体 id | `galboss:black_goat_servant` |
| 实体类 | `com.example.galboss.entity.BlackGoatServantEntity` |
| 渲染器 | `com.example.galboss.client.renderer.BlackGoatServantRenderer` |
| geo | `assets/galboss/geo/black_goat_servant.geo.json`，identifier **`geometry.black_goat_servant`** |
| 贴图 | `assets/galboss/textures/entity/black_goat_servant.png`（**128×128**） |
| 发光遮罩（可选但推荐） | `assets/galboss/textures/entity/black_goat_servant_glowmask.png`（128×128，只画眼睛/角根符文，其余透明） |
| 动画 | `assets/galboss/animations/black_goat_servant.animation.json`，`animation.black_goat_servant.<idle/walk/attack/cast/hurt/death>` |
| bbmodel 源 | `tools/blockbench/black_goat_servant.bbmodel` + 生成脚本 `tools/blockbench/make_black_goat.mjs` |
| 刷怪蛋 | `galboss:black_goat_servant_spawn_egg`（`ForgeSpawnEggItem`） |

## 2. 骨骼契约 v2（bbmodel / Blockbench 坐标；importer 会自动对 X 取反）

**v2 重构（用户要求，2026-11-14）**：v1 是「一个骨骼塞一堆 cube」——`head` 下 19 个 cube、
`body` 下 10 个，结果是下颌不能单独张嘴、耳朵不能抽动、羊角不能整体抖、也没有给特效留挂点。
v2 改成三层语义骨骼。**12 个旧骨骼名一个都没改、层级也没动**，所以现有 6 段动画完全不受影响
（新骨骼不加关键帧就永远是静止姿态）。

**必须严格使用这些骨骼名与层级**（动画 JSON、Java 侧、pose_tool 都按名字查，拼错静默失效）：

```
root                 [0,0,0]        空骨骼，全身总闸
└ body               [0,12,0]       躯干 + 兜帽领（5 cube）；v2 后自身只剩这些
  ├ robe_placket     [0,22.8,-3.2]  胸襟（1，吊在胸口上沿，可摆动）
  ├ belt             [0,13.95,0]    腰带 + 金扣（2）
  ├ shoulder_right   [4.0,22.75,0]  右肩甲（1，轴心在躯干侧壁交线，可耸肩）
  ├ shoulder_left    [-4.0,22.75,0]
  ├ head             [0,24,0]       头部总闸；v2 后自身不带 cube，只有子骨骼
  │ ├ skull          [0,24,0]       颈 + 颅骨（2）
  │ ├ brow_ridge     [0,30.6,-3.2]  眉骨（1，绕 X 挑眉/压眉）
  │ ├ muzzle_base    [0,27.8,-3.45] 吻根 + 鼻梁（2）
  │ │ └ muzzle_tip   [0,27.4,-5.8]  吻尖（1，两段式俯倾吻部）
  │ ├ jaw            [0,25.3,-3.0]  下颌 + 獠牙（3，绕 X 张嘴）
  │ ├ horns_group    [0,28.0,1.85]  双角 6 段（轴心在两角根中点，整体抖动）
  │ └ ears_group     [0,28.5,0.25]  双耳总成（自身不带 cube）
  │   ├ ear_right    [2.95,28.5,0.25] 右耳内+外段（2）
  │   └ ear_left     [-2.95,28.5,0.25] 左耳（2）
  ├ right_arm        [5,22,0]        右肩（1）
  │ └ right_forearm  [5,18,0]        肘/袖口/手（3）
  │   └ right_claw_anchor [6,8,0]    **空骨骼**，黑焰特效/挂件锚点
  ├ left_arm         [-5,22,0]
  │ └ left_forearm   [-5,18,0]
  │   └ left_claw_anchor [-6,8,0]
  ├ right_leg        [2,12,0]        大腿（1）
  │ └ right_shin     [2,6,0]         小腿 + 分趾蹄（3）
  ├ left_leg         [-2,12,0]
  │ └ left_shin      [-2,6,0]
  └ robe             [0,12,0]        长袍下摆（10，走路/施法摆动）
```

27 根骨骼、55 个立方体（数字变了就是有人动过几何）。

补充约定：
- 骨骼 `rotation` 保持 0（动画在动画文件里写；建模阶段不要预烘焙静态旋转）。
- **`right_claw_anchor` / `left_claw_anchor` 是故意留的空骨骼**（无 cube、无子骨骼），只当特效挂点；
  **不要给它们写 rotation** —— 作为渲染层目标骨骼时旋转会被应用两次（武器转 2θ）。
- **下颌张嘴方向（已实测）**：`jaw` 绕 **X 负值张嘴**（`-22°` 时下颌向前下方打开），正值会顶到吻部。
  `muzzle_base` / `muzzle_tip` / `brow_ridge` / `ear_*` 的符号未实测，用前先 `pose_tool.mjs --pose-file` 试。
- 耳朵：要双耳一起动就转 `ears_group`，要单侧抽动（山羊的塑料感就靠这个）就转 `ear_right` / `ear_left`。
- 立方体坐标是**工程空间绝对值**：把一个 cube 拖到另一根骨骼下**不会移动它**，只改它的旋转轴心。
  所以「重挂 cube」是零像素风险操作（v1→v2 已验证：55 个 cube 的 from/to/方向/rotation 逐字节一致；v3 在此基础上精修了头骨、倾角吻部、斜耳与角型与高光贴图）。
- 立方体可以带 `rotation`（羊角必须靠它做出弯曲），pivot 按 Blockbench 规则写。
- 模型总高 **31~32 单位（≈1.95~2.0 格）**，肩宽约 10~11 单位；渲染器不额外缩放（与 geo 1:1）。
- 正面朝 **-Z**（与原版/现有角色一致）。

## 3. 几何要求

- **身体（幻魔者味）**：躯干 8×12×6（x -4..4 / y 12..24 / z -3..3 相对 body pivot）；
  深色长袍：肩部到膝盖的长袍主体 + 下摆（`robe` 骨骼，x -5..5 / y 0..11 / z -4..4，略外扩）；
  兜帽领：颈部一圈 9×2×8 的厚领（善用立方体叠出层次，不要超过 3 层）；
  宽袖：上臂 4×12×4 + 袖口外扩 5×3×5；腰带 9×1.5×7 + 金色扣饰 2×2×1。
- **山羊骷髅头（v3 精修）**：颅骨 6.0×6.0×6.6；**微倾两段细长吻部**（`muzzle_base` -7°、`muzzle_tip` -12°）与前倾眉骨、
  下收下颌；两只耳朵用 `cube rotation` 做出 16~24° 真实斜角下垂与微后掠；眼窝在颅骨前侧（4×2 发光金瞳）。
- **后弯羊角（v3 精修）**：每侧 3 段，从颅骨后上方向后上拔起、中段平掠、角尖微翘，
  段间用立方体 rotation 衔接（角度写在 cube 上，不要写在骨骼上）；角根粗（2.15）、角尖细（0.88），总高收在 31~32 契约内。
- **不能有明显穿模**：静止姿态下手臂不插进躯干、下摆不穿过腿、角不插进颅骨；用 `pose_tool.mjs` 渲染
  front/side/three 三个视角自查。
- 立方体总数控制在 **35~70**（像素风低模，别堆细节），每个立方体名字唯一。

## 4. 贴图契约（128×128，RGBA）

- 必须是 **128×128**，与 `.bbmodel` 的 `resolution` 一致（importer 会校验）。
- 每个立方体的 **6 个面都必须采样到不透明像素**（不能有洞/透明面；`pose_tool` 或自写采样脚本自检）。
- UV 布局自定，但要求：**同一块立方体的 6 个面尽量落在同一色域内**，避免面与面之间出现明显接缝色差；
  羊头贴图的分辨率要够（羊脸五官至少 20×20 像素，眼睛用 2×2 以上高光）。
- 色板（深→亮，可微调但保持这个气质）：

| 部位 | 颜色 |
|---|---|
| 骷髅骨面（黑骨） | `#15151a` / `#1e1e25` / `#2b2b34` / `#3a3a46`（高光） |
| 羊角 | `#241d18` / `#332a22` / `#4a3d31`，角上有 3~4 道亮 1px 环纹 |
| 眼（发光金） | `#ffd257` 核心 + `#fff3b0` 高光 + `#7a5a10` 眼眶阴影 |
| 长袍（深靛） | `#1b1830` / `#272244` / `#352e5c` / `#4a4178`（高光/褶皱） |
| 长袍内衬/兜帽 | `#120f22` / `#1c1834` |
| 腰带/皮革 | `#241a12` / `#33251a` |
| 金属扣/符文 | `#c9a24a` / `#f0d07a` |
- 长袍要有布纹/褶皱的明暗（每 3~4 像素一条明暗过渡），不要大面积纯色。
- 发光遮罩（可选）：只保留眼睛 + 角根符文像素（其余全透明），Java 侧用 GeckoLib `AutoGlowingGeoLayer` 渲染。

## 5. 动画契约（GeckoLib 4.x，rotation 通道是**绝对值**）

| 动画 id | 时长 | loop | 要求 |
|---|---|---|---|
| `idle` | 2.0s | 是 | 呼吸起伏（body/head 微动）、长袍下摆缓慢摆动、羊角/头轻微左右巡视；首尾帧一致 |
| `walk` | 1.0s | 是 | 腿交替前后 20~28°、手臂反相 15~20°、body 上下 0.3 单位、下摆反向轻摆；首尾帧一致 |
| `attack` | 0.8s | 否 | 上前顶撞：body 前倾、头下压前探、右臂/左臂先后甩；**接触帧在 0.4s 左右**（Java 侧按 0.4s 触发伤害） |
| `cast` | 1.1s | 否 | 施咒：双臂上抬张开、头仰起、下摆鼓起，0.6s 处保持；配合失明诅咒 |
| `hurt` | 0.5s | 否 | 快速后仰 + 头侧偏，0.2s 收回 |
| `death` | 1.2s | 否 | 向后倒地（body 绕 X 后仰 ~85°）+ 头垂下，最后一帧静止 |

- 至少动到：body / head / 四肢（含 forearm/shin）/ robe；`root` 只在 death 里用。
- **v2/v3 新增**：`attack`（0.4s 接触帧张嘴 -26°、压眉 -8°、双耳后掠）与 `cast`（0.3~0.9s 张嘴 -22°、挑眉 10°、胸襟前摆）
  已启用次级骨骼；`idle` 已加入 `ear_right` / `ear_left` 错峰抽耳、`robe_placket` 与 `shoulder_right/left` 呼吸微动，
  `walk` 加入 `robe_placket` 与 `ears_group` 轻晃，首尾帧一致性不变。时序与 §5 表格里的接触帧（0.4s）完全对齐，Java 侧不用改。
- 关键帧用**角度绝对值**（如 `head: [0, 12, 0]`），不要用 molang 求偏移；不要在空骨骼上打关键帧。
- 动画文件必须能被 `pose_tool.mjs --anim <file> --name animation.black_goat_servant.walk --t 0.3` 求值。

## 6. Java 契约（goat-java 实现）

### 6.1 实体 `BlackGoatServantEntity`

- `extends Monster implements GeoEntity`，参考 `entity/FsGuardEntity.java` 的写法（它也是"boss 召出来的仆从"，
  有召唤者存活检查；但本实体有**独立模型**，不走缩放复用）。
- 属性：`MAX_HEALTH 45`、`ATTACK_DAMAGE 7`、`MOVEMENT_SPEED 0.30`、`ARMOR 2`、
  `KNOCKBACK_RESISTANCE 0.25`、`FOLLOW_RANGE 32`；`sized(0.62f, 1.95f)`，`setMaxUpStep(1.0f)`。
- 召唤者：字段 `@Nullable LivingEntity summoner`（存档写 `SummonerUUID`）。`setSummoner(LivingEntity)`。
  每 20 tick 检查：召唤者存在且存活且距离 ≤ 64 → 否则 `discard()`；
  **没有召唤者（刷怪蛋/命令放的）不做存活检查**，且不会自动消失（方便调试）。
- AI（目标选择/移动照 FsGuard）：`FloatGoal`、`MeleeAttackGoal(this, 1.0, true)`、
  `NearestAttackableTargetGoal<>(this, Player.class, true)`、`HurtByTargetGoal`、
  `LookAtPlayerGoal`、`RandomLookAroundGoal`、`WaterAvoidingRandomStrollGoal(0.9)`。
- **诅咒技能**：每 160 tick 一次，条件：有目标、距离 ≤ 14、`hasLineOfSight`。
  触发时播放 `cast` 动画 + `SoundEvents.EVOKER_CAST_SPELL`，16 tick 后对目标施加
  `MobEffects.BLINDNESS` 3s + `MobEffects.WEAKNESS` 3s + `damageSources().magic()` 3 点伤害；施法期间不移动。
- 音效：环境 `GOAT_AMBIENT`（低沉时用 `GOAT_SCREAMING_AMBIENT` 也可以）、受击 `GOAT_HURT`、
  死亡 `GOAT_DEATH`、攻击前 `GOAT_PREPARE_RAM`。音量 0.9~1.1。
- 死亡：`death` 动画 + 无掉落或 25% 掉 1 个 `minecraft:goat_horn`（战利品表放
  `data/galboss/loot_tables/entities/black_goat_servant.json`）。
- 动画控制器：照 FsGuard 的 `AnimatableManager` 写法：idle/walk 由 `walkAnimation` 速度切换，
  attack/cast/hurt/death 用 `triggerAnim`（`AnimationController` + `triggerableAnim`）。

### 6.2 注册与资源

- `ModEntities` 增加 `BLACK_GOAT_SERVANT`；`EntityAttributeCreationEvent` 补属性注册（照现有实体）。
- `BlackGoatServantRenderer`：`GeoEntityRenderer<BlackGoatServantEntity>`，
  `RenderType.entityCutoutNoCull(texture)`；有 glowmask 就加 `AutoGlowingGeoLayer`（先确认 GeckoLib 4.4.2 API 存在，
  没有就跳过并说明）。`ClientSetup` 注册渲染器。
- `ModItems` 增加 `ForgeSpawnEggItem(ModEntities.BLACK_GOAT_SERVANT, 0x1b1830, 0xffd257, new Item.Properties())`，
  进 `ModCreativeTabs` 的 GalBoss 页签。
- `lang/zh_cn.json`：`entity.galboss.black_goat_servant = 黑山羊仆从`；
  `lang/en_us.json`：`= Black Goat Servant`。刷怪蛋名走 `item.galboss.black_goat_servant_spawn_egg`（两语言都加）。

### 6.3 召唤挂钩（郁子 / OnabutaIkukoEntity）

- 新增一个计时器字段（例如 `goatSummonTimer`），初始 30s；在 `tickBoss()` 里递减；
  `isBattleActive()` 且计时器到 0 且场上她的黑山羊 < 3 只时：
  以她为中心 2~4 格随机位置召唤 **2 只**（`setSummoner(this)`），
  播 `playAnim("cast")` + `SoundEvents.EVOKER_PREPARE_SUMMON` + `broadcastNearby(<新 lang key>, "§5")`；
  之后计时器重置 40s。
- 新 lang key（两语言都要）：`message.galboss.ikuko.goat_summon`：
  zh `「深渊的黑山羊，替我撕碎他们。」`；en `"Black goats of the abyss, tear them apart."`。
- 不改郁子现有技能节奏/数值；`die()` 里把她的山羊全部 `discard()`（或靠 lifeline 自然清理，二者择一，说明即可）。

## 7. 质量门禁（任何一条不过都不算完成）

1. `node tools/blockbench/import_bbmodel.mjs tools/blockbench/black_goat_servant.bbmodel black_goat_servant.geo.json --texture black_goat_servant.png` 成功，
   geo 里 **27 根骨骼名与 §2 完全一致**、立方体 55 个（35~70 区间内）、identifier 正确、texture_width/height=128；
   再跑 `node tools/blockbench/make_black_goat.mjs --verify`（自带 27 骨骼/几何/贴图/遮罩全项自检，必须 0 FAIL）。
2. `pose_tool.mjs` 渲染 front/side/three + 头顶特写，肉眼确认：山羊骷髅头辨识度（长吻+后弯角+发光眼）、
   幻魔者长袍轮廓、无穿模；`_preview/` 下有图。
3. 贴图 128×128、所有立方体 6 面采样 100% 不透明（自写采样脚本或 pose_tool 的检查）。
4. 动画 6 段可被 pose_tool 求值；idle/walk 首尾一致；attack 在 0.4s 有前顶接触姿态。
5. `$env:JAVA_HOME='C:\Program Files\Java\jdk-17'; .\gradlew.bat build` **exit 0**。
6. 实机（mcdev probe）：`/summon galboss:black_goat_servant` 出现正确模型/贴图/动画，
   `/galboss battle` 后郁子的召唤技能真的召出 2 只，`/give` 刷怪蛋可放置；日志无 exception/贴图缺失。
7. 不破坏现有资产：不改三个 boss 的 geo/贴图/动画/bbmodel（新增文件优先）。
8. 中文注释、无新增第三方依赖。

## 8. 文件所有权

| 工作流 | 可写 |
|---|---|
| `goat-model` | `tools/blockbench/make_black_goat.mjs`、`tools/blockbench/black_goat_servant.bbmodel`、`src/main/resources/assets/galboss/geo/black_goat_servant.geo.json`、`src/main/resources/assets/galboss/textures/entity/black_goat_servant*.png`、`tools/blockbench/_preview/goat_*` |
| `goat-java` | `src/main/java/**`、`assets/galboss/animations/black_goat_servant.animation.json`、`assets/galboss/lang/*.json`、`data/galboss/loot_tables/entities/black_goat_servant.json` |
| `goat-qa` | `run/**`、`tools/black-goat-verify/**` |

- 读文件用 read 工具（PowerShell `Get-Content` 会把 UTF-8 中文显示成乱码，文件本身没问题）。
- 两边都在 `src/main/resources` 下写，但**目录不重叠**；不要动对方目录。
- 遇到阻断：`send_message` 给 `lead` 说明具体错误，不要自行改契约。
