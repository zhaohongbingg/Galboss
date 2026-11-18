# 黑山羊仆从 v6：外部交付模型接入 + 新动画

> 2026-11-17。用户交付的 `羊首侍从_肩背后枕精修.bbmodel`（`model_identifier: goat_servant`）替换掉
> v5（`make_black_goat.mjs` 手工业产出的 27 骨骼 / 102 立方体版本）。
> v1–v5 的骨架、动画、贴图约定全部作废，**只保留 Java 侧的动画名契约**（见 §4）。

## 1. 交付模型是什么

| 项 | 值 |
|---|---|
| 工程 | `tools/blockbench/black_goat_servant_v6_gift.bbmodel`（原始文件在 `FileRecv`，已留副本入库） |
| 格式 | `.bbmodel`，`meta.model_format = geckolib_model`，Blockbench 5.2.2 + GeckoLib 插件 4.2.5 |
| 骨骼 | **62** 根（61 根带几何，0 根死骨骼），顶层 `root` |
| 立方体 | **363** 个，其中 **242 个自带旋转** |
| UV 空间 / 贴图 | 1024×1024 / 1024×1024 PNG（内嵌在工程里） |
| 包围盒 | 20.10 × **43.184** × 15.02 单位（1 单位 = 1/16 格 → 高 2.70 格） |
| 自带动画 | `animation.goat_servant.idle`（4.8s loop）、`animation.goat_servant.walk`（1.6s loop），20fps 烘焙曲线，每根骨骼几十个关键帧 |

骨架命名（全小写，`r_`/`l_` 左右）：`root → pelvis → chest → head / r_arm → r_forearm → r_hand → r_手指(三段)`、
`r_thigh → r_shin → r_foot`、`tail → tail_tip`、`r_coat_front/_back/_side(±_tip)`、`r_ear`、`l_*` 镜像。

原始交付没有独立发光层，也没有 attack/cast/hurt/death。

## 2. 怎么落地的

```powershell
# 1) 几何 + 贴图：交付 .bbmodel → geo.json / textures/entity/*.png
node import_bbmodel.mjs black_goat_servant_v6_gift.bbmodel black_goat_servant.geo.json `
  --texture black_goat_servant.png --identifier geometry.black_goat_servant

# 2) 动画：交付的 idle/walk 转换 + 手写 attack/cast/hurt/death，合成一份 animation.json
node make_goat_v6_anims.mjs

# 3) 发光遮罩：从交付贴图按色相抠出红瞳（1024²，与主贴图同 UV）
python make_goat_v6_glowmask.py

# 4) 自检
python ../../.codebuddy/skills/geckolib-character-animation/scripts/check_animation.py `
  ../../src/main/resources/assets/galboss/geo/black_goat_servant.geo.json `
  ../../src/main/resources/assets/galboss/animations/black_goat_servant.animation.json
```

## 3. **坐标系与符号：这次查到了根，以后不要再靠试**

老 README 记的是「导出器把 rotation 的 X/Y 取反」，实测是对的，但没写清**为什么**，于是容易
被 pose_tool 的画面带偏。这次直接读 GeckoLib 4.4.2 的字节码定死了两头：

| 环节 | 行为 | 依据 |
|---|---|---|
| Blockbench geckolib 插件导出 geo | 骨骼 pivot / 立方体 origin 的 **x 取反**；骨骼与立方体的 rotation **x、y 取反、z 保留** | 插件源码 `compileCube` / `compileGroup`（v4.2.5） |
| Blockbench geckolib 插件导出动画 | 关键帧 **x、y 取反、z 保留**（position 只取反 x） | 插件 `checkAndPatchKeyframeValues()` |
| GeckoLib 载入 geo | `constructBone`：`updateRotation(toRadians(-rx), toRadians(-ry), rz)`、`updatePivot(-px, py, pz)` | `javap -c BakedModelFactory$Builtin` |
| GeckoLib 载入动画 | 关键帧再 `toRadians(-v)`（x、y 两处 `dneg`） | `javap -c BakedAnimationsAdapter` |

**结论：两端各取反一次，游戏里最终角度 = Blockbench 编辑空间的角度。** 所以：
导出侧必须按上表取反（`make_goat_v6_anims.mjs` 里的 `CHANNEL_XF`），而**写姿势时按 BB 编辑空间的直觉写**：

- 朝下的骨骼（四肢）：`rotation.x` 为正 = 向前摆（-Z）、为负 = 向后
- 朝上的骨骼（chest / pelvis）：`rotation.x` 为正 = 后仰、为负 = 前倾
- `head`（吻部朝 -Z）：`rotation.x` 为正 = 抬头、为负 = 低头
- `tail`（朝 +Z）：`rotation.x` 为正 = 下压、为负 = 上翘
- `rotation.z`：右臂（-X 侧）为正 = 内收、为负 = 外展；左臂相反

⚠ **pose_tool 的坑（两处，2026-11-17 修）**：`pose_tool.mjs` 直接按 file 数值渲染，不做 GeckoLib
载入时的第二次取反；而 geo 里的骨骼位置又是 BB 的 X 镜像。要把「游戏姿态」搬进 pose_tool 的镜像空间，
旋转必须做**镜像共轭** M·R·M（M = diag(-1,1,1)）：

```
rotation → (x, -y, -z)      position → (-x, y, z)      scale → 原样
```

所以 `GOAT_PREVIEW=1` 导出的预览文件走的是共轭变换（`RAW_XF`），**不是**「原样输出」：

```powershell
$env:GOAT_PREVIEW="1"; $env:GOAT_OUT="_preview/raw.animation.json"; node make_goat_v6_anims.mjs
Remove-Item Env:GOAT_PREVIEW; Remove-Item Env:GOAT_OUT
node pose_tool.mjs ../../src/main/resources/assets/galboss/geo/black_goat_servant.geo.json `
  --anim _preview/raw.animation.json --name animation.black_goat_servant.attack --t 0.4 `
  --texture ../../src/main/resources/assets/galboss/textures/entity/black_goat_servant.png `
  --render _preview/beat_attack.png --views side
```

