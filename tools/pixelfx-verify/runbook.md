# 正式验证 runbook（qa-runtime）

前置：Lead 说「build 已绿」；`gradlew build` exit 0；`run\mods\mcdev_probe-0.1.0-dev.jar` 在位。
写权限：只写 `run/**` 与 `tools/pixelfx-verify/**`。

## 0. 启动前自检

```
mcdev_launch_status          # 必须 {"processes":[]}
# 端口 39001/39002/25565 必须 free（pwsh 查 Get-NetTCPConnection）
```

有残留先 `mcdev_stop side="all"`。

## 1. 启动

```
mcdev_prepare projectDir=D:\game\mc\bossmod kind="both" waitJoin=true timeoutMs=600000
```
- 首次 server 生成世界 ~25s；client 进游戏 60~120s。
- 判据：`mcdev_status side=server` 通 + `mcdev_status side=client` 返回 screen=GameScreen、fps>0。
- 若 client 停在 ConnectScreen：`mcdev_ui` 看 screen，检查 `run\logs\latest.log` 是否有 `Failed to verify username`（需 online-mode=false）/ `Connection refused`（server 没起）。

## 2. 环境准备（全部走 server 控制台，避开 dev 客户端非 OP）

先拿玩家名：
```
mcdev_command side="server" cmd="list"
```
记作 `<P>`（dev 客户端通常是 `Dev`）。下面命令同理：

```
mcdev_command side="server" cmd="execute as <P> at <P> run time set day"
mcdev_command side="server" cmd="weather clear"
mcdev_command side="server" cmd="execute as <P> at <P> run tp @s 0 80 0 180 0"
```
- 平原/开阔地：可先 `execute as <P> run locate biome minecraft:plains` 或用固定坐标测试世界。
- 视角：`tp @s ~ ~ ~ <yaw> <pitch>`，yaw 0=+Z、90=-X、180=-Z、270=+X；**ground 类要低头**（pitch 40~55），**billboard 类视线略平或略抬**（pitch 0~-10）。
- 命令在**执行者前方 3 格**生成，所以镜头要能看到脚前 3 格。

## 3. 逐 slug 验证

对每个 slug：
```
mcdev_command side="server" cmd="execute as <P> at <P> run galboss fx <slug>"
# 等 1~3 秒（短特效 0.5~1s，长特效 2s；用 wait 或用 status 轮询确认 tick 前进）
mcdev_screenshot            # 返回 path/exists/size/screen
read_image <path>           # 肉眼判断
```
短特效（rei_crit_hit 0.9s / ikuko_parry_flash 1s / rei_bullet_impact 1.4s）建议连拍 2 张：0.3~0.5s 处一张（中段）、1.5s 处一张（尾巴）。

顺序与机位建议：

| 序 | slug | 机位要点 |
|---|---|---|
| 1 | tohka_iron_fist | ground，pitch 45，距离 3 格 |
| 2 | tohka_bell_wave | 同源贴地，pitch 45；45 帧截断，收尾快 |
| 3 | tohka_confinement_sigil | billboard，pitch 0，看正前方 |
| 4 | tohka_convocation_circle | ground，pitch 50（魔法阵大，可后退：先 `tp` 到稍远再执行） |
| 5 | tohka_verdict_smite | billboard，pitch 0 |
| 6 | tohka_phase2_eclipse | billboard，pitch -5；**循环**，观察 2.5s 仍在播、~2.5s(50 tick) 后消失 |
| 7 | rei_bullet_impact | billboard，pitch 0 |
| 8 | rei_crit_hit | billboard；0.9s 极短，0.3s 处截 |
| 9 | ikuko_parry_flash | billboard；1s 短，0.4s 处截 |
| 10 | ikuko_quake | ground，pitch 45~50 |

额外对照（light 通道）：
```
mcdev_command side="server" cmd="execute as <P> at <P> run time set midnight"
# 重播一个带 light 的 slug（tohka_verdict_smite 或 tohka_convocation_circle）截图
# 完成后 time set day 恢复
```

未知 slug 容错：
```
mcdev_command side="server" cmd="execute as <P> at <P> run galboss fx not_a_slug"
# 期望：命令失败并列出已知 slug；游戏不崩；客户端日志最多一条 warn
```

`galboss fx list`（确认 10 条）：
```
mcdev_command side="server" cmd="execute as <P> at <P> run galboss fx list"
```

## 4. 真实战斗

```
mcdev_command side="server" cmd="difficulty hard"
mcdev_command side="server" cmd="summon galboss:reizein_tohka ~ ~ ~2"
# 实体 id：reizein_tohka / onabuta_ikuko / tadasugawa_rei（见 entity/ModEntities.java）
# 等 boss 放技能（观察 mcdev_status server tick，或直接轮询截图）
mcdev_screenshot
```
- 若 boss 不打人：靠近、`gamemode survival`，或 `execute as <P> at <P> run summon ...` 到脚边。
- 抓到技能特效后记下 boss 名 + 触发方式。

## 5. 收尾与证据

