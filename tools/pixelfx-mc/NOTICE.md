# 许可与归属（NOTICE）

`assets/galboss/textures/fx/*.png`、`assets/galboss/pixelfx/*.json` 和
`tools/pixelfx-mc/_review/**` 都是 **pixelfx** 烘焙产物。本仓库只分发烘好的 PNG 与元数据 JSON，
**不包含 pixelfx 的 JS 源码**；pixelfx 只在开发机上作为只读源码参与离线烘焙。

## pixelfx

- 项目：<https://github.com/patch-gg/pixelfx>
- 许可：MIT License
- 版权：Copyright (c) 2026 Patch
- 版权：Copyright (c) 2026 Hugo Duprez

## 上游再归属

pixelfx 的 `NOTICE.md` 声明了下面两部分；我们的 10 个特效用到了它们，因此在这里一并保留归属。

- **Hugo Duprez / Sprite Fusion**：`src/core/sim.js` 与部分特效文件头注明改编自 Sprite Fusion 的
  开源像素 VFX（<https://www.spritefusion.com/pixel-art-vfx>），Copyright (c) 2026 Hugo Duprez，
  MIT License。本仓库用到的特效里，下列 5 个在文件头明确引用：
  - `impacts/ground-slam`（爆炸）→ `tohka_iron_fist`、`tohka_bell_wave`
  - `impacts/bullet-impact`、`impacts/crit-hit`、`impacts/parry`（impact，迸溅）→ `rei_*`、`ikuko_parry_flash`
  - `nature/quake`（explosion，地面裂开）→ `ikuko_quake`
  其余 4 个（`shadow/hex-sigil`、`holy/smite`、`shadow/eclipse`、`arcane/rune-circle`）是 pixelfx 原创。
- **Hollow Crown**：pixelfx 的 `src/core/act.js` 改编了 Hollow Crown 的技能演出时序（12 fps 步进的
  姿势、顿帧、弹簧草人目标），<https://github.com/rehan-remade/hollow-crown>，
  Copyright (c) 2026 rehan-remade，MIT License。**本项目没有使用 act/角色演出系统**，只烘特效帧；
  列出它是为了完整保留 pixelfx 的归属链。

## 不在 MIT 范围内的部分

`gallery/fonts` 里的 **Patch Pixel** 字体是 Patch 自己的作品，**不在 pixelfx 的 MIT 许可范围内**
（见 pixelfx 仓库 `gallery/fonts/NOTICE.md`）。本项目**没有使用**该字体：烘焙只调用 effects 的
`draw/create`，不涉及 pixelfx 的 gallery UI 与字体；`_review/contact.png` 上的标注文字是导出器用
pixelfx 的 `core/font.js` 里的 `mini` 像素字体（MIT 部分）现画的，不是 Patch Pixel 字体。

## 使用约束

- 每个 JSON 的 `source` 字段保留出处（repo / license / effect / seed / adaptedFrom），不要清空。
- **带 `meta.actor` 的特效不得导入 MC**：pixelfx 里这类特效会额外画一个灰色替身人（`stockActor`）
  用于预览构图，烘进贴图就是一块人形剪影。导出器会校验 `meta.actor` 并直接报错退出，绕过校验手工
  改名导入属于违规使用。
- 商业使用允许（MIT）。如果要再分发烘好的贴图，请把本文件和 pixelfx 的 MIT 许可一起带上。

## MIT License（pixelfx）

```
MIT License

Copyright (c) 2026 Patch
Copyright (c) 2026 Hugo Duprez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
