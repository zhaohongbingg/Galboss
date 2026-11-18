# pixelfx → GalBoss 集成契约（Lead 制定，v1）

本文件是 `fx-pipeline`（资源管线）与 `mc-render`（Java 渲染/接入）两个工作流之间的唯一接口。
**未经 Lead 同意不要修改本文件。**

## 0. 结论先行

pixelfx（https://github.com/patch-gg/pixelfx ，MIT）不是 GLSL/着色器工程，而是**用 JS 代码逐像素画
像素画序列帧**的库：每个特效导出 `draw(p,t,o)` 或 `create(o)`，用 `frame.color`（像素）+ `frame.light`
（加色发光）两块缓冲描述一帧。所以它在 Minecraft 里唯一正确的用法是：**离线烘成序列帧贴图**，再用
MC 自己的渲染管线播放。不要试图把 JS 塞进 MC（没有 JS 运行时、也没有 canvas）。

MIT 许可允许商业使用。本仓库只引入烘好的 PNG + 元数据，不引入 JS 运行时代码。

## 1. 交付目标

在 GalBoss（MC 1.20.1 / Forge 47.4.10 / official mappings）里实现一条可复用的像素序列帧特效通道：

1. Node 导出工具：从 pixelfx 源码渲染指定特效 → 烘成 MC 贴图/元数据。
2. 客户端渲染器：世界空间四边形（地面平铺 / 面向相机的立牌），支持
   **逐帧时长、循环、palette 已烘焙、加色 light 通道、未知 slug 容错**。
3. 服务端广播：boss 技能在正确时机把特效发给附近玩家（S2C 包），客户端纯表现。
4. 调试命令 `/galboss fx <slug> [scale] [yaw]`，用于游戏内验证。
5. 至少 6 处真实技能挂钩（见 §3 的 `hook` 列）。

**不做**：不改 pixelfx 源码、不引入 npm 依赖、不做 244 个特效全量导入（只做 §3 清单）。

## 2. 素材与工作目录

