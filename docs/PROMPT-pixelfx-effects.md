# 给 Gemini 的提示词：为 GalBoss 写 3 个 pixelfx 新特效

> 用法：整段复制给 Gemini（连同「硬性约束」和「API 速查」一起）。产出是 3 个 `.js` 文件，
> 直接放进 `D:\game\mc\_refs\pixelfx\src\fx\<类别>\<名字>.js` 就能烘焙。
> 提示词里所有 API 都来自上游 `docs/AUTHORING.md`，不是推测。

---

## 任务

你是像素美术特效工程师。为开源库 **pixelfx**（https://github.com/patch-gg/pixelfx）写 3 个新特效，
它们会被离线烘焙成序列帧贴图，用在 Minecraft 模组里做 Boss 技能演出。

代码风格、API、质量标准和该库现有 244 个特效完全一致。**先读下面「API 速查」，严格按它写。**

---

## 硬性约束（违反任何一条，烘焙器会直接抛错拒绝）

1. **禁止 `actor`**。`meta` 里**不能出现 `actor` 字段**（`actor: 2` 会在帧里画出灰色替身人，
   烘焙器硬校验会拒）。也不能 `import`/使用 `o.actor`、不能导出 `act`、不能有 `onBody`。
   我们要的是**纯特效本身**，画面里不能出现任何人物。
2. **单文件、零依赖**：只允许 `import { ... } from "../../core/index.js"`。不许图片、不许 npm 包。
3. **确定性**：随机数**只能**用 `new Rng(o.seed)`。禁止 `Math.random()`、`Date.now()`、`performance.now()`。
4. **时间**：`duration` 秒；烘焙按 **20 fps** 取帧，所以 `duration × 20 = 帧数`。
   - `loop: false` 的 one-shot **必须在 `t = duration` 时画面为空**（把 duration 留够，让最后一点余烬散完）。
   - `loop: true` 的必须**首尾无缝**（用「Pure」写法，画面只是 `t` 的函数）。
5. **画布与网格**：贴图上限 `2048 × 4096`。网格 `cols × rows ≥ 帧数`，且 `frameW × cols ≤ 2048`、
   `frameH × rows ≤ 4096`。所以 **帧数多就要控制画布尺寸**（下面每个特效给了推荐值）。
6. **必须真的发光**。烘焙器会分别导出「颜色」和「光」两张图，**光只来自光通道**：
   `p.glow` / `p.glowLine` / `p.glowRing` / `p.light` / `lights.add(...)`。
   只是把颜色画成白色**不会**产生光层。每个特效都必须有光。
7. **色板**：调用方传 8 阶色板（深→亮）。用 `fit(o.palette, n)` 取用，**不要硬编码颜色**。
   白热核心、带色中间调、深色只留给阴影。
8. **锚点语义**：`(0, 0)` 是锚点（落地点 / 底座 / 冲击点），**x 向右、y 向下、1 单位 = 1 像素**。
   地面特效站在 `y = 0` 上（地板环、裂纹、焦痕可以略微画到 y > 0 当透视）。
   `meta.origin` 是 `(0,0)` 在画布里的像素坐标。
9. **质量标准**：和 Sprite Fusion 的 Hugo Duprez 那批开源特效同级——1px 的描边/环/拖尾，光撑起亮度，
   大块纯色几乎总是错的；粒子有真实的加速度、弹跳、拖拽；分阶段编排（蓄力 → 释放 → 扩散 → 余韵 → 消散）；
   在 1x 尺寸下能看清焦点，形状内部不要噪点。

---

## API 速查（照这个写，别自己发明）

### 两种写法

**模拟型**（一次性：冲击、爆炸、碎片）：
```js
import { Particles, Lights, P, PF, Rng, fit, sparkle, smooth } from "../../core/index.js";

export const meta = { name: "...", desc: "...", tags: ["impact"],
  size: [96, 72], origin: [48, 56], duration: 1.2, loop: false, fps: 30,
  bg: "ground", options: { palette: [...], seed: 1 } };

export function create(o) {
  const C = fit(o.palette, 7), R = new Rng(o.seed);
  const ps = new Particles({ rng: R, ground: () => 0 });
  const lights = new Lights();
  lights.add(0, -12, 40, C[4], 0.7, 0.4);
  let t = 0;
  return {
    update(dt) { t += dt; ps.update(dt); lights.update(dt); },
    draw(p) { ps.draw(p); lights.draw(p); },
  };
}
```

