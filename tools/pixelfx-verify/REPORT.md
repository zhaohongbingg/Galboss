# pixelfx 运行时验证报告（qa-runtime）

> 版本：**v2（2026-11-07 20:20，修复后复测完成）**
> **结论先行：渲染管线通过（10/10 slug 在客户端真实出画，命令链路、S2C、ground/billboard 朝向、light 加色、
> 日志无 pixelfx 报错均通过）；真实 boss 战斗技能特效「未取得」；另发现 3 项资源侧/组合限制（不影响管线）。**
> 首轮（v1）曾因两处 `.png` 路径 bug 完全不出画，两处均已修复并复测，链路完整记录在 §4。

## 0. 测试环境（实测）

| 项 | 值 |
|---|---|
| 项目 / 版本 | D:\game\mc\bossmod，MC 1.20.1 / Forge 47.4.10（official mappings） |
| JDK / Gradle | JDK 17（C:\Program Files\Java\jdk-17）/ Gradle 7.6.4 wrapper；`JAVA_HOME` 由 Lead 临时 patch 的 `gradlew.bat` fallback 提供 |
| 启动方式 | `mcdev_prepare` → `gradlew runServer`（25565）+ `gradlew runClient`（离线身份 `Dev`）；probe 39001/39002 |
| 客户端 | pid 5300，join 成功；`fps≈100~120`，**帧缓冲 854×480**（GUI 427×240，guiScale=auto） |
| options.txt | renderDistance=12、graphicsMode=1(fancy)、particles=0(all)、ao=true、gamma=0.5、fov=0、fullscreen=false、resourcePacks=[]、**pauseOnLostFocus=false（本次验证中由我改，否则 probe 截图会因失焦弹暂停菜单）** |
| 光影 | **无 shader / 光影 mod**，原版管线 |
| server | 世界 `world`，tick 正常推进，全程无崩溃、玩家未掉线 |
| 测试舞台 | 自建平台 `fill 96 199 96 128 199 smooth_stone` + 上方清空；测试点 (112,200,115)，观察点 (112,202,110)/(112,200,112)/(112,205,108)；`time set day`+`gamerule doDaylightCycle false`+`weather clear` |
| 日期 | 2026-11-07 18:10–20:20（含 preflight、两轮修复、复测） |

## 1. 命令链路（**通过**）

| 用例 | 结果 |
|---|---|
| `galboss fx list`（控制台 `execute positioned … run`） | ✅ 返回 10，与契约 §3 一致 |
| `galboss fx <slug>`（10 个逐个执行） | ✅ 全部 result=1，服务端日志逐条 `已在 (112.50, 200.00, 115.50) 播放 pixelfx <slug>（scale=1.000x, yaw=0.000°…）` |
| 未知 slug `galboss fx not_a_slug` | ✅ result=0（sendFailure），不崩、无异常（首轮即验证） |
| 控制台代玩家执行 `execute as Dev at Dev run …` | ✅ 可用（dev 客户端非 OP；本报告所有命令均走控制台） |
| S2C 广播 → 客户端渲染 | ✅ 客户端收到并出画（见 §3） |

## 2. 10 个 slug 逐项结论

方法与机位：均为 `execute positioned <pos> run galboss fx <slug>`（命令在**执行者脚部高度、水平视线前方 3 格**生成），
截图时机按 Lead 提供的「最亮帧」表手动控制；每条至少 1 张可读截图，关键项 2~3 张。

