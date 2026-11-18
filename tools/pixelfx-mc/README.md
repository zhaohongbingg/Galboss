# pixelfx → GalBoss 像素特效素材管线

pixelfx（<https://github.com/patch-gg/pixelfx>，MIT）不是 GLSL 工程，而是**用 JS 代码逐像素画
像素画序列帧**的库。MC 里没有 JS 运行时也没有 canvas，所以它的正确用法是在开发机上**离线烘成序列帧
贴图**，再由 MC 自己的渲染管线播放。

本仓库**只引入烘好的 PNG + 元数据 JSON**，不含任何 pixelfx 的 JS 运行时；pixelfx 源码位于开发机的
`D:\game\mc\_refs\pixelfx`（只读，不入库），只在烘焙时需要。

对应 Java 侧实现：`com.example.galboss.client.fx`（渲染/元数据加载）、`com.example.galboss.fx`
（`PixelfxFx` 播放入口、`PixelfxSlugs` 字符串常量）。接口契约见 `CONTRACT.md`（Lead 维护，不要改）。

## 目录

```
tools/pixelfx-mc/
  CONTRACT.md           与 mc-render 的唯一接口契约
  manifest.mjs          10 条导出清单（slug / pixelfxId / palette / seed / anchor / scale / maxDuration）
  export-pixelfx.mjs    导出器（渲染 → 裁剪 → 网格贴图 → JSON → 审查图）
  NOTICE.md             许可与归属说明
  _out_check/           默认烘焙输出（不碰 assets，用来比对/演练）
  _review/<slug>/       contact.png（color+light 叠棋盘格 + pivot 十字）与 light.png（只显示加光通道）

src/main/resources/assets/galboss/
  textures/fx/<slug>.png           color 网格图（RGBA，alpha 精确）
  textures/fx/<slug>_light.png     light 网格图（RGB=加色光，A=该像素最大通道）
  pixelfx/<slug>.json              帧尺寸/网格/pivot/anchor/defaultScale/palette/出处
```

## 命令

| 命令 | 作用 | 写到哪里 |
|---|---|---|
| `node tools/pixelfx-mc/export-pixelfx.mjs` | 烘焙全部 10 个（安全默认，供演练/比对） | `tools/pixelfx-mc/_out_check/` |
| `node tools/pixelfx-mc/export-pixelfx.mjs --all` | **正式重烘全部 10 个** | `src/main/resources/assets/galboss/` |
| `node tools/pixelfx-mc/export-pixelfx.mjs --slug X` | 只烘一个到检查目录 | `_out_check/` |
| `node tools/pixelfx-mc/export-pixelfx.mjs --slugs a,b,c` | 只烘指定的几个（`--only X` 等价 `--slug X`） | `_out_check/` |
| `node tools/pixelfx-mc/export-pixelfx.mjs --slug X --all` | 只烘一个并落地 | assets |
| `node tools/pixelfx-mc/export-pixelfx.mjs --out D:\tmp\fx` | 指定输出根（生成 `textures/` 与 `meta/`） | 指定目录 |
| `node tools/pixelfx-mc/export-pixelfx.mjs --review-only` | 不渲染，直接读已落地贴图重放 `_review/` | `_review/` |
| `node tools/pixelfx-mc/export-pixelfx.mjs --verify` | 校验 assets 自洽性 + 与 `_out_check` 逐字节比对 | 只读 |

默认输出到 `_out_check/` 是刻意的：`assets/` 里的贴图是 qa 实机验证过的冻结产物，重烘必须显式加
`--all`，避免手滑覆盖。所有模式都只依赖 Node（本机 v22 实测），**不需要 npm install / canvas**。

## 烘焙管线

每条 manifest 条目都走同一套流程（`export-pixelfx.mjs` 的 `bakeOne`）：

1. `loadEffect(pixelfxId)` → 校验 `meta.actor` 为空（带 actor 的特效会画灰色替身人，直接抛错）。
2. `renderFrames(fx, { fps: 20, bg: "none", actor: false, options: { palette, seed } })`
   —— 20 fps 与 MC tick 对齐，1 tick = 1 帧。帧数 = `round(duration*20)`，one-shot 多一帧尾帧，
   loop 不多；`maxDuration` 条目再截断到 `round(maxDuration*20)` 帧。写进 JSON 的 `loop` 可以再用
   manifest 的 `loopOverride` 覆盖（源特效是 loop、但技能只播一次时用）。
3. **缓冲格式**（按 `src/core/color.js` / `fx.js` / `core/painter.js` 核实）：
   - `color` Uint32 = `a<<24 | b<<16 | g<<8 | r` → `r=c&255, g=c>>>8&255, b=c>>>16&255, a=c>>>24&255`
   - `light` Uint32 = `255<<24 | b<<16 | g<<8 | r` → **只用 rgb，alpha 字节无意义**
   - 写入 PNG 时 `_light.png` 的 A 取该像素 rgb 最大通道。
   - 全程按位解包（不套 `Uint8Array` 视图），因此不依赖宿主大小端；alpha 精确保留、不做预乘。
   - 每帧都与 `frames[i].clear`（fxlib 自己的合成结果）逐像素比对自检，格式假设错了立刻抛错。
   - **不用 `frames[i].color`**（里面混了背景和替身人），也**不用 `frames[i].clear`**
     （它是 color+light 的合成结果，会把两路信息揉成一路）。