- pixelfx 源码副本：`D:\game\mc\_refs\pixelfx`（Lead 已浅克隆，**只读**，不要改）
- 导出工具与产物审查图：`D:\game\mc\bossmod\tools\pixelfx-mc\`
- 烘出的贴图：`D:\game\mc\bossmod\src\main\resources\assets\galboss\textures\fx\`
- 烘出的元数据：`D:\game\mc\bossmod\src\main\resources\assets\galboss\pixelfx\`

pixelfx 渲染器是纯 JS（`tools/render.mjs` 只用自带 `tools/lib/png.mjs` 写 PNG），
本机 Node v22.23.4 已实测可直接跑，**不需要 npm install / canvas 依赖**。命令示例：

```powershell
cd D:\game\mc\_refs\pixelfx
node tools/render.mjs impacts/ground-slam --sheet --fps 20
```

导出实现请直接 `import { loadEffect, renderFrames } from "../../_refs/pixelfx/tools/lib/fxlib.mjs"`
一类的相对路径（注意目录层级），或从 `_refs/pixelfx` 目录内运行脚本。`renderFrames()` 返回的
`frames[i]` 同时包含 `clear`（RGBA，已合成到透明背景）、`raw`（color 缓冲 Uint32Array）、
`light`（light 缓冲 Uint32Array）——**请用 `raw` + `light` 自己写 PNG**，以保留精确的 color/light 两路信息；
`clear` 只能作为对照。需先读 `src/core/color.js` / `fx.js` 确认缓冲的打包格式（ARGB 还是 RGBA、
是否预乘），不要猜。

**贴图必须最近邻放大，禁止 mipmap 模糊**（Java 侧会强制 nearest + 关 mipmap，导出端只要保证 1:1 像素）。

## 3. 特效清单（唯一权威）

slug 是 MC 侧字符串 ID，双方都按这张表写死。fx-pipeline 必须全部导出成功；
`mc-render` 按 `hook` 列接入。所有特效**必须是 pixelfx 里 `meta.actor` 不存在的版本**
（带 actor 的会画一个灰色替身人，绝不能进 MC；`moves/*`、`fire/fire-ring`、`status/*` 都属于这类）。
每个特效固定 `seed`（在下面 options 里给死），保证每次烘出来一致。

| slug | pixelfx id | 尺寸 | 时长 | 循环 | anchor | defaultScale | 归属 / hook 点 |
|---|---|---|---|---|---|---|---|
| `tohka_iron_fist` | `impacts/ground-slam` | 288×208 | 3.2s | 否 | ground | 0.020 | 桐香·铁拳制裁 `IronFistSkill.hit()` |
| `tohka_bell_wave` | `impacts/ground-slam`（与铁拳同源，换 GOLD palette；**截断到 2.25s / 45 帧**） | 288×208 | 2.25s | 否 | ground | 0.021 | 桐香·判决铃冲击波 `JudgmentBellSkill` 波前 |
| `tohka_confinement_sigil` | `shadow/hex-sigil` | 168×128 | 4.2s | 否 | billboard | 0.020 | 桐香·禁足令 `ConfinementSkill.start()` |
| `tohka_convocation_circle` | `arcane/rune-circle`（**原 `arcane/magic-circle-cast` 已弃用**：本体带竖直光柱，压平贴地会变成地面光带）| 136×100 | 2s（截断 40 帧，loop 元数据强制 false） | 否 | ground | 0.020 | 桐香·学生会召集 `ConvocationSkill.start()` |
| `tohka_verdict_smite` | `holy/smite` | 224×176 | 2.8s | 否 | billboard | 0.020 | 桐香·全校裁决 `VerdictSkill` 命中每人 |
| `tohka_phase2_eclipse` | `shadow/eclipse` | 160×144 | 4s | **是** | billboard | 0.020 | 桐香·二阶段 `enterPhase2()`（loop，寿命 50 tick） |
| `rei_bullet_impact` | `impacts/bullet-impact` | 152×100 | 1.4s | 否 | billboard | 0.020 | 礼·枪击命中（`TadasugawaReiEntity` 攻击结算处） |
| `rei_crit_hit` | `impacts/crit-hit` | 216×136 | 0.9s | 否 | billboard | 0.020 | 礼·连段终结 / 高伤命中 |
| `ikuko_parry_flash` | `impacts/parry` | 200×112 | 1s | 否 | billboard | 0.020 | 郁子·刀锋相交（`OnabutaIkukoEntity` 命中/被格挡） |
| `ikuko_quake` | `nature/quake` | 288×104 | 3.1s | 否 | ground | 0.021 | 郁子·狂战·一斉突撃落地（冲击波） |

palette（`options.palette`，深→亮 ramp，直接传给 pixelfx options）：

- 桐香·猩红（铁拳）：`["#1c0a10","#3d0f18","#6f1622","#a8222c","#d93a38","#ff7a5c","#ffc2a0","#fff0e0"]`
- 桐香·纪律金：`["#241703","#4a2c08","#7d4a0e","#b87417","#e0a12b","#ffd25a","#fff0a8","#ffffff"]`
- 桐香·威压紫：`["#120a1e","#241238","#3f1f5e","#5c2f86","#7d49b0","#a471d8","#d3aef5","#f4e6ff"]`
- 礼·琥珀：`["#1c1206","#3a2408","#6b410c","#9c5f12","#cc8420","#ffb347","#ffd98a","#fff4d6"]`
- 郁子·天青：`["#081420","#0f2942","#1a4468","#24628f","#2f86bf","#5cb3e0","#a3dbf2","#eafaff"]`

各特效除 palette 外的额外 options 由 fx-pipeline 读取该特效 `meta.options` 决定是否覆盖
（例如 `shadow/eclipse` 还有 `pearl`，`impacts/ground-slam` 还有 `stone`/`reach`）；
**seed 必须固定**（例如统一 `seed: 11`，写进 manifest），不要用随机。

导出规格：

- 帧率 **20 fps**（= MC tick 率，一 tick 一帧；`renderFrames({fps: 20})`）。
- manifest 每条可选 `maxDuration`（秒）：渲染后截断帧数（`tohka_bell_wave` 用它压到 2.25s / 45 帧，
  `tohka_convocation_circle` 用它压到 2.0s / 40 帧）；可选 `loopOverride`（覆盖 pixelfx 的 loop，
  例如 `arcane/rune-circle` 源是 loop，但作为一次性召唤特效要强制 false）。
  同一 pixelfx id 允许用不同 palette 出多个 slug。要选用的 effects 的 `light` 缓冲普遍非空，所以这 10 个应都产出 `_light.png`。
- **ground anchor 选型红线**：不能选带竖直元素的特效（竖直光柱 / 竖立符印 / 站立角色）。压平到 XZ 平面后
  竖直内容会变成"射向远处的地面光带"——`arcane/magic-circle-cast` 就是这样被替换掉的。
  ground 类应只用地面透视（法阵、冲击环、裂谷、贴地烟尘）。
- 帧数 = 该特效时长 × 20（one-shot 会多 1 帧尾帧，按导出器实际返回值来）。
- 取所有帧的**并集包围盒**裁剪（忽略完全透明像素），再排成网格（列数让贴图宽 ≤ 2048、高 ≤ 4096）。
  裁剪后 pivot = `meta.origin - bboxMin`（像素坐标，原点在左上）。
- 每个特效输出两张网格图：`<slug>.png`（color，RGBA，alpha 精确保留）与
  `<slug>_light.png`（加色光：RGB = light 缓冲颜色，A = 该像素最大通道值；light 全空的特效
  可以不写 `_light.png`，元数据里 `lightTexture` 置 null）。
- `anchor=ground` 的特效用于贴地：pivot 要落在贴图**底边中点**附近（用裁剪后的实际 pivot，不要强行改），
  保证「地面接触点」= 特效原点；`anchor=billboard` 的 pivot 同样按元数据 origin 计算。

## 4. 资源文件格式（冻结）

`assets/galboss/pixelfx/<slug>.json`：

```json
{
  "slug": "tohka_iron_fist",
  "pixelfxId": "impacts/ground-slam",
  "texture": "galboss:textures/fx/tohka_iron_fist.png",
  "lightTexture": "galboss:textures/fx/tohka_iron_fist_light.png",
  "frameW": 244, "frameH": 152,
  "cols": 8, "rows": 9, "frames": 65,
  "fps": 20, "loop": false, "durationTicks": 65,
  "pivot": { "x": 122, "y": 140 },
  "anchor": "ground",
  "defaultScale": 0.02,
  "palette": ["#1c0a10", "..."],
  "source": {
    "repo": "https://github.com/patch-gg/pixelfx",
    "license": "MIT",
    "effect": "impacts/ground-slam",
    "seed": 11,
    "adaptedFrom": "Hubo Duprez / Sprite Fusion (MIT), Hollow Crown (MIT) 见 NOTICE.md"
  }
}
```

约束：`fps` 固定 20；`durationTicks = frames`（20fps 下 1 tick = 1 帧）；
`texture` 用 `galboss:` 命名空间、相对 `textures/` 的路径且**不带 `.png`**（这是资源 id 的写法；
Java 侧直接用 `TextureManager` 时必须自己补 `.png` —— `SimpleTexture.load` 是精确 open，不带扩展名的 id
只有经过 atlas/Sprite 体系才会被补全，补错就是洋红 missing texture）。
`frameW/frameH` 是裁剪后单帧尺寸，`cols×rows ≥ frames`（空位留透明）。
未知 slug/缺失 JSON 时客户端必须**只记一次日志并跳过**，绝不能崩。

## 5. Java 侧接口（mc-render 实现，服务端只用这层）

```java
package com.example.galboss.fx;

public final class PixelfxFx {
    /** 服务端调用：把特效广播给 64 格内能看到该位置的玩家；客户端侧调用则本地播放。 */
    public static void play(Level level, Vec3 pos, String slug);
    public static void play(Level level, Vec3 pos, String slug, float scale, float yawDegrees);
    /** 循环特效（如 eclipse）：lifeTicks 后自动结束。 */
    public static void playLooping(Level level, Vec3 pos, String slug, int lifeTicks, float scale, float yawDegrees);
}
```

- 服务端**不得**引用任何 `net.minecraft.client.*` 类（专用服务器会 ClassNotFound）。
- 渲染必须是纯客户端：`RenderLevelStageEvent.Stage.AFTER_PARTICLES` + 自己写顶点
  （`DefaultVertexFormat.POSITION_TEX_COLOR`，shader `position_tex_color`，全亮无 lightmap），
  color 通道 `SRC_ALPHA / ONE_MINUS_SRC_ALPHA`，light 通道 `SRC_ALPHA / ONE`，`depthMask(false)`、
  关剔除、`RenderSystem.enableBlend()`。文字/画面不能盖住 GUI：
  只在世界层画，不进 HUD。
- 贴图用 `TextureManager` 直接按 ResourceLocation 加载（**不进粒子图集**，单张网格贴图即可），
  加载后强制 `AbstractTexture#setFilter(false, false)`（最近邻、关 mipmap）。