| # | slug | anchor | 结论 | 截图 | 说明 |
|---|---|---|---|---|---|
| 1 | tohka_iron_fist | ground | **通过** | `tohka_iron_fist-01-flash.png`、`-02-dust.png` | 猩红+白像素冲击环，平贴地面、以生成点为中心，0.3~1s 有清晰像素块与闪光；不悬空、不埋地 |
| 2 | tohka_bell_wave | ground | **通过** | `tohka_bell_wave-01.png` | 金色+紫色环状冲击波贴地扩散，像素边缘硬（最近邻），未出紫黑棋盘格 |
| 3 | tohka_confinement_sigil | billboard | **通过** | `tohka_confinement_sigil-01.png` | 紫色六芒/辐条符印，**竖直立起、正面朝相机**（不随俯仰倾倒），底部锚在生成点附近 |
| 4 | tohka_convocation_circle | ground | **通过（有资源缺陷）** | `tohka_convocation_circle-01-beam-artifact.png` | 法阵本体贴地可见，但贴图内含**竖直虚线伪影**，渲染成"法阵 + 一条射向远处的高光长条"；贴图侧问题，见 §5-① |
| 5 | tohka_verdict_smite | billboard | **通过** | `tohka_verdict_smite-01-day.png`、`-02-midnight-light.png` | 1.1s 帧为贴地小光斑（与贴图该帧内容一致）；**午夜对照里 light 通道明显自发光**（暗处金光+漂浮光点） |
| 6 | tohka_phase2_eclipse | billboard(loop) | **通过** | `tohka_phase2_eclipse-01.png`、`-02-still-looping-2.5s.png` | 黑色日食圆盘+紫色光晕射线，竖直朝相机；1.0s / 2.5s / 3.7s 持续循环（符合 loop 语义）；**`/galboss fx` 播它不会自动结束**（预期限制，见 §5-④） |
| 7 | rei_bullet_impact | billboard | **通过（画面偏弱）** | `rei_bullet_impact-01.png` | 琥珀色零星像素（弹着点），朝向正确；但贴图内容仅 231 个不透明像素/120×73 帧，屏幕观感很弱，见 §5-② |
| 8 | rei_crit_hit | billboard | **通过（画面偏弱）** | `rei_crit_hit-01.png` | 琥珀色散点爆发，朝向正确；贴图内容稀疏（1322/25152 像素），观感弱，见 §5-② |
| 9 | ikuko_parry_flash | billboard | **通过（画面偏弱）** | `ikuko_parry_flash-01.png` | 天青色短促像素线/点，朝向正确；内容稀疏（445/15800 像素），观感弱，见 §5-② |
| 10 | ikuko_quake | ground | **通过** | `ikuko_quake-01-crack.png` | 天青色地面裂谷/冲击线，**平贴地面**；贴图为扁长 263×82，低视角下接近一条线（俯视更清楚），见 §5-③ |

补充：修复后**没有再出现**「找不到贴图」「FileNotFoundException」「紫黑棋盘格」「实心色块」；所有特效均未遮挡 HUD/准星。

## 3. 判定清单（逐项）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 出现像素序列帧（帧间有变化、非静态图） | **通过** | 同一 slug 不同时刻截图内容不同；提取贴图逐帧与画面一致（`shots/frames/`） |
| 无紫黑/洋红棋盘格 | **通过** | 修复后 10 个 slug 均无 missing texture；日志无 FileNotFound |
| 非实心色块 / UV 未串帧 | **通过** | 像素细节清晰，相邻帧网格拼接处无相邻帧渗色（如 iron_fist 环、bell 环） |
| ground 贴地 | **通过** | iron_fist/bell_wave/quake/convocation 均平铺在 y=200 平台表面，与地面共面、无悬空 |
| billboard 竖直朝相机 | **通过** | sigil/eclipse/verdict 立起且只绕 Y 轴，视角俯仰不倾倒 |
| light 加色在暗处发光 | **通过** | 午夜 `time set midnight` 下 verdict_smite 的金色自发光明显（对比白天同帧） |
| 不遮挡画面/GUI | **通过** | 特效只在世界层，HUD、准星、血条正常；近距也没有铺满整屏 |
| loop 语义 | **通过（带限制）** | eclipse 在 1.0/2.5/3.7s 持续循环；命令播 loop 不会自动结束（§5-④） |
| 未知 slug 容错 | **通过** | result=0 + sendFailure，不崩 |
| 日志 warn/exception/纹理缺失 | **通过** | 客户端日志（`launch-client-1794049744116.log`）grep `[pixelfx]|FileNotFoundException|MissingTexture`：**0 条**；仅有已知的 latest.log 占用告警与 JDK17 netty 告警 |
| 专用服务器稳定 | **通过** | 全程 ~2h（含多次重启）无崩溃、无异常退出；`mcdev_status server` 持续可用 |
| 真实 boss 战斗技能特效 | **未验证（限制）** | 见 §5-⑤ |

## 4. 缺陷与修复链路（首轮完全不出画 → 修复 → 复测）

### 缺陷 #1（阻断）：`ResourceManager` 存在性检查用了无扩展名 id
- 首轮现象：命令成功、服务端广播成功，客户端**画面无任何特效**；日志
  `[pixelfx] 找不到贴图 galboss:textures/fx/tohka_iron_fist，相关特效将被跳过`。
- 根因：`PixelfxAssets.bindTexture()` 拿 `galboss:textures/fx/xxx`（无 `.png`）去 `ResourceManager.getResource()`；
  ResourceManager 要**精确文件名**，而 `TextureManager` 才收无扩展名。资源本身没问题（src 与 build/resources 都在、PNG magic 正常）。