4. 取所有帧的**并集包围盒**裁剪：`color.a>0` 或 `light!=0` 的像素都算内容（只算 color 会切掉发光）。
5. 排网格：`cols = min(frames, floor(2048/frameW))`，`rows = ceil(frames/cols)`，保证
   `cols*frameW ≤ 2048` 且 `rows*frameH ≤ 4096`；`cols*rows > frames` 的空位留全透明。
6. `pivot = meta.origin - bboxMin`（像素坐标，原点左上）。ground 特效的 pivot 落在贴图底边中点附近，
   即「地面接触点 = 特效原点」；billboard 同样按 origin 算。**不强行居中**，因为部分特效 origin 偏心
   （如 `impacts/bullet-impact` 的 origin 是 (116,84) / 152×100）。
7. 落盘后回读 PNG 尺寸与 JSON 字段做自洽校验；再从**刚写好的网格图**（不是重新渲染）重放
   `_review/<slug>/contact.png`，所以审查图里的每个像素都来自真正进游戏的贴图。

贴图一律 1:1 像素、最近邻、无 mipmap：导出端不放大不滤镜，Java 侧加载后强制
`AbstractTexture#setFilter(false, false)`。

## 元数据字段（`assets/galboss/pixelfx/<slug>.json`）

| 字段 | 说明 |
|---|---|
| `slug` | MC 侧字符串 ID，与 manifest / `PixelfxSlugs` 完全一致 |
| `pixelfxId` | 来源特效路径（`impacts/ground-slam`） |
| `texture` / `lightTexture` | `galboss:textures/fx/<slug>`，**不带 `.png`**；无加光时为 `null` |
| `frameW` / `frameH` | 裁剪后单帧像素尺寸 |
| `cols` / `rows` / `frames` | 网格列行数与有效帧数（`cols*rows ≥ frames`） |
| `fps` / `loop` / `durationTicks` | 固定 20；`loop` 可被 manifest 的 `loopOverride` 覆盖；`durationTicks` 与 `frames` 相等（1 tick = 1 帧） |
| `pivot` | 裁剪后原点，相对单帧的像素坐标 |
| `anchor` | `ground`（贴地四边形）/ `billboard`（面向相机立牌） |
| `defaultScale` | 1 像素对应的世界单位，调用方传的 scale 是它的倍数 |
| `palette` | 实际使用的 8 级 ramp（深→亮），便于查色 |
| `source` | 出处：repo / license / effect / seed / adaptedFrom |

## 新增一个特效

1. 在 `manifest.mjs` 的 `EFFECTS` 里加一条：`slug`、`pixelfxId`、`palette`、`seed`、`anchor`、
   `defaultScale`，必要时 `options`（覆盖特效自己的 options）、`maxDuration`（截断时长）、
   `loopOverride`（覆盖元数据 loop 字段）。
   **务必先确认该特效 `meta.actor` 为空**——带 actor 的（`moves/*`、`status/*`、`fire/fire-ring`、
   `lightning/thunderclap` 等）会画灰色替身人，不能用。
2. 先烘到检查目录看效果：
   `node tools/pixelfx-mc/export-pixelfx.mjs --slug <slug>`，然后打开
   `tools/pixelfx-mc/_review/<slug>/contact.png` 检查：没有替身人、没有黑底/白底、四周没被裁掉、
   帧序连贯、pivot 十字落点合理。
3. 满意后落地：`node tools/pixelfx-mc/export-pixelfx.mjs --slug <slug> --all`。
4. Java 侧：在 `com.example.galboss.fx.PixelfxSlugs` 加一个常量（字符串必须与 slug 逐字符一致）。
5. 技能里调用：`PixelfxFx.play(level, pos, PixelfxSlugs.XXX)`；循环特效用
   `PixelfxFx.playLooping(level, pos, slug, lifeTicks, scale, yawDegrees)`。
6. 回归：`node tools/pixelfx-mc/export-pixelfx.mjs --verify`，再 `gradlew build`。

## 可复现性与校验

- manifest 里 seed 逐条写死（11…20），渲染是确定性的：**连续两次烘焙 20 张 PNG + 10 个 JSON
  全部 SHA256 一致**。
- 与已落地（qa 验证过的）assets 对比：**20/20 PNG 逐字节一致**；JSON 仅多
  `source.adaptedFrom` 一个字段（Lead 批准的补充），其余字段完全一致。这条在
  `tohka_convocation_circle` 换源成 `arcane/rune-circle`（40 帧 / 2.0s / 118×96 / pivot 59,76）
  之后重跑仍然是 20/20 一致，说明换源后的产物同样可复现。