**踩过的坑，别再踩**：喂原值给 pose_tool 会把 y/z 轴动作画反（x 轴不受影响，因为 M·Rx·M = Rx）。
做顶撞那版时，`r_arm` 的「后张外展」被画成「内收前挤」，于是凭空报出 16~35 对穿模，
差点据此把正确的姿势改坏。判据：**对同一帧对比 `t=0` 静止基线的穿模对数**，静止也是 8 对
（交付模型颈/领口本来就互相压着），改完还是 8 对才叫「没新增」。

（`--pose` 探针同理：它测出的符号是「geo 空间」，不能直接当 BB 空间用。）

另：Blockbench 侧的正确对照方式是 `animation_timeline set_time` + `capture_screenshot`（按需渲染，
不受窗口最小化影响）；`capture_app_screenshot` 在窗口隐藏时截到的是旧帧，别用它验收。

## 4. 动画清单（Java 侧契约不变）

| 动画名 | 时长 | loop | 来源 | 与 Java 的对接 |
|---|---|---|---|---|
| `animation.black_goat_servant.idle` | 4.8s | `true` | 交付模型（转换） | predicate 循环态 |
| `animation.black_goat_servant.walk` | 1.6s | `true` | 交付模型（转换） | predicate 循环态 |
| `animation.black_goat_servant.attack` | 0.8s | `hold_on_last_frame` | 手写 | `ATTACK_WINDUP_TICKS = 8` → 接触帧 **0.4s** 与顶撞最远点对齐 |
| `animation.black_goat_servant.cast` | 1.1s | `hold_on_last_frame` | 手写 | `CURSE_CAST_TICKS = 16` → **0.8s** 双手推到最前，失明/虚弱在那一刻结算 |
| `animation.black_goat_servant.hurt` | 0.5s | `hold_on_last_frame` | 手写 | `hurt()` 触发 |
| `animation.black_goat_servant.death` | 1.2s | `hold_on_last_frame` | 手写 | `die()` 触发 |

手写四段的节拍（20fps 网格，帧 → 秒 = /20）：

- **attack（前臂三连刺拳，第三版）**：
  f0 中立 → f2(0.10s) **举拳**（双肘收到 105°，拳收到胸前）→ **f4(0.20s) 右刺**（上臂前送 78°、
  **前臂伸直 2°**）→ f5 一帧快收 → f6 拳架 → **f8(0.40s) 左刺 = 伤害结算帧**（上臂 84°、前臂 4°、
  躯干反向拧 11°）→ f9 收 → f10 拳架 → **f12(0.60s) 右刺 3** → f13 收 → f16 归位。
  同时 12 根手指骨在 f2 收成拳（80°/75°/45°），f16 松开。
  要点：**主体几乎不前移**（骨盆只前压 0.5~1.1 单位 ≈ 0.03~0.07 格），靠肘的"收 105° → 伸 2°"把拳送出去；
  腿只做轻微屈膝吸收——一蹬地就变成"冲撞"了。
  演进留档（两次都被否）：
  1. 第一版"羊角冲锋"：接触帧头压 30°+胸 -12°+骨盆 -6° 三层叠加成弯腰，羊角不领先，手臂像垂着；
  2. 第二版"低头突进"：骨盆前推 1.5 + 后腿蹬直，剪影读起来是**快速移动**而不是攻击；
  3. 第三版改成前臂刺拳连环，才把"攻击"的读法立住。
