# 神话武器 & 闪电：特效设计（v2）

> 这一份只做设计：**没有改代码、没有烘图、没有构建**。
> 上一版 `docs/THUNDER-SPEAR-AND-RELICS.md` 讲的是「千枪」这一招的落地细节；这一份把
> **闪电**独立成一套可复用原语，把**神话武器**做成她的一套「兵器谱」，并给出分档方案。
>
> 所有 slug、贴图、预算都按工程现状写死；标了「新烘」的才需要动美术管线。

## 0. 先说结论

1. **v0 可以零新增美术**：工程里已经烘好 10 个 pixelfx slug（`tohka_*` / `rei_*` / `ikuko_*`），
   换尺寸、换朝向、换触发时机就能把「落雷 / 链雷 / 三件武器」全部演出来。
2. **v1 要烘 7 张**图集，才谈得上「神话感」（枪影、锤影、盾、天幕、雷柱）。
3. 大尺度演出**一律走 pixelfx**（一个包播一整段），粒子只负责贴身细节——这条在上一轮
   「万千雷枪」的预算里已经验证过：粒子是客户端逐颗开销，不是带宽。

---

## 1. 设计原则（三条，冲突时按顺序让）

| # | 原则 | 落到数值 |
|---|---|---|
| 1 | **先预警、后落地** | 预警 ≥ 0.6s；预警形状 = 危险形状（线对线、圈对圈），不许「圈预警、线伤害」 |
| 2 | **大尺度走 pixelfx** | 每次演出实例 ≤ 12（上限 64，一半留给其它技能同时放） |
| 3 | **一击一判定，不叠秒** | 同一 tick 多段命中封顶 16 点；每轮武器总伤 ≤ 30 |

---

## 2. 素材层：两档

### 2.1 已烘好、可直接复用（零美术，v0 就用这些）

| 现有 slug | 原本用途 | 拿来做什么 |
|---|---|---|
| `tohka_bell_wave` | 金色地面冲击波（ground，2.25s / 45 帧） | **落雷的地面冲击**、锤击的环形震波（放大 2~2.5 倍） |
| `tohka_verdict_smite` | 圣光裁击（billboard，2.8s） | **天降神兵的显形光**、雷柱的竖闪 |
| `tohka_phase2_eclipse` | 循环日食（billboard，loop） | **雷云天幕**（scale 放大 25 倍、寿命 120 tick） |
| `tohka_convocation_circle` | 地面法阵（ground，2s） | **武器显形前的召唤阵**（枪/锤出场） |
| `ikuko_quake` | 地面震波（ground，3.1s） | **巨物坠地**的远场震感 |
| `tohka_iron_fist` | 猩红重击（ground，3.2s） | 锤落点的近场重击 |
| `rei_crit_hit` | 暴击爆闪（billboard，0.9s） | **贯穿线上的连续爆点**、破盾闪光 |
| `rei_bullet_impact` | 弹着点（billboard，1.4s） | **链雷的每个节点** |
| `ikuko_parry_flash` | 刀锋闪（billboard，1s） | 盾挡住投射物时的火花 |
| `tohka_confinement_sigil` | 紫色符印（billboard） | 被雷击后的**麻痹标记**（立牌） |

### 2.2 要新烘的（v1，7 张）

| 新 slug | pixelfx 源 | anchor | 帧/尺寸 | 时长 | palette |
|---|---|---|---|---|---|
| `epitaph_lightning_bolt` | `holy/smite` | billboard | 224×176 | 1.4s | 冷白电光 |
| `epitaph_spear_fall` | `ice/icicle-fall` | billboard | 96×384 | 1.0s | 冷白电光 |
| `epitaph_spear_impact` | `explosions/plasma-explosion` | ground | 256×256 | 1.2s | 冷白电光 |
| `epitaph_storm_sky` | `arcane/time-stop`（截断 + loopOverride） | billboard | 512×512 | 2.0s loop | 雷紫 |
| `epitaph_gungnir` | `ice/ice-lance` | billboard | 512×128 | 1.2s | 白金 |
| `epitaph_mjolnir` | `impacts/heavy-landing` | billboard | 256×256 | 0.9s | 雷紫 |
| `epitaph_aegis` | `holy/divine-shield` | billboard | 512×512 | 3.0s | 金 |

> 红线（契约 §3）：`ice/*`、`holy/divine-shield` 这类**带竖直元素**的只能 billboard；
> 压到 ground 会变成「贴地光带」。ground 只给冲击环/裂地这类本来就是地面透视的。
> 全部走 `tools/pixelfx-mc/export-pixelfx.mjs`，`seed` 固定、20fps，烘完把常量加进 `PixelfxSlugs.ALL`。

