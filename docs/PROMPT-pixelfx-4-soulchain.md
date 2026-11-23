# 给 Gemini 的提示词 · 第四件：魂链 `shadow/soul-chain.js`

> 这是「一个一个来」的第四件（护盾、战锤、光枪已完成）。
> 把本文件整段复制给 Gemini，并附上 `docs/pixelfx-refs/` 里的参考文件。

---

## 任务

为开源库 **pixelfx**（https://github.com/patch-gg/pixelfx）写一个新特效
**`shadow/soul-chain.js`**：一条把两个目标连起来的**魂之索**——白紫色的能量绳，
双股缠绕，有能量团沿着它流动。

它会被烘焙成序列帧贴图，在 Minecraft 里用作「魂链」法术的连线：把周围 6 只怪物串成共伤链。

---

## 渲染契约（这条最重要，决定素材该怎么画）

**它不会被当作一整幅图摆放，而是会被沿两点连线拉伸使用。**

具体来说，渲染器会：
1. 把这条贴图的 **左端（pivot）对齐起点 A**，**右端对齐终点 B**
2. 按 A→B 的实际距离**横向拉伸**（可能拉伸到 4 倍，也可能压缩到 0.5 倍）
3. 纵向保持原比例

**由此推出三条硬性设计要求：**

- **水平方向的细节必须是低频的**。不要画密集的横向虚线、横向条纹、密集的锯齿——
  那些一拉伸就会变成难看的粗长条。把细节放在**垂直方向**（粗细变化、上下分层）。
- **两端要能独立看**。左端是「系在目标身上的结」，右端是「散开的须」——
  但两端都必须让贴图**从边缘自然延伸出去**，不要画一个「封闭的框」。
- **必须无缝循环**（`loop: true`，这条索会持续显示 10 秒）。首尾帧要能接上。

---

## 硬性约束（违反任何一条，烘焙器直接抛错拒绝）

1. **禁止 `actor`**：`meta` 里不能出现 `actor` 字段，不能 `import`/使用 `o.actor`，不能导出 `act` 或 `onBody`。
   **画面里不能出现任何人形、手、身体。**
2. **单文件零依赖**：只允许 `import { ... } from "../../core/index.js"`，不能新建 `_lib.js`，只能就地写函数。
3. **确定性**：随机数**只能**用 `new Rng(o.seed)`。禁止 `Math.random()` / `Date.now()` / `performance.now()`。
4. **必须真的发光**：光只来自 `p.glow` / `p.glowLine` / `p.glowRing` / `p.light` / `lights.add(...)`。
   把颜色画成白色**不会**产生光层。
5. **色板**：`const C = fit(o.palette, 8)`，按「暗 → 亮」用。除 `WHITE` 外**不要硬编码任何颜色**。
6. **画布与网格**（已算好，照抄即可）：
   - `size: [240, 64]`
   - `origin: [8, 32]` —— **pivot 在左端、垂直居中**
   - `duration: 1.0`、`loop: true`、`fps: 20` → **20 帧**
   - 网格 **8 列 × 3 行 = 24 ≥ 20**；贴图 `240 × 8 = 1920 ≤ 2048` 宽、`64 × 3 = 192 ≤ 4096` 高 ✓
7. **锚点**：`(0, 0)` 是语义原点，`x 向右、y 向下、1 单位 = 1 像素`。
   索从 `(0, 0)` 向**右**延伸，最远到 `x = +232`（右端留 8px 余量）。
   垂直方向只占 `y ∈ [-24, +24]`。

---

## 画面要求

**类型**：模拟型循环（`export function create(o)` → `{ update(dt), draw(p) }`），`loop: true`。

**一条索的解剖（从左到右）：**

| 区段 | 位置（x） | 内容 |
|---|---|---|
| **系结** | 0 ~ 24 | 左端收成一个小环/结，白热核心，边缘有 3~4 根细须向外勾 |
| **主体** | 24 ~ 208 | **双股缠绕**：一条白热股 + 一条紫股，以约 60px 为一个缠绕周期反向旋转；两股之间留出 1~2px 的暗缝 |
| **尾须** | 208 ~ 232 | 右端散成 4~5 根逐渐变细的须，末端发光 |

