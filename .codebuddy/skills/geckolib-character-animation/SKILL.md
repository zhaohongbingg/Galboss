---
name: geckolib-character-animation
description: GeckoLib 4.x（Minecraft Forge / NeoForge 1.20+）角色动画的编写规范与自检工具。当需要新建或改动实体的 idle/walk/attack 等动画、设计骨骼层级与命名、把动画接到 AnimationController 或 triggerableAnim、给动画编排伤害/音效/粒子时序、或排查「动画不播 / 骨骼转了没反应 / 循环处跳帧 / 武器不跟手」这类问题时使用。含骨骼命名模板、动画 JSON 格式与缓动规范、Molang 程序化循环动画、控制器分层、时长档位、反模式清单，以及可直接运行的动画校验脚本。
---

# GeckoLib 角色动画编写规范

## 用途与核心原则

给 GeckoLib 4.x 的 Minecraft 模组编写/审查角色动画。所有结论来自对三个生产模组的实测
（一个共享骨架的日式角色模组、一个 379 段动画的 Boss 库、一个自建流水线的 Boss 模组），
实测数据与对比见 `references/case-studies.md`。

四条核心原则，先记住这四条再往下看：

1. **先定骨架，再写动画。** 骨架定错，所有动画都得重写。共享骨架能让 N 个角色复用同一批动画，
   无规范骨架会让每条动画都变成一次性消耗品（实测：一个模组出现了 733 个互不相同的骨骼名，
   379 段动画几乎全不可复用）。
2. **循环动作用 Molang 程序化生成，战斗动作用关键帧手写。** 这两类动画的写法完全不同，
   混用会两边都别扭。
3. **特效、音效、伤害时机全部放 Java 侧。** 不要用动画 JSON 的 `sound_effects` / `particle_effects`
   / `timeline` 通道 —— 三个生产模组里两个完全没用、一个只用了 1 处。
4. **动画名是编译期字符串。** 拼错不会报错，只会静默不播。必须用自检脚本兜住。

## 一、资源布局

```
src/main/resources/assets/<modid>/
├── animations/<实体名>.animation.json     # 动画数据
├── geo/<实体名>.geo.json                  # Bedrock 几何体
└── textures/entity/<实体名>.png           # 贴图
```

对应 Java 侧三个类，路径必须一一对上：

```java
// 1) 模型：三个 ResourceLocation
public class FooModel extends GeoModel<FooEntity> {
    public ResourceLocation getModelResource(FooEntity e)     { return rl("geo/foo.geo.json"); }
    public ResourceLocation getTextureResource(FooEntity e)   { return rl("textures/entity/foo.png"); }
    public ResourceLocation getAnimationResource(FooEntity e) { return rl("animations/foo.animation.json"); }
}

// 2) 渲染器
public class FooRenderer extends GeoEntityRenderer<FooEntity> {
    public FooRenderer(EntityRendererProvider.Context ctx) { super(ctx, new FooModel()); }
}

// 3) 实体
public class FooEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) { /* 见第三节 */ }
}
```

**一实体一文件，还是多角色共享一个文件？** 取决于骨架：

| 情况 | 做法 |
|---|---|
| 骨架完全一致、只换贴图 | 共享一个 `animations/biped.animation.json`，多个 `GeoModel` 指向同一文件；新角色只追加差异动画 |
| 骨架各不相同的独立 Boss | 一实体一文件，容器清晰、diff 友好 |
| 需要按角色差异化同一段动画 | 见第四节「基础版 + 角色专属」 |

反面案例：把 183 段动画塞进一个 456KB 的文件 —— 加载慢、diff 地狱、多人协作必冲突。
单文件超过 ~100 段就该拆。

## 二、骨架：命名模板与层级

**全小写下划线，一次定好永不变更。** 参考模板（人形）：

```
root                        空骨骼，全身总闸（pivot 在脚底，用于整体前倾/后仰/受击）
└── body                    骨盆，必须带动四肢
    ├── head
    │   ├── hair_bangs / hair_side_l / hair_side_r / hair_back   （空骨骼，占挂点）
    ├── upper_arm_l ── lower_arm_l ── hand_l
    ├── upper_arm_r ── lower_arm_r ── hand_r
    │                                  └── weapon_anchor          （武器挂点，在下节说明）
    ├── thigh_l ── shin_l ── foot_l
    └── thigh_r ── shin_r ── foot_r
```

四条硬性要求：

1. **四肢必须挂在 `body` 之下，不要挂在 `root` 之下。** 挂错了 `body.position` / `body` 旋转
   只会带动躯干，腿留在原地 —— 表现是扭腰时腰部裂开，于是就不能用 position 通道做重心起伏，
   动画立刻变得很"木"。