---

## 3. 闪电：四个原语

闪电不是「一招」，是一套原语。她所有带雷的技能都由这四个拼出来。

### L1 落雷 `Strike`（单点，基础件）

| 段 | tick | 表现 | 代码 |
|---|---|---|---|
| 预警 | 0–16 | 地面画半径 2.2 的圈（`BossTelegraph.circle`，冷白尘） | 每 4 tick 重画一次 |
| 落 | 16 | `PIXELFX` 竖闪（v0 用 `tohka_verdict_smite` scale 1.6；v1 换 `epitaph_lightning_bolt`）+ `ENTITY_LIGHTNING_BOLT_THUNDER` 0.7 音量 + 屏幕震 0.35 | `PixelfxFx.play` |
| 冲击 | 16 | 地面冲击环（v0 `tohka_bell_wave` scale 1.2）+ 灰烬粒子 | `PixelfxFx.play` |
| 判定 | 16 | 半径 2.2 内 9 点魔法伤害 + 8 tick 麻痹（缓慢 III） | 一次性结算 |

- 冷却 80 tick；同一目标 20 tick 内不吃第二次（防止多雷叠死）。
- **反制**：看到圈就走，走 2 格就安全。

### L2 链雷 `Chain`（3 跳）

- 目标选 3 个玩家/召唤物：首个必定是「最近的玩家」，之后每跳选上一个目标 8 格内下一个。
- 表现：节点之间用 `ParticleTypes.ELECTRIC_SPARK` 沿直线插值（每 0.5 格一颗），
  每个节点放一次 `rei_bullet_impact`（scale 0.8）。
- 伤害：9 → 6 → 4（每跳 −33%），跳跃间隔 4 tick（看得到「电顺着连过去」）。
- 成本：3 实例 + 约 40 颗粒子；**反制**：散开——链雷只在 8 格内能跳。

### L3 雷云 `Storm`（天幕 + 周期落雷）

- 天幕：`tohka_phase2_eclipse` 放大到 scale 22、挂在场地上空 40 格、寿命 140 tick（v1 换 `epitaph_storm_sky`）。
- 持续：每 30 tick 在随机玩家脚下 4 格内落一次 L1（预警压缩到 10 tick，伤害降到 6）。
- 视觉补充：天空变暗（`ClientboundGameEventPacket` 天气？不做——直接靠天幕遮）+ 低频持续音。
- 成本：1 天幕 + 每 1.5s 一次落雷（2 实例/次）。
- **反制**：落雷点永远在你 1.5 秒前的位置，跑动即可；雷云期间不要站桩读条。

### L4 屏震 `ScreenShake`（不是特效，但雷必须有）

工程里**没有**屏震。最省的做法（1 个包 + 1 个客户端事件）：

```
服务端：S2CCameraShakePacket(strength, ticks) → 广播 64 格内玩家
客户端：ViewportEvent.ComputeCameraAngles 里
        angles.yaw   += sin(t * 37.0) * strength * decay
        angles.pitch += sin(t * 53.0 + 1.7) * strength * 0.6 * decay
        decay = 1 - age/ticks
```

- strength 建议：小雷 0.35、锤 0.6、塔坠地 1.2（单位度）。
- **不要**做「镜头随机抖动」——高频正弦 + 衰减比随机更像冲击波，也不会让人晕。
- 与 `RenderLevelStageEvent` 无关，纯相机角度，不影响命中判定。

---

## 4. 神话武器：三件套（+ 可选巨物）

### 4.1 Gungnir——奥丁的永恒之枪（贯穿直线）

| 段 | tick | 内容 |
|---|---|---|
| 显形 | 0–24 | 天空出现一支横置枪影（`epitaph_gungnir` / v0 用 `tohka_verdict_smite` 压扁 + 旋转 90°），对准玩家；地面同时用两排尘粒画出 24×3 的**细长预警** |
| 锁定 | 24 | 枪影停住，音效 `TRIDENT_THROW` 变调 |
| 贯穿 | 24–36 | 沿直线每 4 格放一个 `rei_crit_hit`（scale 0.9），6 个实例错峰 2 tick |
| 判定 | 30 | 24×3 的 AABB，14 点 + 击退 1.2；**只一条线** |

- **反制**：侧身一步。这是整套武器里「最讲道理」的一招，用来教玩家「看预警形状躲」。
- 成本：1 枪影 + 6 爆点 = 7 实例。