- 修复（Lead）：检查路径补 `.png`。证据：`shots/pre-fix-no-texture-1..3.png`（空平台）。

### 缺陷 #2（阻断）：`TextureManager` 仍用无扩展名 id
- 复测现象：出画了，但是**洋红/黑色四边形**（missing texture）；日志
  `java.io.FileNotFoundException: galboss:textures/fx/tohka_iron_fist at PixelfxAssets.bindTexture(PixelfxAssets.java:201)`。
- 根因：存在性检查修好后，`getTexture(location)`/`setShaderTexture(0, location)` 仍传无扩展名 → `SimpleTexture.load` 按精确路径 open 失败。
- 修复（Lead）：`pngLocation = path + ".png"` 四处统一（检查 / getTexture / FILTERED_TEXTURES / setShaderTexture）。
- 复测：**10 个 slug 全部正常出画**，日志 0 条 pixelfx 错误。证据：`shots/pre-fix-no-texture-*`（前）→ 各 slug 截图（后）。

### 环境坑（已修，供复用）
1. `mcdev_prepare` 派生的 gradlew 缺 `JAVA_HOME`（PATH 里只有 Java 8）→ Lead 给 gradlew.bat 临时 fallback，**验证完需还原**。
2. `run\eula.txt` 缺失（`eula=true`）；`run\server.properties` 预置 `online-mode=false` 等（dev 离线客户端）。
3. client 的 join 端口：`mcdev_prepare` 默认 `joinPort=25566`，而 dev 服务器 `server-port=25565` → 必须显式 `joinPort=25565`，否则 `Connection refused` 停在 DisconnectedScreen。
4. `pauseOnLostFocus=true` 时 probe 截图/命令会因窗口失焦弹暂停菜单，挡住画面 → 已在 `run\options.txt` 改为 `false`（仅 run 目录，不入库）。
5. server 与 client 共写 `run\logs\latest.log`，client 启动会报「Unable to delete latest.log…」，client 侧日志以 `.mcdev\launch-client-*.log` 为准。

## 5. 实际限制与未覆盖项（不编造通过）

1. **tohka_convocation_circle 贴图含竖直虚线伪影**：`tohka_convocation_circle.png` 的帧里有一条从法阵向上的 1px 虚线（并集包围盒把某帧的细线纳入），贴地后渲染为"法阵 + 一条高光长条"。证据：`shots/frames/tohka_convocation_circle-f30.png`（贴图原帧）+ `tohka_convocation_circle-01-beam-artifact.png`（实机）。属**资源侧**（导出器/裁剪），不是渲染器 bug；渲染对贴图是忠实的。
2. **短特效视觉偏弱**：`rei_bullet_impact`（帧内仅 231 个不透明像素）、`rei_crit_hit`（1322/25152）、`ikuko_parry_flash`（445/15800）在 854×480、5 格距离下只看到稀疏像素点，不像"爆发/闪光"。逐帧提取贴图确认**画面与贴图内容一致**（渲染忠实），弱是资源内容/包围盒留白造成的观感问题，建议 fx-pipeline 检查这三条的导出裁剪与内容密度。
3. **ikuko_quake 扁长**：贴图 263×82（宽>3 倍高），低视角（pitch 30 左右）下几乎是一条地面线；俯视机位能看到裂谷形状。物种本身贴地正确。
4. **eclipse loop + `/galboss fx` 组合**：命令调用 `PixelfxFx.play`（lifeTicks=0），对 loop 元数据=永不自动结束；实例会一直留在该位置（>128 格不渲染但计时保留，走近仍可见）。属命令/loop 组合的预期限制，不是 bug；但会串场影响后续截图（我通过重启客户端清场）。
5. **真实 boss 战斗技能特效未取得**：`summon galboss:reizein_tohka`（近距离、difficulty hard、玩家 survival+抗性）后观察 16 秒 + 第二次 5 秒，boss 站立未施放技能；`galboss:onabuta_ikuko` 近战/受击期间也未在观测窗内触发 `ikuko_parry_flash`（该 hook 在 `doHurtTarget` 命中玩家时触发，需要它真的打中）。因此**没有"boss 自然施法 → 特效"的截图**，标为未验证；若要补，需要可控的技能触发手段（命令强制技能/延长观察/降低 AI 门槛）。
6. 本机是 dev 环境（离线身份、`run/` 目录、临时 JAVA_HOME fallback、854×480 小窗），不等同发布环境；未测真实光影/高分辨率/其他 GPU 驱动。
7. 美术构图/风格是否契合角色技能（"电影级"验收）不在本报告范围；本报告只判渲染管线正确性、是否出现、锚点/朝向/发光/日志/稳定性。