2. **关节要分段。** 只有 `upper_arm` 没有 `lower_arm` 时，所有攻击都只能是"整条手臂抡"，
   做不出屈肘蓄力→放开命中这个关键节拍。
3. **空骨骼也是资产。** 头发、裙摆、武器挂点先占好位置（不带立方体，外观不受影响），
   以后要加几何体直接把 cube 的 parent 指过去，动画不用改。
4. **左右方向在编辑空间里是 `+X = 角色右手`。** 前提是模型正面朝 `-Z`（脸的贴图在 `-Z` 侧）。
   这一条决定了后面所有旋转的符号。

**分段骨骼不改变外观的数学**：把一条 12 像素长的肢体拆成上下各 6 像素，只要两段的立方体
首尾相接、四个侧面的 UV 各取原矩形的上下两半、端面两段都给（另一段是关节处的断面，
静止时被互相遮住、弯下去才露出来），**静止姿态与未分段时逐像素一致**。
`references/format-and-api.md` 给了完整的 UV 对半切公式。

## 三、动画 JSON 规范

`format_version` 用 `1.8.0` 或 `1.12.0`，GeckoLib 4.x 生成的都带 `"geckolib_format_version": 2`。

```json
{
  "format_version": "1.12.0",
  "animations": {
    "attack": {
      "loop": "hold_on_last_frame",
      "animation_length": 0.8,
      "bones": {
        "upper_arm_r": {
          "rotation": {
            "0.0":  { "vector": [0, 0, 0] },
            "0.2083": { "vector": [-30, 0, -12] },
            "0.4167": { "vector": [80, 0, 6] },
            "0.7917": { "vector": [0, 0, 0] }
          }
        },
        "lower_arm_r": { "rotation": { "vector": [0, 0, 0] } },
        "body": { "position": { "vector": [0, -1, 0] } }
      }
    }
  }
}
```

要点：

- **时间单位是秒**，关键帧会被导出器吸附到**项目 fps 的整帧**上（fps 是 Blockbench 项目设置，
  实测有 24 和 48 两种；用 24 时网格是 `0.0417 0.0833 0.125 0.1667 0.2083 0.25 …`）。
  写非整帧值会被吸附（`0.35` → `0.3333` = 8/24），**源码与导出产物就对不上了**。
  用帧号换算函数书写可以彻底避免：`const T = n => +(n / 24).toFixed(4);` 然后写 `time: T(6)`。
  不知道项目 fps 时，用校验脚本反查（它会自动探测）。
- **`vector` 有两种用法**：写成 `{"vector": [x,y,z]}` 且不带时间键 = **整段恒定值**（省关键帧）；
  带时间键 = 关键帧。
- **`animation_length` 可以大于最后一个关键帧时间**，多出的部分就是保持。
- **缓动**：GeckoLib 4.x 默认 catmullrom 插值，一般不用写；要显式控制时用
  `{"pre": [...], "post": [...], "lerp_mode": "catmullrom"}` 或
  `"easing": "easeInOutQuad"`。**全部用默认值**或**全部用同一种曲线**，别混 ——
  实测有个模组堆了 17 种曲线，动作看起来很"毛"。
- **动画名 = JSON 里的 key。** 两种约定选一种并贯彻：
  - 裸名（`"attack"`）：简单，但跨模组同名会撞，实测有模组出现
    `idle` / `misc.idle` / `kamish_idle` / `animation.demon_king_baran.idle` 四种写法并存。
  - 带命名空间（`"animation.foo.attack"`）：**推荐**，Java 侧只需拼前缀，不会撞车。
- **禁止**：名字里有空格、纯数字、同义词并存（`death` / `dead` / `despawn` 三选一）。

## 四、循环动画用 Molang，不要手搓关键帧

`idle` / `walk` / `run` / `swim` 这类周期性动画**一个关键帧都不写**，直接用正弦表达式。
`query.anim_time` 单位是秒：

```json
"walk": {
  "loop": true, "animation_length": 2,
  "bones": {
    "upper_arm_r": { "rotation": { "vector": ["Math.sin(query.anim_time * 360) * 45", 0, 0] } },
    "upper_arm_l": { "rotation": { "vector": ["Math.sin(query.anim_time * 360) * -45", 0, 0] } },
    "thigh_r":     { "rotation": { "vector": ["Math.sin(query.anim_time * 360) * -45", 0, 0] } },
    "thigh_l":     { "rotation": { "vector": ["Math.sin(query.anim_time * 360) * 45", 0, 0] } },
    "body": {
      "rotation": { "vector": [0, "Math.sin(query.anim_time * 360) * -4", 0] },
      "position": { "vector": [0, "Math.sin(query.anim_time * 720) * 0.25", 0] }
    }
  }
}
```