**纯函数型**（循环：光环、法阵、护盾、传送门）：
```js
export function draw(p, t, o) { /* 只依赖 t，保证首尾无缝 */ }
export function drawBack(p, t, o) { /* 可选：画在角色/环的远半圈后面 */ }
```
循环用的工具：`cycle`（循环粒子）、`fly`（循环运动）、`loopNoise` / `tileFbm`（循环噪声）。

### meta 字段

| 字段 | 含义 |
|---|---|
| `name` / `desc` / `tags` | 给人看的 |
| `size` | `[宽, 高]` 画布像素 |
| `origin` | `[x, y]` 锚点在画布里的位置 |
| `duration` | 秒 |
| `loop` | 是否循环 |
| `fps` | 作者自检用，烘焙固定 20 |
| `bg` | `"ground"` / `"none"`，预览背景提示 |
| `options` | `{ palette, seed, ... }` 默认值 |

### 画布 API（`p`，坐标是局部坐标，可以是小数；`c` 是颜色号，`a` 是 0..1 透明度）

| 调用 | 画什么 |
|---|---|
| `p.px(x, y, c)` / `p.dot(x, y, c, a)` | 单像素，实心 / 抖动 |
| `p.blend(x, y, c, a)` | 单像素，真半透明 |
| `p.disc(x, y, r, c, a)` | 半透明圆盘（烟、雾）；`c`/`a` 可以是 `(d, angle) =>` 函数 |
| `p.line(x0, y0, x1, y1, c, a)` | 1px 线；`c`/`a` 可以是 `(u) =>` |
| `p.polyline(pts, c, a)` / `p.beam(x0, y0, x1, y1, w, c, a)` | 折线 / 粗胶囊光束 |
| `p.circle` `p.ellipse` `p.ring` `p.rect` `p.poly` `p.tri` | 填充形状；颜色/透明度可以是坐标函数 |
| `p.arc(x, y, r, w, a0, a1, c, a)` | 环的一段，`w` 可以按 `(u) =>` 渐细 |
| `p.shade(x0, y0, x1, y1, fn)` | 逐像素着色器：`fn(x, y, out)` 返回颜色或设 `out.c/out.a/out.l/out.k` |
| `p.glow(x, y, r, c, k)` `p.glowLine` `p.glowRing` `p.light(x, y, c, k)` | **加色光**（光层来源） |
| `p.sprite(rows, pal, x, y, {scale, a, flip, flipY, tint})` | 用字符串数组画 sprite |
| `p.text(str, x, y, c, {...})` | 像素文字（本任务用不到） |
| `p.mask` `p.outline` `p.get` | 轮廓 / 读回像素 |

### 粒子与光

```js
new Particles({ rng, ground: (x) => floorY, sparks: [head..tail], haze, onLand })
ps.spawn(kind, x, y, vx, vy, life, color, size, delay, flags)
ps.update(dt) / ps.draw(p, "back" | "front")

new Lights(); lights.add(x, y, r, color, k, life)   // k 随寿命平方衰减
new Bits({ ground }); bits.add({ kind, x, y, vx, vy, a, va, s, c, ramp, g, drag, bounce, life })
new Blazes(ps); blazes.add(x, y, life, power)
```

粒子种类 `P.*`：`SPARK`（速度拉伸的双色火花，受重力、会弹地、发光）、`MOTE`（带暗尾的拖尾）、
`STREAK`（加速虚线，表现「能量汇聚」）、`FLAKE`、`VAPOUR`（柔和半透明团）、`DUST`、`SMOKE`（三段柔盘、顶部受光）、
`FIRE`（火球冷却成烟，`PF.SMOKES` 死后转烟）、`BRAND`（燃烧块、落地回调）、`GRIT`（碎石）、`EMBER`、`STAR`（闪烁十字）。
`PF.BACK` 画在后面；`delay` 延后出现。

### 常用辅助函数（从 `../../core/index.js` 导入）

`clamp lerp seg smooth bump ease.* TAU` · `Rng rng rand` · `noise noise2 fbm loopNoise tileNoise tileFbm` ·
`fly flyV burst cycle stream` · `bolt forks star ngon trail` ·
`PAL RAMP ramp fit at blend mix scale hex rgb luma WHITE` ·
`sparkle(p, x, y, arm, c, tip, k)` 星芒 · `flare(p, x, y, L, k, ramp)` 8 芒闪光 ·
`band(p, cx, cy, r, sy, thick, alpha, ramp, keep, hot)` 倾斜平面上的环（`sy = 0.16` 贴地；`keep: g => Math.sin(g) < 0` 取远半圈） ·
`crystal(p, x, base, h, w, lean, ramp, {a, white, glint})` 冰晶尖刺 ·
`GRAVITY = 950 px/s²`

