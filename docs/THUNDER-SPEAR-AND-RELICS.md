# 全屏雷枪 & 神话武器：特效设计

> 设计前提全部来自工程现状：特效走 `PixelfxFx.play(level, pos, slug, scale, yaw)`，
> 素材由 `tools/pixelfx-mc/export-pixelfx.mjs` 从 `D:\game\mc\_refs\pixelfx`（MIT）烘成图集，
> slug 必须同步登记进 `fx/PixelfxSlugs.java` 的 `ALL`。
> 硬约束：`MAX_EFFECTS = 64`、`MAX_RENDER_DISTANCE = 128`、pixelfx 是 2D 面片（无厚度 / 不遮挡 / 无阴影）。

## 一、全屏雷枪「碑文·千枪」

三段式，**每一段都留反制窗口**（红线：预警 ≥ 0.6s，否则玩家只会觉得被坑）。

| 段 | tick | 发生什么 | 实现 |
|---|---|---|---|
| **宣告** | 0 – 40 | 红字「天空，借我一用。」；天空压暗；地面浮现整片落点网 | `RedTruthOverlay`（已有）+ `PixelfxFx.playLooping(level, bossPos, tohka_phase2_eclipse, 90)` 复用现有天幕 slug，省一张图；落点用 `BossTelegraph.circle` 画 12 个半径 3 的圈 |
| **落下** | 40 – 90 | 12 根枪影从 y+40 垂直砸下，每 4 tick 落一根（错峰），落地炸开 | 每根：`epitaph_spear_fall`（billboard，scale 1.4，从高空往落点插值 spawn 一帧即可）；落地：`epitaph_spear_impact`（ground，scale 1.0） |
| **残响** | 90 – 140 | 地面插着一排枪（ground 锚点 sprite 停留 50 tick），灰烬粒子，天幕淡出 | `epitaph_spear_stand`（ground）；`ParticleTypes.ASH` |

**伤害结算（服务端，一次性）**：每根枪记录 `fired` 标志，落地那一 tick 对半径 3.0 内玩家造成
8 点魔法伤害 + 击退 0.6；同一 tick 内多根命中不叠加超过 16 点（否则 12 根重叠会秒人）。

**实例预算**：1 天幕 + 12 枪 + 12 冲击 + 12 插地 = 37（上限 64）。**建议同时最多 8 根**（= 25 实例），
把余量留给别的技能。

## 二、神话武器三件套

### Gungnir——奥丁的永恒之枪（贯穿直线）

- **形态**：天空出现一支横置枪影对准你，0.8s 后沿着一条 24 格的直线贯穿战场。
- **视觉**：`epitaph_gungnir`（billboard，`ice/ice-lance` 源，palette 换白金），沿直线每 4 格放一帧，共 6 实例。
- **判定**：以落点为原点、朝向你的方向，24×3 的 AABB（`getEntitiesOfClass`），14 点伤害 + 1.2 击退；
  **只有一条线**，侧身一步就能躲 —— 这是它的教学价值。
- **反制**：地面先画一条 24×3 的细长预警（`BossTelegraph` 拉长的圈或两段粒子线）。

### Mjolnir——索尔的雷霆之锤（砸落 + 环形冲击）

- **形态**：锤影从天上砸向 0.6s 前的你，落地炸环 + 连锁电光。
- **视觉**：`epitaph_mjolnir`（billboard，`impacts/blunt-hit` 放大 2.0）+ 落地 `epitaph_ring`（ground，`holy/sunburst`）；
  电光用 `ParticleTypes.ELECTRIC_SPARK` 连线到 4 个随机方向（纯粒子，成本可忽略）。
- **判定**：半径 6 内 10 点 + 半径 12 内 5 点；被内圈命中的玩家获得缓慢 II 40 tick。
- **反制**：落点就是 0.6 秒前你站的位置 —— **跑动即安全**，静止必吃。

### Aegis——雅典娜的神盾（防反）

- **形态**：一面光盾虚影挡在她面前 60 tick，期间**远程伤害 ×0.25**，并把飞来的投射物弹回去。
- **视觉**：`epitaph_aegis`（billboard，`holy/divine-shield` 源，scale 4，朝向施法目标）。
- **判定**：`LivingDamageEvent` 里判断 `source.is(DamageTypeTags.IS_PROJECTILE)` → 倍率 0.25；
  弹回用 `ProjectileImpactEvent`（原版箭/三叉戟）把速度取反 ×1.2。
  **注意**：黄金蝶是自己人，弹回逻辑要排除 `ButterflyMissileEntity`。
- **反制**：盾只挡一个方向 —— 绕到侧面砍。

## 三、素材清单（要烘的图）

| slug | pixelfx 源 | anchor | 尺寸 | 时长 | palette |
|---|---|---|---|---|---|
| `epitaph_spear_fall` | `ice/icicle-fall` | billboard | 96×384 | 1.0s | 冷白电光 `#0a1020… #ffffff` |
| `epitaph_spear_impact` | `explosions/plasma-explosion` | ground | 256×256 | 1.2s | 同上 |
| `epitaph_spear_stand` | `holy/holy-pillar`（截断到 0.5s） | ground | 96×128 | 0.5s | 金白 |
| `epitaph_gungnir` | `ice/ice-lance` | billboard | 512×128 | 1.2s | 白金 |
| `epitaph_mjolnir` | `impacts/blunt-hit` | billboard | 256×256 | 0.9s | 雷紫 |
| `epitaph_ring` | `holy/sunburst` | ground | 288×200 | 1.0s | 雷紫 |
| `epitaph_aegis` | `holy/divine-shield` | billboard | 512×512 | 3.0s | 金 |

> 全部 `seed` 固定（例如 11），帧率 20fps；`ice/icicle-fall`、`ice/ice-lance`、`holy/divine-shield`
> 都是竖直元素 —— **只准用 billboard 锚点**，压到 ground 会变成贴地光带（契约 §3 红线）。

## 四、接线点（改哪儿）

| 要做的事 | 改哪里 |
|---|---|
| 新特效能播 | `pixelfx` manifest 加条目 → 跑 `export-pixelfx.mjs` → `PixelfxSlugs.ALL` 加常量 |
| 贝阿朵莉切放技能 | `BeatriceEntity` 里加冷却字段 + 阶段机（她已有 `cast` / `cast_area` 两个动作可复用） |
| 预警圈 | `boss/BossTelegraph.circle` |
| 红字铺垫 | `ModNetwork.sendRedTruthKey(...)` |
| 屏幕震动（未实现） | 需要新包 + 客户端 `ViewportEvent.ComputeCameraAngles` 加噪声；一次演出 1 包 |

## 五、风险

- **2D 面片贴脸**：枪雨必须至少 20 格之外落，否则玩家能看见它是一片纸。
- **半透明排序**：37 个大面片叠在 `AFTER_PARTICLES` 阶段逐实例画，深度互相切；对策是「先大后小」固定顺序 + 天幕只开 1 个。
- **伤害叠加**：多根重叠必须封顶，否则一次演出能秒掉满血玩家（上面已写 16 点上限）。
- **不要**做"可走进去的塔"：那是方块/模型的事，见 `docs/MAGIC-FX-FEASIBILITY.md`。
