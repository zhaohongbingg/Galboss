# 给 Gemini 的提示词 · 第三件：神枪 `holy/divine-lance.js`

> 这是「一个一个来」的第三件（前两件护盾、战锤已完成）。
> 把本文件整段复制给 Gemini，并附上 `docs/pixelfx-refs/` 里的 3 个参考文件。

---

## 任务

为开源库 **pixelfx**（https://github.com/patch-gg/pixelfx）写一个新特效
**`holy/divine-lance.js`**：一柄由圣光凝成的长枪，斜向刺出。

它会被烘焙成序列帧贴图，用在 Minecraft 模组里作为「奥丁的永恒之枪 Gungnir」的显形效果。
现在这个位置用的是 `ice/ice-lance`（冰枪，靠金色 tint 硬染成金），语义不对——我们要一柄**真正的光枪**。

---

## 硬性约束（违反任何一条，烘焙器直接抛错拒绝）

1. **禁止 `actor`**：`meta` 里不能出现 `actor` 字段，不能 `import`/使用 `o.actor`，不能导出 `act` 或 `onBody`。
   **画面里不能出现任何人形、手、身体。**
2. **单文件零依赖**：只允许 `import { ... } from "../../core/index.js"`（以及同目录 `_lib.js` 里已有的导出，但**新建 `_lib.js` 不行**，只能就地写）。
3. **确定性**：随机数**只能**用 `new Rng(o.seed)`。禁止 `Math.random()` / `Date.now()` / `performance.now()`。
4. **时间**：`duration × 20 = 帧数`。one-shot 在 `t = duration` 时必须**画面全空**。
5. **画布与网格**（已算好，照抄即可）：
   - `size: [240, 96]`
   - `duration: 1.0` → **20 帧**
   - 网格 **8 列 × 3 行 = 24 ≥ 20**；贴图 `240 × 8 = 1920 ≤ 2048` 宽、`96 × 3 = 288 ≤ 4096` 高 ✓
6. **必须真的发光**：光只来自 `p.glow` / `p.glowLine` / `p.glowRing` / `p.light` / `lights.add(...)`。
   把颜色画成白色**不会**产生光层。
7. **色板**：`const C = fit(o.palette, 8)`，按「暗 → 亮」用（`C[0]` 阴影，`C[7]` 白热核心）。
   除 `WHITE` 外**不要硬编码任何颜色**。
8. **锚点**：`(0, 0)` 是语义原点，`x 向右、y 向下、1 单位 = 1 像素`。
   `meta.origin: [216, 48]` —— 即**枪尖**在画布右端、垂直居中。**枪身从枪尖往左延伸**。
   所以「枪往前刺」时，`(0,0)` 就是枪尖所在的那个点。

---

## 画面要求

**类型**：模拟型 one-shot（`export function create(o)` → `{ update(dt), draw(p) }`），`loop: false`。

**分阶段编排**（必须有前摇、释放、余韵，不要一上来就是一根完整的枪）：

| 时间 | 内容 |
|---|---|
| 0 ~ 0.25s | **凝聚**：光点与细长流光从右侧（枪尖方向）和四周向枪身轴线汇聚，枪身轮廓由虚到实，`P.STREAK` 向内收；轴线上一道细光先行亮起 |
| 0.25 ~ 0.35s | **成形 + 冲刺**：枪身完整显现（1~2 帧白热帧），带 2 帧速度残影；枪尖爆出 `flare` 式 8 芒星闪，`lights.add` 一记强光 |
| 0.35 ~ 0.7s | **贯穿余韵**：枪身保持发光但开始从尾部崩解成光点向后飘散；沿轴线留下一道渐暗的光痕；细小火星沿枪身表面流过 |
| 0.7 ~ 1.0s | **消散**：枪身碎成光粒向外扩散、上浮、冷却消失，光痕淡出 |

**枪的造型**（约 200px 长、最宽处 26px）：
- **枪头**：细长的菱形/柳叶形分面，1px 深色外描边 + 1.5px 高光斜切棱，白热核心在尖端
- **枪身**：锥形（靠近枪头粗、尾部细），中间一道 1px 亮线贯穿（像发光的内芯），两侧有 2~3 道更暗的纵向分面线
- **枪镡**（枪头与枪身交界）：一个小型十字或环形配重，比枪身亮一档
- **尾部**：收成尖端，拖出能量拖尾
- 整体朝向：**枪尖在右**（`x` 正方向），符合 pixelfx「方向性特效朝右」的约定

**必须有光**：枪身轴线一条 `glowLine`、枪尖一个 `p.glow` 强光点、整体一个 `p.glow` 柔光、命中/成形瞬间 `lights.add`。

---

## 参考文件（附件一起给 Gemini）

| 文件 | 看什么 |
|---|---|
| `docs/pixelfx-refs/ice-lance.js` | **最接近的结构**：一个飞行中的长枪怎么做（`size: [176,64]`、`origin: [146,32]`、枪尖在右、loop）。照它的骨架，把冰换成光 |
| `docs/pixelfx-refs/crit-hit.js` | 冲击型 one-shot 的分阶段写法与 `Particles`/`Lights` 用法 |
| `docs/pixelfx-refs/sunburst.js` | 光的层次：白热核心 + 金色晕 + 射线（本任务的光枪需要同样的层次） |

API 速查见同目录上一份提示词 `docs/PROMPT-pixelfx-effects.md` 的「附录 A」——**精确签名以那份为准**，特别是：

```js
p.glow(x, y, r, c, k = 0.5)
p.glowLine(x0, y0, x1, y1, r, c, k = 0.5)
p.glowRing(x, y, R, r, c, k = 0.5)
p.shade(x0, y0, x1, y1, fn)     // fn(x, y, out) → 设 out.c / out.a / out.l / out.k
ps.spawn(kind, x, y, vx, vy, life, color, size, delay, flags)   // 是 spawn，不是 add
P = { SPARK:0, MOTE:1, STREAK:2, FLAKE:3, VAPOUR:4, DUST:5, STAR:6, EMBER:7, SMOKE:8, FIRE:9, BRAND:10, GRIT:11 }
```

---

## 输出格式

输出**一个完整的 `.js` 文件**，路径写在代码块上方，不要省略成 `// ...`：

```
// 文件：_refs/pixelfx/src/fx/holy/divine-lance.js
import { ... } from "../../core/index.js";
export const meta = { ... };
export function create(o) { ... }
```

---

## 写完之后自查（逐条回答）

1. `meta` 里有 `actor` 吗？（**必须没有**）
2. 有 `Math.random()` / `Date` 吗？（**必须没有**）
3. `duration × 20` = 多少帧？网格 `8 × 3 = 24` 够不够？贴图会不会超 `2048 × 4096`？
4. 光通道调了哪些接口？逐条列出 `p.glow*` / `p.light` / `lights.add` 的位置。
5. `t = duration` 时是不是全空？
6. 画面里有任何人形/手/身体吗？（**必须没有**）
7. 有没有硬编码颜色？（除 `WHITE` 外必须全部走 `o.palette`）
8. 枪尖是不是在画布右端、垂直居中（`origin: [216, 48]`）？枪身是不是往左延伸？

---

## 交付之后（我方执行，不用你管）

```bash
node tools/pixelfx-mc/export-pixelfx.mjs --slug epitaph_gungnir   # 只进 _out_check/
node tools/pixelfx-mc/export-pixelfx.mjs --slug epitaph_gungnir --all
```

烘焙器会硬校验：actor / 空帧 / 尺寸越界 / pivot 出帧 / JSON 与贴图自洽。不过就抛错。