---

## 要写的三个特效

### 1. `holy/shield-aura.js` —— 优先级最高

**用途**：Minecraft 里一个 boss 给自己罩的护盾，会持续 3 秒。现在是拿「圣光柱」顶替的，完全不像盾。

**规格**
- 类型：**纯函数型循环**（`draw(p, t, o)`），`loop: true`
- `size: [176, 192]`，`origin: [88, 184]`（底部中央 = 脚底）
- `duration: 2.4`（48 帧，网格 8×6 正好）
- 画面内容：
  - 一个**正八边形能量罩**包住一个人形大小的空间（高约 120px），罩子是 1px 双层描边 + 极淡的内部半透明填充，
    **不要**画里面的人
  - 罩面上有 2~3 道沿棱边流动的高光（用 `loopNoise` 或相位偏移保证无缝）
  - 底部一圈**贴地光环**（`band(... sy ≈ 0.16)`），近半圈亮、远半圈暗
  - 6~10 颗光点沿罩面缓慢上浮（`cycle` 循环粒子）
  - 有 `p.glowRing` 或 `lights` 产出的**外发光**
- 色板：用调用方传的金色系 8 阶。白热只留给描边和最亮的流光。

**自检**：`t = 0` 与 `t = duration` 必须**逐像素完全一致**。

---

### 2. `impacts/hammer-smash.js`

**用途**：雷神之锤砸落。现在拿「钝击冲击」顶替，没有锤体。

**规格**
- 类型：**模拟型**，`loop: false`
- `size: [224, 256]`，`origin: [112, 244]`（底部中央 = 落点）
- `duration: 1.3`（26 帧）
- 编排（必须分阶段，不要一上来就炸）：
  1. **蓄力 0 ~ 0.25s**：锤子从画面上方（y ≈ -240）带着拖尾下压，画面上方汇聚能量（`P.STREAK` 向内收），
     同时地面出现一个收缩的光环预告落点
  2. **命中 0.25s**：**1~2 帧白热帧**——锤头接触地面，`flare` + 最强的一记 `lights.add`，画面整体提亮
  3. **扩散 0.25 ~ 0.6s**：地面冲击环（`band` 椭圆）、碎石（`Bits` 的 `SHARD`/`CHUNK`，会弹跳后静止）、
     放射状尘埃（`P.DUST`）、几道沿地面的短裂纹（`p.shade` 或 `p.line`）
  4. **余韵 0.6 ~ 1.3s**：尘埃上浮变淡（`P.SMOKE`）、火星冷却（`P.EMBER`）、光斑衰减
- 锤子本体：一个**侧面轮廓的方头锤**（约 60×40px），柄朝右上，锤头是深色带 1px 亮棱的方块。
  **不要画手、不要画人。** 锤子砸下时可以有 2 帧残影（`ghost` 式重画，透明度递减）。
- 必须真的落在地面上（`y = 0` 附近），冲击环压在地上。

**自检**：`t ≥ duration` 时画面完全空。

---

### 3. `impacts/fist-slam.js`

**用途**：金色巨拳砸地。现在和「判决铃冲击波」用了同一个源素材，观感重复。

**规格**
- 类型：**模拟型**，`loop: false`
- `size: [208, 176]`，`origin: [104, 164]`
- `duration: 0.9`（18 帧，短促有力）
- 编排：
  1. 0 ~ 0.12s：一个**拳头剪影**从右上方砸下（3~4 帧，速度极快），带 2 帧残影
  2. 0.12s：命中白帧 + 地面炸开
  3. 0.12 ~ 0.4s：两道向外扩散的地面冲击波（比锤击更扁、更快）、碎片、尘埃
  4. 0.4 ~ 0.9s：余烬上浮、光衰减
- 拳头：约 40×36px 的方拳，**只有拳和一小截前臂**，不要画身体。
- 整体比 `hammer-smash` **更小、更快、更贴地**，和它明显区分开。

---

## 输出格式

对每个特效，输出**一个完整的 `.js` 文件内容**，文件路径写在代码块上方：