- **cast** 失明咒誓：0 → 5(0.25s) 双臂举过水平线（x=104）、抬头 → 11(0.55s) 微颤 → **16(0.8s)
  推到最前**（x=124、肘 -30）→ 22(1.1s) 手放下一半定格
- **hurt** 受击：2(0.1s) 后仰（胸 14°、头 20°、骨盆后移 0.9、燕尾前甩）→ 6 回弹 → 10(0.5s) 归位
- **death** 倒地：4(0.2s) 僵直 → 11(0.55s) 屈膝塌陷 → 19/24(1.2s) 前扑趴地定格

## 5. 渲染与贴图

- **尺寸：3 格高**。交付模型实测包围盒 **43.184 单位 = 2.699 格**（1:1，不是整数 3 格）。
  第一版照贝阿朵莉切的做法压到 `withScale(0.72f)` ≈ 1.94 格去贴合旧的 0.62×1.95 碰撞箱，
  结果被用户指出「这黑山羊不是 3 格高的吗」—— 确实压小了。现在：
  `BlackGoatServantRenderer.RENDER_SCALE = 1.1115f`（3.00 × 16 / 43.184）→ 正好 3.00 格，
  `ModEntities` 的碰撞箱同步改为 `.sized(0.9f, 3.0f)`。
  **两处必须一起改**：改渲染不改碰撞箱 → 穿天花板但打不到；改碰撞箱不改渲染 → 看得见打不着。
  想严格 1:1（2.70 格）：缩放 `1.0f` + 碰撞箱 `.sized(0.78f, 2.70f)`。
  ⚠ 3 格高的代价：进不去 2 格高的门洞/走廊。教学楼常规层高 5 格没问题，
  但如果以后要在矮房间里放它，得把尺寸压回去。
- **发光遮罩**：`make_goat_v6_glowmask.py` 从交付贴图按 `r>=150 且 r>=g*2.5 且 r>=b*2.5` 抠出红瞳
  （1168 px，峰值色 rgb(210,17,34)），输出 `black_goat_servant_glowmask.png` 1024²，供
  `AutoGlowingGeoLayer` 使用。第一版阈值 `r>=80 / 1.55` 会把领带暗红 rgb(122,31,43) 一起点亮，已修。

## 6. 自检与验收记录

| 门禁 | 结果 |
|---|---|
| `import_bbmodel.mjs` | 62 骨骼 / 363 立方体 / 0 死骨骼 / bbox 20.10×43.184×15.02 |
| `make_goat_v6_anims.mjs` | 6 段动画；骨骼名全在 geo 内；时长与循环接缝检查通过；`r_*`/`l_*` 13 对全齐 |
| `check_animation.py`（技能脚本） | **通过**：0 拼写错误、0 越界、0 Molang、20fps 网格 100% 对齐 |
| 穿模体检（`pose_tool --clip`，共轭预览） | 刺拳三拍 + 拳架帧 7~8 对 = **静止基线 8 对**（无新增） |
| 逐帧目视（`GOAT_PREVIEW` + pose_tool） | 刺拳 f2 举拳 / f4 右刺 / f5 收 / f8 左刺 / f12 右刺 + 3/4 与正面，见 `_preview/_attack_v3.png` |
| `gradlew build` | **BUILD SUCCESSFUL** |
| 实机（mcdev probe，`/summon galboss:black_goat_servant`） | 模型 / 贴图 / 红瞳发光 / 0.72 缩放正常，无 exception；idle、walk 正常播放；attack 在近距离被触发（见 `_preview/goat_v6_ingame_attack*.png`，但探针帧率抓不满 0.4s 的接触拍） |

实机截图：`_preview/goat_v6_ingame_front.png`、`goat_v6_ingame_walk_a/b.png`。

## 7. 已知差距（诚实清单）

1. **attack 的实机验证不完整**：0.8s / 接触拍 0.4s 的攻击在实机里被触发过（近距离拍摄可见抬臂与
   低头前倾），但探针截图最快 130ms 一张、且模型贴脸时构图被切，没能抓满一整段。离线逐帧 + 穿模
   基线对比是目前的验收依据。要拿准确的实机连拍，得用 OBS 级录制或给探针加"按 tick 定点截图"。
2. **cast / hurt / death 没进实机**，只在离线逐帧看过。
3. **walk 的步态方向**没做像素级 A/B（符号已按 §3 的字节码证据定死）。
4. **红瞳发光是抠色得到的**，不是交付方提供的发光层。交付方若补 `_glowmask`，直接替换文件即可。
5. **贴图 1024²**（原交付尺寸，未降采样）：一个仆从用 1024² 偏重；要省显存得同时按比例缩放 geo 里
   所有 UV（`import_bbmodel.mjs` 的 `--texture-scale` 只缩 PNG 不动 UV）。
6. **没有动画控制器**（`animation_controllers` 段）：与全项目其他角色一致，状态切换全在 Java 侧。