- `* 360` = 1 个周期/秒（`360°/s`），`* 720` = 2 个周期/秒。**频率由系数决定，
  `animation_length` 只决定循环多长**，两者互不冲突，也天然无缝。
- 优点：文件极小、不需要凑首尾帧、Java 侧传 `speed` 就能变速
  （GeckoLib 里用 `setAnimationSpeed()` 或包一层 SpeedControlled 动画）。
- **和手写关键帧混用要谨慎**：同一个模组里两种都用是正常的（实测：14/183 条用 Molang，
  全部是移动循环；其余全关键帧），但**同一条动画内**别混。
- Molang 只适合"正弦/余弦能表达"的周期性动作。**战斗动作一律手写关键帧。**

## 五、控制器分层与触发

### 三个控制器的分工（推荐）

```java
@Override
public void registerControllers(AnimatableManager.ControllerRegistrar data) {
    // movement：腿与位移。恒 CONTINUE，永不 STOP
    data.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
    // attacking：普攻。和 movement 同时 CONTINUE → 天然的上下半身叠加
    data.add(new AnimationController<>(this, "attacking", 4, this::attackingPredicate));
    // procedure：一次性招式，用官方 triggerableAnim
    data.add(procedure.receiveTriggeredAnimations());
}
```

- **transition tick 用 4**（实测某个模组全仓统一 4，特例 2）。太小会硬切，太大会糊。
- **`movement` 与 `attacking` 分层是免费的上下半身分离**：跑动时打普攻不再需要单独的
  `run_attack` 动画。

### movementPredicate：先守卫，再分级

```java
private PlayState movementPredicate(AnimationState<FooEntity> state) {
    if (this.isPlayingTriggeredAnimation()) return PlayState.CONTINUE;  // 让位给一次性动作
    if (this.isDeadOrDying()) return state.setAndContinue(DEATH);
    if (this.hurtTime > 0)    return state.setAndContinue(HURT);
    if (state.isMoving())     return state.setAndContinue(WALK);        // Molang 正弦
    return state.setAndContinue(IDLE);
}
```

**守卫必须放第一行**：一次性招式在播时，移动判定如果把动画抢过去，招式就断了。

### attackingPredicate：必须有超时兜底

```java
private PlayState attackingPredicate(AnimationState<FooEntity> state) {
    if (this.getAttackAnim(state.getPartialTick()) > 0.0f && !this.swinging) {
        this.swinging = true;
        return state.setAndContinue(ATTACK);
    }
    if (this.swinging) {
        this.swinging = false;
        return state.setAndContinue(ATTACK);   // 反向挥砍
    }
    this.swinging = false;
    return PlayState.STOP;
}
```

`getAttackAnim() > 0` 只持续很短，**必须配一个 tick 计数器做超时**（实测模组用 7 tick），
否则某些情况下动画会卡在攻击态出不来。

### 一次性招式：用官方 triggerableAnim

```java
private static final List<String> TRIGGERED = List.of("attack", "cast", "phase", "guard");
private static final String CONTROLLER = "controller";

public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    AnimationController<FooEntity> c = new AnimationController<>(this, CONTROLLER, 2, this::predicate);
    for (String name : TRIGGERED) {
        c.triggerableAnim(name, RawAnimation.begin()
                .then(PREFIX + name, Animation.LoopType.PLAY_ONCE));
    }
    controllers.add(c.receiveTriggeredAnimations());
}

/** 只在服务端调用。 */
protected void playAnim(String suffix) {
    if (!this.level().isClientSide) this.triggerAnim(CONTROLLER, suffix);
}
```

- **`triggerAnim` 只在服务端调**，同步交给 GeckoLib。
- **不要自建"动画名 → 状态"的映射表。** 反面案例：有个模组放弃 triggerableAnim，
  改成 `EntityDataAccessor<String>` + 一个 600+ 行的全局 `LivingTickEvent` 里做 if/else 映射 ——
  每加一个动画都要改那个巨型文件。官方机制就是为了避免这个。
- `RawAnimation` 的四种收尾：`.thenLoop(name)` 循环、`.thenPlay(name)` 播一次、
  `.then(name, LoopType.PLAY_ONCE)` 显式、`.thenWait(...)` 等待。

### 时长档位与 loop 类型