```
// 文件：_refs/pixelfx/src/fx/holy/shield-aura.js
import { ... } from "../../core/index.js";
export const meta = { ... };
export function draw(p, t, o) { ... }
```

三个都要给全，不要省略成 `// ...`，不要写伪代码。

---

## 写完之后自查（逐条回答）

1. `meta` 里有 `actor` 吗？（**必须没有**）
2. 有没有用到 `Math.random()` / `Date`？（**必须没有**，只能用 `new Rng(o.seed)`）
3. `duration × 20` 等于多少帧？网格 `cols × rows` 够不够？贴图会不会超 `2048 × 4096`？
4. 光通道有内容吗？列出你调用 `p.glow*` / `p.light` / `lights.add` 的地方。
5. one-shot 在 `t = duration` 时是不是全空？循环是不是首尾一致？
6. 画面里有任何人形/手/身体吗？（**必须没有**）
7. 有没有硬编码颜色？（除了白色 `WHITE` 和 `hex(...)` 的极少数特例，其他都要走 `o.palette`）

---

## 附：如果只能先做一个

**先做 `holy/shield-aura.js`**。它是唯一一个「上游完全没有、且现在只能用别的东西硬顶」的特效。
另外两个（锤、拳）属于「有替代品但不够贴题」，优先级低一档。

---

# 附录 A：精确 API 签名（已逐条对照源码，可照抄）

## Painter（`src/core/painter.js`，默认参数照抄）

```js
p.px(x, y, c)                                  // 实心单像素
p.dot(x, y, c, a = 1)                          // 抖动半透明
p.blend(x, y, c, a)                            // 真半透明单像素
p.disc(cx, cy, r, c, a)                        // 半透明圆盘
p.line(x0, y0, x1, y1, c, a = 1)               // 1px 线
p.polyline(pts, c, a = 1)                      // 折线
p.beam(x0, y0, x1, y1, width, c, a = 1)        // 粗胶囊
p.circle(cx, cy, r, c, a = 1)
p.ellipse(cx, cy, rx, ry, c, a = 1)
p.ring(cx, cy, r, w, c, a = 1)                 // 圆环，w = 环宽
p.arc(cx, cy, r, w, a0, a1, c, a = 1)          // 环的一段，a0/a1 弧度
p.poly(pts, c, a = 1)                          // 多边形，pts = [[x,y], ...]
p.shade(x0, y0, x1, y1, fn)                    // 逐像素：fn(x, y, out) → 返回颜色 或 设 out.c/out.a/out.l/out.k
p.glow(x, y, r, c, k = 0.5)                    // 加色光（光层）
p.glowLine(x0, y0, x1, y1, r, c, k = 0.5)      // 加色光带（光层）
p.glowRing(x, y, R, r, c, k = 0.5)             // 加色光环：注意 R 是大半径、r 是光晕半径（光层）
p.light(x, y, c, k = 1)                        // 单点加色（光层）
p.sprite(rows, pal, x, y, opts = {})           // rows = 字符串数组
p.mask(m, x, y, c, a = 1) / p.outline(m, x, y, c, a = 1)
p.get(x, y)                                    // 读回像素
```

`c` 是颜色号（从 `fit(o.palette, n)` 取出的数组下标），`a` 是 0..1。
`c` / `a` 在多数形状里都可以传 `(d) => ...` 或 `(u) => ...` 函数。

## 运动 / 循环 / 形状辅助

```js
fly(x, y, vx, vy, t, g = 0, drag = 0)
flyV(vx, vy, t, g = 0, drag = 0)

burst(n, seed, fn)                                  // fn(rng, i) —— rng 是粒子 i 的 Rng
cycle(t, period, count, seed, life, fn)             // ★ 循环粒子。对每个存活副本调 fn(age, L, rng, i)
                                                    //   age = 当前年龄(秒)，L = 寿命，rng = 粒子 i 的 Rng（每周期相同）
                                                    //   life 可以是数字，也可以是 (rng) => 秒
stream(t, t0, t1, rate, seed, life, fn)             // 同上回调 fn(age, L, rng, i)，rate = 每秒几个

bolt(r, x0, y0, x1, y1, { jag = 0.22, depth = 5 } = {})     // 闪电折线
forks(r, path, { count = 3, len = 20, jag = 0.3, spread = 0.9 } = {})
ngon(cx, cy, r, n = 6, rot = 0)                     // ★ 正多边形顶点数组 —— 画八边形护罩用这个
trail(pos, t, n, dt)                                // 历史位置数组
sparkle(p, x, y, arm, c, tip = c, k = 0.6)          // 星芒
flare(p, x, y, L, k, ramp)                          // 8 芒闪光
band(p, cx, cy, r, sy, th, k, ramp, keep = () => true, hot = false)   // 倾斜平面上的环（sy≈0.16 贴地）
crystal(p, x, base, h, w, lean, ramp, opts = {})
```