### 4.2 Mjolnir——索尔的雷霆之锤（预判砸落）

| 段 | tick | 内容 |
|---|---|---|
| 预警 | 0–24 | 地面半径 6 的圈 + 圈内十字线（`BossTelegraph`）；圈心 = **玩家 0.8 秒前的位置** |
| 砸落 | 24 | 锤影从 y+30 砸到地面（`epitaph_mjolnir` / v0 `tohka_iron_fist` scale 2.4）+ 屏震 0.6 |
| 冲击 | 24 | 外环 `tohka_bell_wave` scale 2.2 + 电光向 4 个方向拉 8 格 |
| 判定 | 24 | 内圈 6 格 10 点；外圈 12 格 5 点；内圈命中给缓慢 II 40 tick |

- **反制**：落点固定在预警时定死，**跑动必躲、站桩必吃**。
- 成本：1 锤影 + 1 冲击环 = 2 实例 + 粒子。

### 4.3 Aegis——雅典娜的神盾（防反）

| 段 | tick | 内容 |
|---|---|---|
| 举盾 | 0 | 她面向目标，身前 2 格出现半透明盾影（`epitaph_aegis` / v0 `tohka_convocation_circle` 立起来 + 半透明），持续 60 tick |
| 减伤 | 0–60 | `LivingDamageEvent`：`source.is(IS_PROJECTILE)` → 伤害 ×0.25；弹回原版箭（`ProjectileImpactEvent`，速度取反 ×1.2，**排除黄金蝶**） |
| 破盾 | 任意 | 近战累计 40 点 → 盾碎：`rei_crit_hit` + `ikuko_parry_flash`，她获得虚弱 II 60 tick |
| 结束 | 60 | 盾自动消失，冷却 200 tick |

- **反制**：绕侧后打，或者用近战硬破（破盾有 3 秒虚弱窗口，是输出节奏点）。
- 成本：1 实例。

### 4.4（可选）巴比伦塔——巨物坠落

- 远景剪影（`tohka_phase2_eclipse` 放大 + 灰雾粒子）从天上压下来，落点半径 10 的圈预警 1.2s。
- 落地：`ikuko_quake` scale 3 + 屏震 1.2 + 击退 2.0，伤害只有 8（**它主要是压迫感**）。
- **不做地形破坏**：竞技场方块硬度 -1，砸不烂；用碎块粒子 + 尘土装作砸穿了。
- 成本：1 剪影 + 1 震波；只在血量 <50% 时插入。

---

## 5. 编排：她的一场「魔法大战」

触发：**第 9 夜复苏之后**（`revived == true`）才开始轮换；之前只有碑文 + 蝶。

| 相对 tick | 动作 | 预警 | 伤害 | 反制 |
|---|---|---|---|---|
| 0 | 红字「天空，借我一用。」+ 天幕升起 | — | — | — |
| 40 | 落雷 ×3（不同人/不同位置） | 16 tick 圈 | 各 9 | 走位 |
| 120 | Gungnir | 24 tick 细线 | 14 | 侧身 |
| 200 | 链雷 | 12 tick（脉冲） | 9/6/4 | 散开 |
| 280 | Mjolnir | 24 tick 圈 | 10/5 | 跑动 |
| 360 | Aegis（举盾 60 tick） | 无（她站着不动） | 减伤 75% | 近战破盾 |
| 440 | 循环回 40 | | | |
| 血量 <50% | 每循环插入巴比伦塔 | 1.2s | 8 + 击退 | 远离落点 |
| 血量 <25% | 天幕常驻 + 落雷间隔 20 tick | | | |

**与现有技能共存**：她的 `cast`（单发法术）/ `cast_area`（范围法术）/ 黄金蝶齐射各有冷却，
兵器谱只在「没有在施法」的间隙插进去（`castTicks == 0`），避免动画互相打断。
每轮兵器谱总伤上限按 30 点算，玩家有两轮补血窗口。

---

## 6. 数值与平衡

| 项 | 值 | 理由 |
|---|---|---|
| 单个技能伤害 | 4~14 | 玩家 20 HP，裸装两下不能死 |
| 一轮（440 tick ≈ 22s）总伤上限 | ≤ 30 | 留出吃金苹果/回血的节奏 |
| 预警时长 | 0.6~1.2s | 低于 0.6s 会变成「无法反应」 |
| 同 tick 叠伤封顶 | 16 | 防止多段重叠秒人（千枪那招尤其需要） |
| 多人缩放 | 伤害不缩放，数量缩放 | 人多就多落几个雷点，而不是把单人劈死 |