## 6. 证据文件

| 文件 | 内容 |
|---|---|
| `shots/tohka_iron_fist-01-flash.png` | ground：猩红冲击环平贴地面（0.3~1s 最亮帧） |
| `shots/tohka_iron_fist-02-dust.png` | ground：烟尘尾巴 |
| `shots/tohka_bell_wave-01.png` | ground：金色环状冲击波 |
| `shots/ikuko_quake-01-crack.png` | ground：天青裂谷/冲击线贴地 |
| `shots/tohka_convocation_circle-01-beam-artifact.png` | ground：法阵 + 竖直虚线伪影 |
| `shots/tohka_confinement_sigil-01.png` | billboard：紫色符印竖直朝相机 |
| `shots/tohka_verdict_smite-01-day.png` / `-02-midnight-light.png` | billboard + 暗处 light 自发光对照 |
| `shots/tohka_phase2_eclipse-01.png` / `-02-still-looping-2.5s.png` | billboard loop：日食持续循环 |
| `shots/rei_bullet_impact-01.png` / `rei_crit_hit-01.png` / `ikuko_parry_flash-01.png` | billboard：短特效（内容稀疏） |
| `shots/pre-fix-no-texture-1..3.png` | 修复前完全不出画（bug 证据） |
| `shots/battle-reizein_tohka-summoned-no-skill-fx.png` | 真实战斗尝试：boss 在场但未施法 |
| `shots/frames/*.png` | 从贴图逐帧提取的 ground truth（用于核对 UV/帧选择/观感） |

---

## 7. 定点复验 v3（2026-11-07 19:23–20:00，本小节为追加，v2 结论与 bug 链路保留）

> 触发：Lead 换源 convocation + 放大 4 条特效 + 新增 `/galboss battle` 调试命令后，要求定点复验。
> 环境同 v2：854×480、无光影、fancy、pauseOnLostFocus=false；实机时间 19:23 起，server/client 均正常。

### 7.1 前置自检（全部符合）

| 项 | 实测 |
|---|---|
| convocation 换源 | `pixelfxId=arcane/rune-circle`、frameW×frameH=118×96、**frames=40**、cols×rows=17×3、**loop=false**、pivot=(59,76)、anchor=ground、defaultScale=0.02；贴图 mtime 19:23:18、`_light.png` 19:23:19 |
| 4 条 defaultScale | rei_bullet_impact=**0.030**、rei_crit_hit=**0.028**、ikuko_parry_flash=**0.030**、ikuko_quake=**0.027** |
| build | jar 3,937,438 B / mtime 19:23:52；`build/resources/main/.../tohka_convocation_circle.json` 已是 rune-circle 新内容 |
| `/galboss battle` | 可用：控制台 `execute as Dev at Dev run galboss battle` → result=1，日志 `[FS] Reizeiin Tohka entered battle with 1 participant(s), HP x1.0` |

### 7.2 复验 1：convocation 换源（**通过**）

- 机位 C：(112,202,104) yaw0 pitch45；效果落点 (112,200,108)，3 格近距俯视。
- 结果：**金色透视法阵圆环平贴地面**，环体+符文+上升光点清晰，**没有任何竖直光柱/射向远处的高光长条**（v2 的光带伪影消失）。
- 0.6s / 1.2s 两张可见完整法阵；~2.0s（40 帧 one-shot）后自动消失，**不再常驻**。
- 证据：`shots/tohka_convocation_circle-v3-00-rune-circle.png`、`-v3-01-rune-circle.png`、`-v3-02-ended.png`（结束后空地）。
- 限制：贴图本身为「透视法阵（斜视椭圆）」，贴地后从正上方看会略显扁平，属源素材透视，不是渲染问题。

### 7.3 复验 2：4 条放大（**通过**）

同机位（A=(112,202,110) yaw0 pitch30，效果落 z=115）与 v2 对比：

