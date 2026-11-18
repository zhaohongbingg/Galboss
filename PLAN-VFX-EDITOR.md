# 游戏内可视化特效编辑器（VFX Editor）方案

> 目标：把「用语言描述特效 → 改 Java → 重编译重启」换成「在游戏里看着拖 → 存 json → 立刻生效」。
> 决策（用户已拍板）：**世界内拖拽 + HUD 侧栏** / **纯本地开发工具** / **图集校准器要做**。

## 0. 边界

| 进游戏做 | 不进游戏做 |
|---|---|
| 摆放（锚点 / 缩放 / 朝向 / 时机） | 画像素图 |
| 编排（一个技能的多段特效时间轴） | 烘序列帧图集（继续走 `tools/pixelfx-mc`） |
| 调参（颜色 / 半径 / 粒子数 / 震屏） | 生成贴图素材 |
| 图集切片校准（pivot / 帧格 / fps） | — |

## 1. 为什么必须先做数据层

现在参数散在 Java 调用点，编辑器无物可编：

- `PixelfxFx.play(level, pos, slug, scale, yaw)` —— scale/yaw 由调用方传死
- `SpellFx.MAX_POINTS = 48` / `MAX_LINE_POINTS = 64` —— 常量
- `PixelfxSlugs.ALL` —— 硬编码白名单，**加特效要重编译**

已发生的真实事故：`assets/galboss/pixelfx/` 有 17 个 json，白名单只有 10 个，
7 个 `epitaph_*` 烘好了但播不出来（`PixelfxFx.playInternal` 直接拒掉未知 slug）。

## 2. 数据模型

### 2.1 三层来源（后者覆盖前者，字段级合并）

| 层 | 路径 | 谁写 | 用途 |
|---|---|---|---|
| L1 烘焙产物 | `assets/galboss/pixelfx/<slug>.json` | 资源管线 | 随 jar 发布，只读 |
| L2 本地覆盖 | `config/galboss/fx/<slug>.json` | 编辑器 / 手改 | **开发期调参**，可只写要改的字段 |
| L3 运行时 | 内存 `Map<String, JsonObject>` | `EffectLibrary` | 双端统一的唯一真相 |

L2 支持**部分覆盖**（只写 `{"pivot":{"x":120}}` 即可），靠「浅合并 + 对象递归合并」。
这正是 v1 拖拽编辑器回写数值时要用的落盘格式。

### 2.2 定义字段（冻结，沿用 CONTRACT.md §4）

```json
{
  "slug": "tohka_bell_wave",
  "texture": "galboss:textures/fx/tohka_bell_wave",
  "lightTexture": "galboss:textures/fx/tohka_bell_wave_light",
  "frameW": 248, "frameH": 184, "cols": 8, "rows": 6, "frames": 45,
  "fps": 20, "loop": false, "durationTicks": 45,
  "pivot": { "x": 125, "y": 141 },
  "anchor": "ground",
  "defaultScale": 0.021
}
```

必填校验：`texture` + `frames`。缺任一 → 跳过该 slug 并只警告一次。

### 2.3 v2 轨道格式（预留，v0 不实现）

```json
{ "id": "gungnir_cast",
  "tracks": [
    { "type": "pixelfx",   "atTick": 0,   "slug": "tohka_verdict_smite", "scale": 2.0, "yaw": 0, "offset": [0,0.6,0] },
    { "type": "telegraph", "atTick": -40, "durationTicks": 40, "radius": 3.5, "color": "gold" },
    { "type": "shake",     "atTick": 0,   "strength": 0.6, "durationTicks": 12 },
    { "type": "postfx",    "atTick": 0,   "id": "impact_frame", "durationMs": 120 },
    { "type": "sound",     "atTick": 0,   "id": "minecraft:entity.lightning_bolt.thunder" }
  ] }
```

`atTick` 负数 = 相对命中时刻提前。

## 3. 分期

| 期 | 内容 | 验收判据 | 状态 |
|---|---|---|---|
| **v0** | `EffectLibrary`（扫描 + 合并 + 校验 + generation）+ `FxReloadListener`（双端）+ 动态 slug + `/galboss fx reload` | 改 `config/galboss/fx/*.json` → `/galboss fx reload` → 立刻变；17 个 slug 全可播 | **已交付** |
| v1 | 世界内拖拽：锚点 gizmo、滚轮缩放、朝向环、HUD 侧栏；`/galboss fx edit <slug>`；写回 L2 | 拖完不用重进游戏，数值回写 json | 待做 |
| v2 | 时间轴：playhead、多轨道增删、关键帧、scrub 预览 | 一个技能多段特效排完 | 待做 |
| v3 | 图集校准器：贴图铺开、拖 pivot、框选帧格 → 生成 json | 新图集不靠外部工具接入 | 待做 |

## 4. 关键约束（不可违反）

1. **`fx` 包不许出现 `net.minecraft.client.*`**（专用服务器 NoClassDefFoundError）。客户端解析留在 `client/fx`。
2. `EffectLibrary` 双端可用，只存 `JsonObject`，不存 `PixelfxMetadata`。
3. 未知 slug / 坏 json **只警告一次并跳过**，绝不崩。
4. 重载让客户端缓存失效靠 `EffectLibrary.generation()` 计数器，**不加网络包**。
5. 编辑器产物一律写 `config/galboss/fx/`，**绝不写 `assets/`**（assets 在 jar 里，运行期写不进去）。

## 5. 文件清单

| 文件 | 动作 |
|---|---|
| `fx/EffectLibrary.java` | 新增 |
| `fx/FxReloadListener.java` | 新增 |
| `fx/PixelfxSlugs.java` | 白名单改动态 + 常量兜底 |
| `client/fx/PixelfxAssets.java` | 优先读 `EffectLibrary` 合并结果 |
| `GalBoss.java` | 挂服务端 `AddReloadListenerEvent` |
| `client/ClientSetup.java` | 挂客户端 `RegisterClientReloadListenersEvent` |
| `command/GalBossCommand.java` | `fx reload` + 动态 list/suggest |