---

## 7. 实现清单（v1 要动的地方）

**新增**

| 文件 | 职责 |
|---|---|
| `boss/BeatriceArsenal.java` | 兵器谱轮换：冷却表、按 tick 触发、`revived` 才启用 |
| `boss/spell/LightningSpell.java` | L1/L2/L3 三个静态方法（预警 + 播放 + 判定） |
| `boss/spell/GungnirSpell.java` / `MjolnirSpell.java` / `AegisSpell.java` | 三件武器 |
| `network/S2CCameraShakePacket.java` + `client/ScreenShake.java` | 屏震 |
| `client/ScreenShake` 监听 | `ViewportEvent.ComputeCameraAngles` |

**修改**

- `BeatriceEntity.aiStep()`：`castTicks == 0` 时调 `BeatriceArsenal.tick(this)`。
- `PixelfxSlugs.ALL`：加新 slug（未烘之前不注册，`PixelfxFx` 会打 warn 并跳过——**这就是 v0/v1 的开关**）。
- `ModNetwork.register()`：加屏震包；`ClientSetup.onClientSetup()`：挂屏震监听。
- `lang`：招式名与红字台词（8 条 ×2 语言）。

**伪代码（Gungnir，其余同构）**

```java
public static void cast(BeatriceEntity boss, Player target, int age) {
    Vec3 dir = target.position().subtract(boss.position()).normalize();
    Vec3 end = boss.position().add(dir.scale(24));
    if (age < 24) {                                   // 显形 + 预警
        if (age % 4 == 0) lineTelegraph(boss.level(), boss.position(), end);   // BossTelegraph
        return;
    }
    if (age == 24) {                                  // 甩出
        PixelfxFx.play(boss.level(), boss.position().add(0, 3, 0), SPEAR_BILLBOARD, 2.0f, 0f);
        for (int i = 0; i < 6; i++)                   // 沿线爆点
            PixelfxFx.play(boss.level(), boss.position().lerp(end, i / 5.0), HIT_FLASH, 0.9f, 0f);
        ModNetwork.sendScreenShake(boss.level(), boss.position(), 64, 0.6f, 8);
    }
    if (age == 30) aabbDamage(boss, boss.position(), end, 1.5, 14.0f, 1.2f);   // 直线判定
}
```

---

## 8. 风险

| 风险 | 对策 |
|---|---|
| 2D 面片贴脸 | 落点 ≥ 20 格外；近处只放 ground 冲击 |
| 半透明排序（大面片互相切） | 天幕只开 1 个；先画大后画小；`AFTER_PARTICLES` 阶段顺序固定 |
| 实例打爆（64 上限） | 单次演出 ≤ 12；雷云 + 兵器不同时全开 |
| 屏震与光影/其它模组冲突 | 只改相机角度、幅度 ≤ 1.2°，可在配置里关 |
| 竞技场不可破坏 | 不做地形变化，用震波 + 碎块粒子伪装 |
| 多人 | 数量缩放而非伤害缩放；每个玩家身上最多 1 个「被锁定的雷」 |

---

## 9. 里程碑

| 阶段 | 内容 | 美术 | 工作量 |
|---|---|---|---|
| **v0** ✅ 已实装 | 落雷 + 链雷 + 雷云 + 三件武器 + 屏震，全部复用现有 slug；调试命令 `/galboss spell <id>` | 0 张 | `spell/` 8 个类 + 1 个包 + 1 个监听 |
| **v1** | 烘 7 张图集替换 v0 的替身特效（只改 `SpellFx` 里几行字符串） | 7 张 | 中文 |
| **v2** | 命中间对齐、音效分层、红字台词、巴比伦塔 | 0 | 打磨 |

> v0 的实装位置：`spell/Spell.java`（基类）、`SpellEngine`（全局调度）、`SpellFx`（画笔与结算）、
> `LightningStrikeSpell` / `ChainLightningSpell` / `StormSpell` / `GungnirSpell` / `MjolnirSpell` /
> `AegisSpell`；屏震在 `network/S2CCameraShakePacket` + `client/ScreenShake`。
> **它们不认识「贝阿朵莉切」这个类**——boss 以后只需在自己的 AI 里调用。

**v0 就能玩**：没有新图也能先验证「预警 → 落雷 → 判定 → 屏震」这条链路，
手感对了再花钱烘图——这也是我建议的推进顺序。