| 类型 | 时长 | `loop` |
|---|---|---|
| 单次挥砍 / 短动作 | 0.25 – 0.5s | 省略（播完回默认） |
| 蓄力 / 收招姿态 | 0.5 – 1.0s | `hold_on_last_frame` |
| 一段式 Boss 招式 | 1.5 – 3.0s | 省略 |
| 待机 / 移动 | 2.0 – 4.0s | `true`（Molang 正弦） |

- **`hold_on_last_frame` 是"停在末帧等 Java 切回去"**：攻击、格挡、蓄力用它，
  不要把姿势回收写在动画里再靠代码停帧。
- **显式写 `"loop": false` 的坏处**：GeckoLib 里 `loop: false` 等同于省略（播完即结束），
  写了没坏处但没必要。
- 实测分布（183 段样本）：`true` 47 / `hold_on_last_frame` 58 / 省略 78。

## 六、动画 ↔ 伤害 ↔ 音效 ↔ 粒子：全在 Java 侧按 tick 编排

动画只负责"看起来在做什么"，**判定与表现全部按 tick 在 Java 侧调度**。

```java
public void start(FooEntity boss) {
    boss.playAnim("lateral_slash");                    // tick 0：起手播动画
    MovementHelper.setVelocity(boss, forward);         // tick 0：位移同时启动
    GuardStateHelper.setGuard(boss, 20.0f, formId);    // tick 0：动画 = 防御窗口

    scheduler.scheduleOnce(e -> damageInAABB(e, 5.0, 22.0f), 0);   // tick 0：结算伤害
    scheduler.scheduleOnce(e -> spawnParticles(e), 2);             // tick 2：出粒子
    scheduler.scheduleOnce(e -> cleanup(e), durationTicks);         // tick 15：清 guard 复位
    sound(e, SoundEvents.PLAYER_ATTACK_SWEEP);                      // 音效同样在这里播
}
```

实测的伤害时机参考值：

| 事件 | 参考 tick | 例子 |
|---|---|---|
| 起手播动画 / 首次结算 | 0 | 多个型在第 0 tick 就用 `AABB.inflate(5.0)` 结算 |
| 首次结算（需要前摇读条） | 8 | 有模组用 `stateTicks >= 8` |
| 持续伤害区间 | 8–32 每 tick，或每 10 tick 一次 | 吐息类 |
| 复位 / 清 guard | 15 / 18 / 20 | 各家不同，与动画时长对齐 |

**硬性要求：**

1. **时长是动画与代码之间的契约。** Java 里的 `CHARGE_TICKS = 16` 对应动画 `len: 0.8`；
   改动画时长必须同步改常量。**同一个动画名在所有角色上时长必须一致**，
   否则伤害判定会和动画错位。校验脚本会检查这一条。
2. **同一段动画只播一次，连段做成一整段动画**，不要在每次命中都触发一次
   （否则动画会不断被打断重播）。
3. **不要用线程 sleep 结束动画。** 反面案例：有模组用
   `new Thread(() -> { sleep(50ms × n); layer.setAnimation(null); })` —— 不受游戏 tick 驱动，
   暂停 / 掉帧 / 卡顿时全乱。用 tick 调度器。
4. **音效与粒子只在服务端生成**（`ServerLevel.sendParticles`），按 `tick % N == 0` 节流。

## 七、特效表现层

**斩击轨迹 / 光环这类"一次性特效"用独立的小 geo 模型，不要塞进角色模型。**

```
geo/sword_slash_water.geo.json      # 一个薄片/弧面，约 840 字节
animations/sword_slash.animation.json  # loop: true, animation_length: 0.01 —— 纯占位保活
textures/entity/sword_slash_forest0..9.png  # 序列帧
```

- 动画只需要一个 `loop: true` 的极短占位，**缩放/位移/朝向由 Java 侧调**。
- 播放序列帧用自定义 `Particle` 类或自定义 `RenderType`，贴图按帧编号。
- 每个流派/属性一份 geo（只有贴图不同），共享一个动画文件。

**自发光**用 `RenderType.eyes(LAYER)` + 自定义 `GeoRenderLayer`，不要指望 `AutoGlowingLayer`
（实测模组全是自研层，14 个）。

**挂武器**用官方的 `BlockAndItemGeoLayer`，把武器注册成普通 `Item`，挂在角色模型的
`weapon_anchor` 骨骼上：

```java
public class GunLayer extends BlockAndItemGeoLayer<ReiEntity> {
    private static final String ANCHOR = "weapon_anchor";
    protected ItemStack getStackForBone(GeoBone bone, ReiEntity a) {
        return ANCHOR.equals(bone.getName()) ? new ItemStack(ModItems.REI_GUN.get()) : null;
    }
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack s, ReiEntity a) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
}
```