- `_out_check/` 永远按**当前 manifest** 生成，是 `--verify` 的比对基线；改了 manifest 就重烘一次，
  别拿旧基线对。
- `--verify` 会做三件事：读回两张 PNG 逐像素校验结构（尺寸 = `cols*frameW × rows*frameH`、
  `cols*rows ≥ frames`、空格子必须全透明、pivot 在帧内、`durationTicks == frames`）、
  与 `_out_check` 逐字节比对、报告全空帧等提示。
- 已知正常现象：one-shot 特效常在时长结束前就收笔，尾部会有几帧全空（pixelfx 的实际帧数按
  `duration*20+1` 来，契约要求照实际返回值，不额外裁）。`--verify` 只把它当提示。

## 尺寸与内存预算

- 磁盘：20 张 PNG 共 **3.37 MB**（+ 10 个 JSON 共 6.3 KB），单张最大 619 KB，远低于 4 MB/张、
  40 MB 总量的告警线。`_out_check/` 是同样大小的校验基线，如果仓库要控体积可以 gitignore 它
  （`--verify` 在没有基线时会跳过逐字节比对，只做结构校验）。
- GPU：MC 把贴图解码成 RGBA8 常驻（4 字节/像素），10 个特效 color+light 全加载约 **109 MB**，
  最大两张是 `tohka_iron_fist`（1952×1629，24.3 MB）和 `tohka_bell_wave`（1984×1104，16.7 MB）。
  `tohka_convocation_circle` 换源成 `arcane/rune-circle` 后从 25.2 MB 降到 4.4 MB。
  当前 10 个特效不会同时全部在场，实际峰值远低于此。
- 如果以后显存吃紧，可选的杠杆（按性价比排序）：① Java 侧用完 `TextureManager.release` 释放，
  不常驻；② 对超长特效降帧（MC 侧按 2 tick/帧播放，等于把 fps 降到 10）；③ 降 `maxDuration`。
  **改规格前先和 Lead 确认**，不要单方面改契约里的帧率/尺寸。

## 已知选型坑

- **`anchor=ground` 不能选带竖直光柱/竖直元素的特效。** pixelfx 里 `arcane/magic-circle-cast`
  （法阵 + 一道竖直光柱）就是反面教材：原版 billboard 看没问题，但当作贴地四边形压平后，光柱会变成
  一条「射向远处的光带」，观感完全不对。给 ground 锚点选型时先看原版 contact：出现**竖直光束、
  光柱、瀑布、雨柱、上升烟柱**这类有明确竖直方向的东西就不要用，只选纯地面透视的（法阵、冲击波、
  裂地、尘土环）。`tohka_convocation_circle` 因此从 `magic-circle-cast` 换成了
  `arcane/rune-circle`（136×100，纯地面透视法阵 + 上升光点）。
- **短小/内容稀疏的特效要适当放大 `defaultScale`。** 枪口火花、格挡火花这类帧小、像素稀的特效，
  按 0.02（1 px ≈ 2 cm）放到 MC 世界尺寸里远处就看不清，实机复验后放大到 0.027~0.032
  才读得出来（`rei_bullet_impact` 0.030、`rei_crit_hit` 0.028、`ikuko_parry_flash` 0.030、
  `ikuko_quake` 0.027）。像素风靠放大像素而不是加分辨率，所以优先调 `defaultScale`，别去重烘更大尺寸。
- **同源特效可以换 palette 出多个 slug。** `tohka_iron_fist` 与 `tohka_bell_wave` 都是
  `impacts/ground-slam`，靠 palette（猩红/纪律金）+ `maxDuration` 区分，不必为每个技能找独立特效。
- **loop 源特效做一次性技能**：用 `maxDuration` 截一段 + `loopOverride: false` 写死一次性播放
  （`tohka_convocation_circle` 就是这样：`rune-circle` 本来是 loop，截到 2.0s / 40 帧一次性播完）。
- **带 `meta.actor` 的特效一律不能用**（会画灰色替身人），导出器已经会在渲染前直接拒绝。

## 踩坑记录

- `frames[i].clear` 看着最"现成"，但它已经把 light 加进 color 了，拿它做贴图等于把加光通道烧死在
  color 里，失去 Java 侧两 pass（`SRC_ALPHA/ONE_MINUS_SRC_ALPHA` + 加色）的分离——所以坚持
  `raw` + `light` 两路自己写 PNG。
- 裁剪只算 color 的 alpha 会把纯发光（alpha=0 但有 light）切掉，包围盒必须两路一起算。
- `lightning/thunderclap` 这类 `meta.actor=2` 的特效，draw 里会 `p.mask(o.actor.mask, ...)`
  给替身人上高光，烘出来的帧里就有一块人形剪影；导出器在 `loadEffect` 之后第一件事就是拒绝它。
- 网格 `cols` 不是越多越好：`cols*frameW` 超 2048 会被导出器直接抛错，别手改 JSON。