| slug | 旧 scale → 新 | 结论 | 证据 |
|---|---|---|---|
| rei_bullet_impact | 0.020 → 0.030 | **通过**：琥珀像素点比 v2 更粗更易读（v2 几乎看不清），仍偏稀疏但轮廓可辨 | `shots/rei_bullet_impact-v3-01/02.png` |
| rei_crit_hit | 0.020 → 0.028 | **通过**：琥珀爆发范围明显变大、像素更粗，能读出「爆发」而非零散点 | `shots/rei_crit_hit-v3-01.png` |
| ikuko_parry_flash | 0.020 → 0.030 | **通过**：天青刀光弧线明显放大（v2 是细小断点，现在是两道可辨认弧线） | `shots/ikuko_parry_flash-v3-01.png` |
| ikuko_quake | 0.021 → 0.027 | **通过**：地面裂谷/冲击线更宽更清楚（约 350px 宽），贴地正确 | `shots/ikuko_quake-v3-01/02.png` |

- 像素变粗（最近邻放大）属预期；未出现模糊（非双线性）、未出现帧间抖动/闪跳；四条均无穿地/悬空。
- 仍存在的观感限制：前三条贴图内容本身稀疏（v2 §5-② 已记录），放大改善可读性但没有改变「像素点少」的本质。

### 7.4 复验 3：boss 真实战斗特效（**通过，取得 2 张有效证据**）

流程：`difficulty hard` → `summon galboss:reizein_tohka 112 200 118` → `tp Dev 112 202 112 0 10` → `effect resistance` → 控制台 `execute as Dev at Dev run galboss battle` → 每 0.35~1s 连拍 40+ 张（含中途 re-tp 复位镜头）。

- **已取得**：
  1. `battle-v3-00-entered-battle-gold-circle.png`：进入战斗瞬间，boss 脚下**金色法阵圆环 + 金色光点**（Student council, assemble → `TOHKA_CONVOCATION_CIRCLE`），boss 血条「冷泉院 桐香」出现。
  2. `battle-v3-01-convocation-circle-in-battle.png`：真实战斗中金色法阵完整铺在 boss 脚下（与 Judgement bell 播报同帧）。
  3. `battle-v3-02-sigil-and-sparks.png`：Iron fist sanction / Judgement bell 同帧，boss 脚下**紫色符印残余**、四周金色像素火花（地面冲击类像素特效），可确认战斗中确实在走 pixelfx 通道。
- **说明**：战斗内技能播报为聊天行（`Judgement bell — silence, everyone.` / `Iron fist sanction.` / `Confinement order.` / `Student council, assemble.` / `Commencing disciplinary inspection.`），可作为「战斗逻辑确实在施法」的交叉证据；boss 在观测窗内多次施法（远超 1 次），但多数帧被聊天行/技能位移/镜头被击飞遮挡，故只保留可读的 3 张。
- **限制**：boss 会在战斗中途消失（本次两次观测均在 ~60~90s 内 boss 实体不存在，疑似被玩家/场地击败或 AI 结束战斗），未取得「P2 日食过场」与「判决铃金色冲击波」的独立清晰帧；`/galboss battle` 的日志行 `64 格内没有 GalBoss 类 boss（排除 galboss:reizein_tohka）` 出现在**未召唤 boss 时**（符合预期：只是提示，不是排除该实体）。
- **未取得**（如实记录，不凑数）：`tohka_iron_fist` 地面猩红重击的清晰战斗帧、`tohka_bell_wave` 金色冲击波的清晰战斗帧、P2 日食。

### 7.5 v3 日志与稳定性

| 项 | 结果 |
|---|---|
| client `launch-client-1794050794965.log` grep `[pixelfx]|FileNotFoundException|MissingTexture` | **0 条** |
| server 异常 | 仅 19:24:53 的 JDK17 mixin/netty 已知告警，无 mod 异常；`[FS] Reizeiin Tohka entered battle with 1 participant(s)` 正常 |
| 服务端稳定性 | 全部复验期间无崩溃、无断线；`mcdev_status` 正常 |
| 收尾 | `mcdev_stop side="all"`，`mcdev_launch_status`={"processes":[]}，无 java 残留 |

### 7.6 v3 结论

- **core 验收点通过**：convocation 换源后贴地法阵不再出现竖直高光长条。
- 4 条 scale 放大幅度生效、可读性提升，无模糊/抖动（像素变粗属预期）。
- 真实 boss 战斗经 `/galboss battle` 成功触发，取得 3 张含 pixelfx 的战斗证据；战斗链路（S2C → 客户端世界层渲染）在真实战斗中复现，日志无任何 pixelfx 错误。
- **遗留**（不阻断，供后续）：战斗帧易被聊天行/位移遮挡，若要「技能特效集合图」需要更高机位/隐藏聊天 HUD 的录制方案；3 条短特效内容密度仍是资源侧问题。