为什么武器要做成物品而不是独立 GeoModel：位置朝向交给原版 `ItemDisplayContext` 的标准握持变换
（零常量）、不会被 GeckoLib 按骨骼名把角色动画套上去导致自转、有自己的贴图系统。
挂点的三个关键点（`rotation` 必须为 0、`pivot.x` 与手同号、手腕骨骼用来调朝向）
见 `references/format-and-api.md`。

## 八、反模式清单

照着这份清单能省几周：

| # | 反模式 | 后果 |
|---|---|---|
| 1 | 骨骼命名不统一（`head` / `Head` / `h_head`、`bone2`~`bone10`） | 动画无法跨实体复用；实测有个模组 379 段动画几乎全是一次性消耗品 |
| 2 | 四肢挂在根骨骼而不是躯干之下 | 扭腰裂开，`position` 通道作废 |
| 3 | 动画名同义异名 / 带空格 / 纯数字 | 拼错只能运行时才发现 |
| 4 | 单文件塞 100+ 段动画 | 加载慢、diff 地狱、协作冲突 |
| 5 | 用线程 sleep 结束动画 | 不受 tick 驱动 |
| 6 | 自建"动画名 → 状态"的巨型映射表 | 每加一个动画都要改那个文件 |
| 7 | 用动画 JSON 的 `sound_effects` / `particle_effects` / `timeline` | 三个生产模组两个零使用、一个只用 1 处；跨版本易碎、不可调参 |
| 8 | 时长 / 伤害 tick 魔数散落各处 | 改动画要全局搜常量 |
| 9 | 混用 5 种以上缓动曲线 | 动作看起来"毛" |
| 10 | 给武器挂点骨骼（`weapon_anchor`）写 rotation | 它作为渲染层目标骨骼会被应用**两次**旋转，武器转 2θ |
| 11 | 一实体一文件却共享骨架 | 冗余；骨架一致就该共享动画文件 |
| 12 | 关键帧时间写非 1/24 网格值 | 导出吸附后源码与产物对不上，无法比对 |

## 九、自检流程

**每次改完动画，按顺序跑这三步。**

### 第 1 步：机器校验（必做）

```bash
# 基本用法：一个 geo + 一个或多个 animation.json（传多个时会额外校验
# "同名动画在不同文件里时长是否一致"）
python scripts/check_animation.py <geo.json> <animation.json> [更多 json ...]

# --fps N   强制帧网格（默认自动探测）
# --strict  把风格建议也当成错误（CI 里用）
# --all     不折叠重复提示
```

校验内容与它能抓到的问题：

- 动画引用的骨骼名是否存在于 geo（**GeckoLib 里骨骼名拼错是静默失效**，动画照播、那根骨骼就是不动）
- 关键帧时间是否越出 `animation_length`
- `loop: true` 的动画首尾关键帧是否一致（不一致会在接缝处"跳"一下）
- 是否在动画里用了「没有立方体、也没有子骨骼」的死骨头（转了没有任何效果）
- 统计 loop 分布、时长分布、Molang 使用、通道使用，并给出该文件的反模式提示

`--strict` 会把风格建议（非 1/24 网格时间、命名不规范、缓动种类过多）也升级为错误。

### 第 2 步：Blockbench 逐帧目视

用 MCP 或手动，把每段动画在**关键拍点**渲染成图（起手 / 命中 / 保持 / 收招），
按角色分目录存，文件名带角色前缀，避免多角色互相覆盖。

**注意：武器是物品、走渲染层，Blockbench 预览里看不到武器。** 持械姿态只能进游戏验。

### 第 3 步：构建

```
gradlew build
```

资源文件（geo / animation JSON）不参与编译，构建通过只代表打包没问题 —— 所以第 1 步不能省。

## 十、参考文件

- `references/format-and-api.md` —— 动画 JSON 完整字段、Bedrock geo 结构、UV 对半切公式、
  导出时的符号转换、GeckoLib 4.x API 速查、武器挂点与手腕骨骼的正确用法。
  **写具体 JSON / Java 代码前先读这份。**
- `references/case-studies.md` —— 三个生产模组的实测数据与横向对比（骨架策略、动画规模、
  控制器写法、命名体系、时序编排），以及两个"命中帧标记 / 数据表驱动"的空白结论。
  **做架构决策（共享骨架还是独立文件、控制器怎么分层）前先读这份。**
- `scripts/check_animation.py` —— 上面第 1 步的校验脚本，无第三方依赖，纯标准库。