**`cycle` 的典型用法**（循环粒子，首尾无缝）：

```js
cycle(t, duration, 8, o.seed, 1.2, (age, L, g, i) => {
  const u = age / L;                       // 0..1 生命周期
  const x = Math.cos((i / 8) * TAU) * 30;  // 固定相位
  const y = -20 - u * 40;                  // 上浮
  p.light(x, y, C[5], (1 - u) * 0.6);
});
```

## 三个容器类的真实方法（运行时验证过，别用错）

```js
// sim.js 的 Particles（index.js 显式导出的是这个，覆盖了 particles.js 里的同名类）
new Particles({ rng, ground: (x) => floorY, sparks: [...], haze, onLand })
  .spawn(kind, x, y, vx, vy, life, color, size, delay, flags)   // ★ 是 spawn，不是 add
  .count() / .update(dt) / .draw(p, "back" | "front")

new Bits({ ground })   .add({ kind: B.SHARD, ... })  / .count() / .update(dt) / .draw(p)
new Lights()           .add(x, y, r, color, k, life) / .update(dt) / .draw(p)
new Blazes(ps)         .add(x, y, life, power)
```

> ⚠️ `Particles` **没有 `add` 方法**（那是 `Bits` / `Lights` 的）。粒子只能走 `spawn(...)`。

## 常量（**是数字常量，不是字符串**）

```js
P  = { SPARK:0, MOTE:1, STREAK:2, FLAKE:3, VAPOUR:4, DUST:5, STAR:6, EMBER:7, SMOKE:8, FIRE:9, BRAND:10, GRIT:11 }
PF = { SMOKES: 1, BIG: 2, BACK: 4 }
B  = { SHARD: 0, CHUNK: 1, DRIP: 2 }
```

用法：`ps.spawn(P.SPARK, x, y, vx, vy, life, color, size, delay, PF.BACK)`；
`bits.add({ kind: B.SHARD, x, y, vx, vy, a, va, s, c, ramp, g, drag, bounce, life })`。

## `meta.options.palette` 的写法

色板是**深 → 亮 的 8 阶十六进制数组**（也接受任意长度，用 `fit(o.palette, n)` 重采样）：

```js
export const meta = {
  // ...
  options: { palette: GOLD, rays: 8, seed: 3 },   // 也可以直接写字面量数组
};
// 同一个类别里通常有 _lib.js 放共用色板，例如 src/fx/holy/_lib.js：
export const GOLD = ["#3b1d2a", "#7a3a2c", "#c4652e", "#f0a040",
                     "#ffd25e", "#fff0a0", "#fffbe0", "#ffffff"];
```

**不要硬编码颜色**。要 `import { fit } from "../../core/index.js"`，然后 `const C = fit(o.palette, 7)`，
用 `C[0]`（最暗）到 `C[6]`（最亮）。

---

# 附录 B：两个决定的答复

## 1. 配色：不用选，全部走 `o.palette`

三个特效**都不要在代码里定死颜色**。色板由调用方（`manifest.mjs`）传进来，我们会在那边统一用
**圣光金色**（`#3b1d2a → #7a3a2c → #c4652e → #f0a040 → #ffd25e → #fff0a0 → #fffbe0 → #ffffff`）。

所以你要做的只有两件事：
- 用 `const C = fit(o.palette, 7)` 取色，按「暗 → 亮」的语义用（`C[0]` 阴影，`C[6]` 白热核心）
- 光通道用 `C` 里的亮色 + `k` 控制强度

这样同一份代码换色板就能变成雷电蓝白或猩红，不用改代码。

## 2. 输出顺序：先只给 `holy/shield-aura.js`

理由：烘焙器对格式有硬校验（空帧、尺寸越界、pivot 落在帧外、循环接缝），**先跑通一个**能最快暴露
格式问题。等这一个进了烘焙器、我确认通过，你再给另外两个——避免三个都按同一个错误前提写完。

所以：**这次只输出 `holy/shield-aura.js` 一个完整文件**，不要附带另外两个。
