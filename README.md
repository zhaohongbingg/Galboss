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
│   ├── FsGuardEntity.java          桐香的召唤物
│   └── ModEntities.java
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

- **`run/` 不在仓库里**（`.gitignore` 排除）。教学楼结构体已随仓库提供
  （`data/galboss/structures/school_building.nbt`）；要改它得先 `/place template` 放回世界、
  改完再用 `tools/blockbench/export_structure.mjs` 重新导出。
- **郁子武士刀的 `katana` 骨骼在 Blockbench 视口里不显示**（礼的手枪正常）。项目数据、场景图、
  导出的 geo 都验证过是完整正确的，游戏里渲染正常 —— 别为此重新建模。
- `mods.toml` 里声明的是 MIT，但仓库里目前还没有 `LICENSE` 文件。

## 许可

MIT