**动态：**

- **整条索**在垂直方向做低频波动：`±3px`，相位随时间推进，波长约 120px
  （**注意**：这是「索本身在晃」，不是「画面在晃」，两端要固定在 `y=0` 附近）
- **能量团**：每隔约 0.2 秒有一个亮团从右端向左端流动（朝系结方向汇聚），
  亮团经过时那一小段变粗、变白
- **高频电光**：沿索偶尔弹出 1~2 根极短的横向电弧（**长度不超过 6px**，拉伸后才不会失控）

**光：**

- 沿索主体一条 `p.glowLine`（细、强度中等）
- 系结处一个 `p.glow` 强光点
- 能量团经过时 `lights.add` 一记短光
- 整体一个弱 `p.glow` 柔光

---

## 参考文件（附件一起给 Gemini）

| 文件 | 看什么 |
|---|---|
| `docs/pixelfx-refs/ice-lance.js` | **最接近的结构**：一个横向细长、左粗右尖的定向特效，`loop: true`。照它的骨架 |
| `docs/pixelfx-refs/crit-hit.js` | `Particles` / `Lights` 的用法与分阶段写法 |
| `docs/pixelfx-refs/sunburst.js` | 光的层次：白热核心 + 外晕 + 射线 |

API 精确签名见 `docs/PROMPT-pixelfx-effects.md` 的「附录 A」，重点：

```js
p.glow(x, y, r, c, k = 0.5)
p.glowLine(x0, y0, x1, y1, r, c, k = 0.5)
p.glowRing(x, y, R, r, c, k = 0.5)
p.line(x0, y0, x1, y1, c, a)          // c 可以是 (u) => 颜色 的函数
p.dot(x, y, c, a)
p.shade(x0, y0, x1, y1, fn)           // fn(x, y, out) → 设 out.c / out.a / out.l / out.k
ps.spawn(kind, x, y, vx, vy, life, color, size, delay, flags)   // 是 spawn，不是 add
P = { SPARK:0, MOTE:1, STREAK:2, FLAKE:3, VAPOUR:4, DUST:5, STAR:6, EMBER:7, SMOKE:8, FIRE:9, BRAND:10, GRIT:11 }
ease = { inQuad, outQuad, inCubic, outCubic, outBounce, ... }
```

---

## 输出格式

输出**一个完整的 `.js` 文件**，路径写在代码块上方，不要省略成 `// ...`：

```
// 文件：_refs/pixelfx/src/fx/shadow/soul-chain.js
import { ... } from "../../core/index.js";
export const meta = { ... };
export function create(o) { ... }
```

---

## 写完之后自查（逐条回答）

1. `meta` 里有 `actor` 吗？（**必须没有**）
2. 有 `Math.random()` / `Date` 吗？（**必须没有**）
3. 索是不是从 `(0,0)` 向右延伸到约 `x=232`？有没有超出 `y ∈ [-24, +24]`？
4. **横向有没有高频细节**（密集虚线/条纹）？有的话删掉——会被拉伸成粗条。
5. 首尾帧能不能无缝接上（`loop` 循环时）？
6. 光通道调了哪些接口？逐条列出 `p.glow*` / `p.light` / `lights.add` 的位置。
7. 画面里有任何人形/手/身体吗？（**必须没有**）
8. 有没有硬编码颜色？（除 `WHITE` 外必须全部走 `o.palette`）
9. 双股缠绕的周期是多少像素？拉伸 4 倍后会不会太粗？

---

## 交付之后（我方执行，不用你管）

```bash
node tools/pixelfx-mc/export-pixelfx.mjs --slug soul_chain   # 只进 _out_check/
node tools/pixelfx-mc/export-pixelfx.mjs --slug soul_chain --all
```

烘焙器会硬校验：actor / 空帧 / 尺寸越界 / pivot 出帧 / JSON 与贴图自洽。不过就抛错。