```
mcdev_stop side="all"
mcdev_launch_status     # 必须 processes:[]
# 日志尾部：run\logs\latest.log（server 与 client 共用一个 latest.log）
Select-String -Path run\logs\latest.log -Pattern "pixelfx|Pixelfx|galboss fx|Missing|WARN|ERROR|Exception" -Tail 40
```
截图复制（probe 返回的路径一般在 `run\screenshots\` 或 `.mcdev\`）：
```
Copy-Item <probePath> tools\pixelfx-verify\shots\<slug>-<view>.png
```
命名规范：`<slug>-<1|2|3>.png`；战斗：`battle-<boss>-<n>.png`。
最后把每项结论填回 `REPORT.md` §3/§4，逐项写 通过/不通过/限制。

---

# 定点复验 v3（2026-11-07 晚，等 Lead 资源+build 绿灯后执行）

## 前置自检（不启动即可做）

```
# 1) convocation 换源是否落盘：pixelfxId=arcane/rune-circle，frames=40，duration≈2.0s，loop=false
Get-Content src\main\resources\assets\galboss\pixelfx\tohka_convocation_circle.json
# 2) 4 个放大后的 defaultScale：rei_bullet_impact=0.030 / rei_crit_hit=0.028 / ikuko_parry_flash=0.030 / ikuko_quake=0.027
# 3) jar 已 rebuild（mtime 晚于资源改动），且 GalBossCommand 含 battle 子命令
# 4) run\options.txt: pauseOnLostFocus:false 仍在（否则截图会弹暂停菜单挡画面）
```

## 机位（沿用 v2 舞台；世界已存档，平台在 y=199，测试点 z=115）

| 机位 | 传送 | pitch | 用途 |
|---|---|---|---|
| A | `tp Dev 112 202 110 0 30` | 30 | billboard（bullet/crit/parry） |
| C | `tp Dev 112 202 110 0 45` | 45 | ground（convocation / quake） |

命令均走**控制台**（dev 客户端非 OP）：`execute positioned <x> 200 <z> run galboss fx <slug>`
（命令内部再往执行者前方 3 格生成 → 效果落点 = 传入 z + 3）。

## 复验 1：convocation 换源（标准：贴地金色法阵，**不得再有射向远处的高光长条**）

```
tp Dev 112 202 108 0 45
execute positioned 112 200 105 run galboss fx tohka_convocation_circle   # 效果落 z=108，3 格距离，pitch 45 俯视
# 0.6s / 1.2s / 1.8s 各截一张（总时长 2.0s）
```
预期：完整金色符文圆环平铺地面，圆环内清晰，无竖直/条状高光。

## 复验 2：4 条放大（标准：同机位比 v2 明显更「读得清」；像素变粗属预期，不糊不抖）

机位 A（效果落 z=115，5 格）：
```
tp Dev 112 202 110 0 30
execute positioned 112 200 112 run galboss fx rei_bullet_impact   # 0.25s + 0.55s 各 1 张
execute positioned 112 200 112 run galboss fx rei_crit_hit        # 0.25s 1 张（0.95s 极短）
execute positioned 112 200 112 run galboss fx ikuko_parry_flash   # 0.25s 1 张（1.05s 极短）
```
机位 C（quake 放大后约 7.1 格宽，5 格距离刚好满屏）：
```
tp Dev 112 202 110 0 30
execute positioned 112 200 112 run galboss fx ikuko_quake         # 0.8s / 1.5s 各 1 张
```
对照：与 v2 同名截图并排看（v2 在 `shots/`，新图加 `-v3` 后缀）。

## 复验 3：boss 真实战斗（`/galboss battle`，OP2，控制台可执行）

```
summon galboss:reizein_tohka 112 200 118          # boss 放玩家正前方 6 格（平台上）
tp Dev 112 202 112 0 10                           # 玩家站好、略微俯视 boss
effect give Dev minecraft:resistance 9999 4 true  # 防死（保持 survival）
execute as Dev at Dev run galboss battle          # 把 64 格内最近 boss 拉进战斗
# 连拍：每 ~1s 一张 × 45~60s（约 50~60 张）
```
每 10s 用 `Select-String` grep 服务端日志技能播报，定位「哪一段在放技能」：
`智能|播放 pixelfx|技能|battle|Skill`（注意服务端日志是 GBK/ANSI 中文，grep 用 `播放 pixelfx` 这类 ASCII 混合串更稳）。
- 保留有特效的 2~3 张（预期：铁拳→地面猩红重击；判决铃→金色冲击波；召集→金色法阵；禁足→紫色符印；P2→日食）。
- 60s 内没等到技能：如实写「未取得」+ 日志证据（不凑数）。

## 收尾

```
mcdev_stop side="all"
mcdev_launch_status      # 必须 processes:[]
```
新截图命名：`convocation-v3-01.png`、`rei_crit_hit-v3-01.png`、`battle-v3-01.png` …
REPORT 升级 v3：**只追加「定点复验 v3」小节**，保留 v2 原结论与 bug 修复链路；5 条复验 + boss 战斗逐条写 通过/不通过/未验证。