- 帧选择按时间：`t = (age + partialTick) / 20.0` 秒，`frame = min(frames-1, floor(t * fps))`；
  loop 特效取模。
- `anchor=ground`：四边形躺在 XZ 平面（绕 X 轴 -90°），pivot 对齐 `pos`，贴图的"上"默认朝北（-Z），
  `yaw` 绕 Y 轴旋转。`anchor=billboard`：竖直面向摄像机（只绕 Y 轴朝相机，保持像素竖直），
  pivot 底边中点对齐 `pos`。
- 无纹理/无元数据时跳过并 `LOGGER.warn` 一次（用 Set 去重）。
- 生命周期：one-shot 播完移除；loop 到 lifeTicks 移除。离玩家 > 128 格不渲染但保留计时。

## 6. 命令

`/galboss fx <slug> [scale] [yaw]` —— 需要 OP（level 2）。在命令执行者前方 ~3 格生成，
用于游戏内肉眼验证。`/galboss fx list` 打印已知 slug。实现放
`com.example.galboss.command.GalBossCommand`，用 `RegisterCommandsEvent` 注册到
`MinecraftForge.EVENT_BUS`。

## 7. 质量门禁（任何一条不过就不算完成）

1. `gradlew build` 必须 exit 0（`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'`；本项目 Gradle 7.6.4）。
2. 10 个特效全部导出成功，`_review/` 下有每个特效的 contact sheet（供 Lead 抽检构图）。
3. JSON 与贴图一一对应：frames/cols/rows/pivot 自洽；不存在 actor 替身人画面。
4. 游戏内 `/galboss fx` 能真实播放（由 qa-runtime 用 mcdev probe 截图验证）。
5. 专用服务器不崩（服务端类不含 client 引用；packet 注册在 common 侧）。
6. 归属声明：`tools/pixelfx-mc/NOTICE.md` 写明 pixelfx 的 MIT 许可与原作者，
   生成的 JSON `source` 字段保留出处。

## 8. 协作纪律

- 文件所有权（不要越界写别人的目录）：
  - `fx-pipeline`：`tools/pixelfx-mc/**`、`src/main/resources/assets/galboss/textures/fx/**`、
    `src/main/resources/assets/galboss/pixelfx/**`、`_refs/pixelfx` 只读。
  - `mc-render`：`src/main/java/**`（新增 `fx/`、`client/fx/`、`command/`，改动
    `network/ModNetwork.java`、三个 boss entity 的挂钩点）。**不要动** `assets/**`。
  - `qa-runtime`：`run/**`、`tools/pixelfx-verify/**`。
- 读文件用 read 工具（PowerShell `Get-Content` 会把 UTF-8 中文显示成乱码，但不影响文件本身）。
- 本仓库所有源码注释是中文，新增代码保持中文注释风格。
- 遇到阻断（缺依赖、API 不存在、导出失败）立刻在共享任务里写明并 `send_message` 给 `lead`，
  不要自己换方案绕过契约。
