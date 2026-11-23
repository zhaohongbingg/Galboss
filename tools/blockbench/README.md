# GalBoss 模型流水线

三个 boss（冷泉院桐香 / 只須川礼 / オナブタ郁子）的 GeckoLib 模型、贴图、动画都从这里生成。
**改模型不要手改 `assets/galboss/geo/*.geo.json`**，改这套文件重跑，否则下次重建会被覆盖。

## 文件

| 文件 | 作用 |
|---|---|
| `pipeline.mjs` | 主流水线。角色骨骼、第二层、武器几何、贴图色板全在这里定义 |
| `builder_body.js` | 送进 Blockbench 执行的建模脚本（读 `pipeline.mjs` 注入的 SPEC） |
| `anims.mjs` | 11 段动画的关键帧定义（idle/walk/hurt/attack/charge/dash/combo/cast/guard/rage/phase/shoot） |
| `make_anims.mjs` / `verify_anims.mjs` / `export_anims.mjs` | 建动画 / 渲染验证 / 导出并同步到三个角色 |
| `_weapon_tex.ps1` | 生成 64×128 贴图：皮肤上半张 + 武器色板下半张 + 手臂背面镜像补全 |
| `_mcp_client.mjs` / `_mcp_run.mjs` | Blockbench MCP 客户端（`localhost:3000/bb-mcp`） |
| `_read_state.js` | 从 Blockbench 读回贴图与武器几何（见下方"手调后回写"） |
| `make_gun_geo.mjs` | 手建礼的手枪：生成 `geo/rei_gun.geo.json` + 自产 128×128 色板贴图（不用 TACZ 资产） |
| `make_katana_geo.mjs` | 手建郁子的武士刀：生成 `geo/ikuko_katana.geo.json`（贴图取 `ref/blade.png`） |
| `geo_to_item_model.mjs` | **武器核心**：geo → 原版物品模型（uv 归一化 / X 镜像 / 握把居中 / element 旋转 / display 预设）。`TEX_SIZE` 支持 `512` 或 `128x64`（非正方形图集） |
| `verify_item_model.mjs` | 物品模型离线体检：uv 空间 / 逐面采样贴图 / element 旋转合法性（angle 必须 0·±22.5·±45） |
| `import_bbmodel.mjs` | **外部交付模型**：`.bbmodel` → `geo/*.geo.json` + `textures/entity/*.png`（复刻 Blockbench 的 Bedrock 导出规则，见下方「外部交付的模型」） |
| `make_goat_v6_anims.mjs` | **黑山羊 v6 动画生成**：读交付 `.bbmodel` 的 idle/walk 并转换，加手写 attack/cast/hurt/death，合成 `animations/black_goat_servant.animation.json`。`GOAT_PREVIEW=1` + `GOAT_OUT=` 可导出「未取反」版本喂 pose_tool（见 `BLACK-GOAT-SERVANT-V6.md` §3） |
| `make_goat_v6_glowmask.py` | 黑山羊 v6 发光遮罩：从交付 1024² 贴图按色相抠出红瞳 → `*_glowmask.png` |
| `pose_tool.mjs` | **动画迭代工具**：不启动 Blockbench，直接对 geo + animation 求值 → 渲染任意时刻姿态 PNG，并做立方体级「穿模体检」。⚠ 它不做 GeckoLib 载入时的第二次取反，带 x/y 旋转的动画要用 `GOAT_PREVIEW` 版本才看得对 |
| `clean_weapon_models.mjs` | 武器 geo 清零展示旋转 + 删除空骨骼 |
| `fix_weapon_bone_names.mjs` | 武器骨骼去重名（对照角色全部骨骼名） |

## 骨架（v2，20 根骨骼 / 20 个立方体）

三人共用同一套骨架。v2 相对 v1 修掉了三处结构性缺陷（详见 `pipeline.mjs` 的 `BASE_GROUPS` 注释）：

1. **`body` / 双腿真正挂在 `root` 之下。** v1 里 `builder_body.js` 把 `'root'` 当字符串喂给
   `addTo()`，于是 `body`、`right_leg`、`left_leg` 全被拍平到顶层，`root` 是个空骨骼 ——
   转 `root` 完全没反应（`hurt` / `phase` 因此是空动画），转 `body` 只带动躯干、腿不动（腰部裂开）。
2. **手臂在肘、腿在膝各分两段**（新增 `right_forearm` / `right_shin`，以及 `*_hand` / `*_foot` 空骨骼）。
   分段只切 UV、不改变体积，静止姿态与 v1 逐像素一致；`_backup_before_rig_v2/` 里留了 v1 的 geo 可以比对。
3. **`weapon_anchor` 改挂到 `right_hand` 之下**，跟着肘与腕一起动（pivot 不变，静止时武器位置与 v1 相同）。
   它现在写在 `pipeline.mjs` 的 `BASE_GROUPS` 里 —— v1 时代它是事后由 `add_weapon_anchor.mjs` 塞进去的，
   重跑一次 `make` 就会被 `RESET_JS` 清掉，礼的枪 / 郁子的刀会整把消失。

```
root                       [0,0,0]      空骨骼，全身总闸（绕脚底旋转）
└── body                   [0,12,0]     骨盆，带动四肢
    ├── head               [0,24,0]
    │   └── hair_bangs / hair_side_r / hair_side_l / hair_back   （空骨骼，只占挂点）
    ├── right_arm [5,22,0] ── right_forearm [5,18,0] ── right_hand [5,12.5,0] ── weapon_anchor [6,12,0]
    ├── left_arm [-5,22,0] ── left_forearm [-5,18,0] ── left_hand [-5,12.5,0]
    ├── right_leg [2,12,0] ── right_shin [2,6,0] ── right_foot [2,0,0]
    └── left_leg [-2,12,0] ── left_shin [-2,6,0] ── left_foot [-2,0,0]
```

四条 `hair_*` 是**空骨骼**（不带立方体，外观不变），以后要加发丝几何直接往它们身上挂。

## 常用命令

```powershell
node pipeline.mjs --check         # 离线自检：骨架层级 / 立方体重名 / UV 越界 / 四肢分段是否严丝合缝
                                  # + 动画骨骼名 / 时间范围 / 循环首尾一致性 / 同名动画时长是否三人一致。
                                  # 不连 Blockbench，改完先跑这个
node pipeline.mjs make  <角色>    # 建/重建 + 四点渲染 + 导出 geo + 保存 bbmodel（一条龙）
node pipeline.mjs rig|shot|export <角色>
node build_all_anims.mjs          # 【动画一条龙】逐个角色切项目 -> 重建动画 -> 导出 -> 统一校验
node verify_anims.mjs --char <角色>   # 渲染该角色的动画关键帧到 _preview/anim_<角色>_*.png
powershell -File _weapon_tex.ps1  # 重新生成三张贴图（会覆盖手改内容，慎用）
```

`make` 会**复用同名项目**并顺手关掉同名多余标签，不会越跑越多标签页。

改完骨架的推荐顺序：`--check` → `make 桐香` → 看 `_preview/` 四点图 → `make 另两个`；
改完动画只需 `node build_all_anims.mjs`。

## 动画：基础版 + 角色专属

三人共用一套基础动画（定义在 `anims.mjs` 的 `ANIMS`），**再按各自手里的东西覆盖几段**
（`anims.mjs` 的 `CHARACTER_ANIMS`）：

| 角色 | 手里的东西 | 专属覆盖 |
|---|---|---|
| 冷泉院 桐香 | 空手 | `shoot` —— 她的 `shoot` 不是开枪，是「禁足令」抬手指认；基础版是举枪瞄准的姿势，空手做很难看 |
| 只須川 レイ | 手枪（`ReiGunLayer`） | `shoot` 双手持枪 + 后坐枪口上跳；`combo` 从空手连击改成**枪托下砸** |
| オナブタ 郁子 | 武士刀（`IkukoKatanaLayer`） | `attack` 横斩；`combo` 二连斩；`cast` 从空手结印改成**举刀** |

两条硬约束（`--check` 会强制）：

1. **同名动画的 `len` / `loop` 三人必须完全一致。** Java 侧按动画名算 tick
   （`PURGE_CHARGE_TICKS = 16` 对应 `charge` 的 0.8s、`SUPPRESS_AIM_TICKS = 8` 压 `shoot` 内），
   所以专属版里 `len` 一律写 `ANIM_LEN.<名字>`，不要写字面量。
2. **不要在 `left_hand` / `*_foot` 上打关键帧。** 它们是空骨骼且没有子骨骼，转了不会有任何效果
   （只有 `right_hand` 下面挂着 `weapon_anchor`），`--check` 会把这种键报成错误。

武器朝向用 `right_hand` 做"转腕"（±25° 以内），细节见 `IkukoKatanaLayer` 的类注释。

导出流程：Blockbench 的 `create_animation` 与 `geckolib_export_animations` 都只作用于
**当前活动项目**，所以 `build_all_anims.mjs` 会逐个切换项目跑一遍。以前那种
"导一份再替换动画名前缀"的做法已经删掉 —— 它会把专属内容抹平。

## 贴图布局（64×128）

```
y   0 .. 63   皮肤本体（原 64×64）
y  64 .. 71   枪色板（5 块 8×8：gun_body/gun_dark/gun_steel/gun_mid/gun_sight）
y  72 .. 79   刀色板（7 块 8×8：blade_mid/blade_dark/blade_light/blade_edge/tsuka/tsuka_wrap/tsuba）
```

武器各面在色块内取 2×2 小矩形即可 —— 采样区被拉伸到整面，纯色不变形。

## 必须遵守的约定（都是踩过的坑）

1. **方块 UV 的上/下面方向**
   ```
   up   = (U+d,     V)     注意是 U+d，不是 U+d+w
   down = (U+d+w,   V)
   east = (U,       V+d)   north = (U+d,   V+d)
   west = (U+d+w,   V+d)   south = (U+d+w+d, V+d)
   ```
   写反的后果：头顶采到脖子区域、第二层头顶采到空白 → **光头 / 头顶缺覆盖**。
   验证方法：`head` 的 `up` 应该采到发色（桐香是 `#4A9F89`），`head_overlay` 的 `up` 必须不透明。

2. **第二层（overlay）UV 原点**用原版标准，别自己编：
   ```
   head2=(32,0)  body2=(16,32)  right_arm2=(40,32)
   left_arm2=(48,48)  right_leg2=(0,32)  left_leg2=(0,48)
   ```
   几何 `inflate`：帽子层 0.5，其余 0.25。

3. **头发和裙子不要自己加立方体**。这些皮肤把头发画在**第二层**、裙子画在 **body/leg 贴图**上，
   原版 Steve 双层模型贴上去就已经有了。加自定义立方体会去第一层乱采样，反而对不上。
   （`HAIR_*` / `SKIRT_*` 定义保留在 `pipeline.mjs` 里备查，但 `buildSpec` 不再使用。）

4. **`risky_eval` 的代码里不能有 `//` 注释和 `console.`** —— 这版 Blockbench 插件会直接拒绝。
   `pipeline.mjs` 的 `evalCode()` 已经自动剥注释；但用 `_mcp_run.mjs --eval-file` 跑临时脚本时要自己注意。

5. **武器握持基准**：手心在 `(-6, 12.5, 0)`，右臂立方体占 `x -8..-4 / y 12..24 / z -2..2`。
   握把（枪）/ 刀柄必须让**中心落在手心**；手臂前方的部分才可见，z > -2 的部分会埋在手臂里。
   （x 是负数：`right_arm` 在 `-X` 侧。写 +6 会落到**另一条手臂**上，见下方 `weapon_anchor` 一节。）

6. **手臂背面贴图**：原始皮肤只画了一半。`_weapon_tex.ps1` 会做镜像补全（水平→垂直→中心依次尝试），
   但如果区域本身不透明像素不到一半，补不满 —— 这种情况在 Blockbench 里手动补，然后按下方流程回写。

7. **立方体不能重名**（`builder_body.js` 的 `makeCube` 是按名字查重复用的）。
   踩过的坑：四肢第二层的立方体曾经沿用第一层的名字，结果第二层**直接覆盖**掉第一层那块 ——
   每根骨骼只剩一块、被 `inflate 0.25` 撑大，第二层彻底消失（导出体积从 37.7KB 掉到 24.4KB）。
   第二层一律用 `<骨骼名>_overlay`。这条已经在 `--check` 里拦下来。
   同理，`Group` 之间也不能重名。

8. **四肢的 UV 按"上段 / 下段"对半切**：四个侧面各取一半，端面（up/down）两段都给（另一段是
   肘 / 膝处的断面，静止时被互相遮住，弯下去才露出来）。`splitV` / `limbFaces` 负责这件事，
   `--check` 会验证两段拼起来与 v1 的原始矩形完全一致。

9. **`session.json` 过期不用手动删**。Blockbench 每次重启都会换会话，而旧版 `_mcp_client.mjs`
   读到过期的 id 会直接抛 `Session not found`。现在 `initialize()` 会自动丢掉旧 id 重新握手。

## 导出器的两个"正常"行为（不要当 bug 修）

- 坐标与旋转的"表示法转换"（实测结论，比"X 取反"这句说法更准）：
  **旋转的 X 与 Y 取反、旋转的 Z 保留、position 的三个分量都保留。**
  例：`anims.mjs` 里写 `right_shin -26`，导出的 json 是 `+26`；写 `body.position [0,0.4,-0.8]`，
  导出还是 `[0,0.4,-0.8]`。这是对齐 Bedrock 坐标系，**编辑器视口与游戏画面一致**，
  所以按编辑器里的直觉写就行，不要为了"让文件里的数值对"而反过来改符号。
- `up` / `down` 面会输出**负 `uv_size`**（uv 取右下角 + 负尺寸表示翻转），每个立方体 2 个面，属正常。
- **已知问题**：郁子的 `katana` 骨骼在 Blockbench 视口里不显示（礼的 `gun` 正常）。项目数据、场景图、
  导出 geo 都验证过是正确且完整的，游戏里会正常渲染。**别为此重新建模。**
- **已知小节**：`node verify_item_model.mjs` 会报武士刀有 **6 个面采样到透明区域**
  （元素 #0 的 up/down、元素 #18 的 north/south/up/down）。这两块是**尺寸 0.06 格上下的**
  柄头与刀尖小立方体，实际渲染看不出来，而且刀身曲线本身已经烘在立方体坐标里、
  没有丢（不是 README 早先记的"4 段骨骼各 1.5° 没还原"）。要清掉这个告警，得给这几面
  挑一块不透明的 uv 矩形后重跑 `geo_to_item_model.mjs`。

## 手工调整后如何回写（重要）

模型在 Blockbench 里手动调过之后，要按这个流程落回配置，否则下次 `make` 就白调了：

1. 在 Blockbench 里调好（几何 / 贴图都行）
2. `node _mcp_run.mjs --eval-file _read_state.js > _state.txt`
3. `_state.txt` 里含三个角色的**贴图 dataURL** 和**武器骨骼几何**：
   - 贴图：剥掉 `data:image/png;base64,` 前缀，base64 解码写成 `textures/<角色>_armed.png`，
     再复制到 `src/main/resources/assets/galboss/textures/entity/<角色>.png`
   - 武器：把 `weapon.bone`（pivot / rotation）和 `weapon.cubes`（from / to / pivot）
     抄进 `pipeline.mjs` 的 `WEAPON_SPECS`
4. `node pipeline.mjs make <角色>` 验证重建结果与手调一致

## 武器：做成物品（枪 / 刀）

武器**不做独立 GeoModel**，而是注册成普通物品（`ModItems.IKUKO_KATANA` / `REI_GUN`），
由渲染层用 GeckoLib 官方的 `BlockAndItemGeoLayer` 画在 `weapon_anchor` 骨骼上。

原因（对比早先「独立 GeoModel + 自定义渲染层」的方案）：

| 麻烦 | 物品方案 |
|---|---|
| 位置/朝向要手算矩阵和偏移常量 | 交给原版 `ItemDisplayContext` 的标准握持变换，零常量 |
| 武器绕自身轴自转（GeckoLib 按骨骼名把角色动画套到武器上） | 物品模型没有骨骼，不存在这个机制 |
| 一张贴图的限制 | 物品有自己的贴图系统 |

| 武器 | geo | 贴图 | 物品模型 | 渲染层 |
|---|---|---|---|---|
| 礼的手枪 | `geo/rei_gun.geo.json`（**手建**，`make_gun_geo.mjs`） | `textures/item/rei_gun.png` (128² 色板) | `models/item/rei_gun.json` | `ReiGunLayer` |
| 郁子的武士刀 | `geo/ikuko_katana.geo.json`（手建，`make_katana_geo.mjs`） | `textures/item/ikuko_katana.png` (512²) | `models/item/ikuko_katana.json` | `IkukoKatanaLayer` |

手枪原来是 TACZ glock 的 geo + 贴图，**已整体换成自建资产**（不能借用 TACZ 的模型），
`ref/glock_17*.{json,png}` 也已删除。`ref/` 里现在只剩刀的贴图 `blade.png`。

生成物品模型（**别手改 `models/item/*.json`**，改完 geo 重跑）：

```powershell
node make_gun_geo.mjs           # 手枪：手建 geo + 自产 128² 色板贴图
$env:TEX_SIZE='128';  node geo_to_item_model.mjs rei_gun.geo.json rei_gun.json rei_gun 0.6 "0,0,0"
$env:TEX_SIZE='512';  node geo_to_item_model.mjs ikuko_katana.geo.json ikuko_katana.json ikuko_katana 0.45
node verify_item_model.mjs        # 离线体检：uv 空间 + 逐面采样贴图 + element 旋转合法性
```

### 五条硬约束（都在 `geo_to_item_model.mjs` 里，其余细节看脚本注释）

1. **uv 必须在 0..16 的归一化空间**。原版 `BlockModel` **不读 `texture_size`**（1.20.1 全 jar 搜不到这个字符串），
   面 uv 直接进 `TextureAtlasSprite.getU(u) = u0 + (u1-u0) * u/16`。
   单位是「整张贴图的 1/16」，不是像素 —— 之前按像素写（最大到 114），武器在游戏里就「像没贴图」。
2. **坐标系只把 X 取反**。GeckoLib 载入 bedrock geo 时做的就是 X 镜像
   （`pivot=(-x,y,z)`、`rot=(-x,-y,z)`、`origin=(-(x+w),y,z)`），而物品模型渲染在同一个空间里。
   曾经误以为 item model 的 Y 向下、对 Y 也做了镜像，结果整把武器上下颠倒。
3. **握把要落在物品空间的中心 (8,8,8)**。原版先绕模型中心做 display 的旋转/缩放，再 `T(-0.5,-0.5,-0.5)`，
   所以握把在中心 = 旋转轴在握把上 = 无论 display 怎么转，握把都停在骨骼原点。
4. **立方体自带的旋转必须搬成原版 element rotation**。GeckoLib 是逐 cube 应用它的
   （`renderCube` → `rotateMatrixAroundCube`），拍平时直接丢掉这些零件就全歪 ——
   表现是「模型乱、贴图跟着乱」（旧版 TACZ 枪有 101 个自带旋转的 cube，就是这么翻车的）。
   原版对应关系（两边都是右手系）：
   ```
   geckolib: rotationXYZ(toRadians(-rx), toRadians(-ry), toRadians(rz))
   原版    : { "rotation": { "origin": pivot, "axis": "x|y|z", "angle": rx*-1 / ry*-1 / rz*+1 } }
   ```
   原版 angle **只接受 0 / ±22.5 / ±45**（`BlockElement.Deserializer.getAngle` 直接抛异常，
   超了整个模型加载失败），一个 element 也只能绕单轴 —— 多轴 cube 转换脚本会打告警。
5. **几何变换只能写在 cube 上，不能写在骨骼上**。转换是「按 cube 的绝对坐标拍平」，
   完全不解析骨骼层级，所以非零的骨骼 rotation 会被丢掉（脚本会打告警）。
   手建模型（`make_gun_geo.mjs` 的握把后倾）因此把旋转挂在 cube 上。
   已知的历史欠账：`ikuko_katana.geo.json` 的刀身曲线是 4 段骨骼各 1.5°，
   物品模型里这段（最多 6°）没被还原 —— 看起来基本还是直的，要修就得给转换脚本加骨骼链合成。

### `weapon_anchor` 骨骼（三个角色 geo 里都有，parent=`right_arm`，pivot=`[-6,12,0]`）

- pivot 就是手心。**`pivot.x` 必须和父骨骼 `right_arm` 的立方体同号**：那些立方体在
  `x -8..-4`（手心 -6），所以挂点是 `-6`。
- ⚠ 这里踩过坑：曾经写成 `+6`，看着像手心，其实是**另一条手臂**的位置 —— 武器悬在身体另一侧，
  手臂摆动时还在旁边乱晃。GeckoLib 载入 bedrock geo 时对骨骼 pivot 与立方体 origin **都**做
  X 取反（`BakedModelFactory` 的 `updatePivot(-pivot.x, ...)` 与 `origin = -(origin.x + size.x)`），
  两者被同样处理，所以「符号相同」在任何坐标系里都成立。
  修正脚本：`fix_weapon_anchor_side.mjs`（自带自检：锚点 x 必须等于父骨骼立方体中心 x）。
- 手臂动画一动挂点就跟着动 —— 武器因此固定在手上。
- **保持 `rotation = [0,0,0]`**。`GeoRenderer.renderRecursively` 已经套过一次骨骼变换，
  而 `BlockAndItemGeoLayer` 又会调一次 `translateAndRotateMatrixForBone`（= 枢轴平移 + 旋转），
  净效果是 anchor 自己的旋转被应用**两次**：blockbench 里转 θ，游戏里转 2θ。
  枢轴位置不受影响（两次枢轴平移互相抵消），所以朝向一律去改物品模型的 display。

## 外部交付的模型（贝阿朵莉切 / 黑山羊 v6）

三个 FS 角色的 geo 是 `pipeline.mjs` 在 Blockbench 里现场建的；**贝阿朵莉切与黑山羊 v6 是外部交付的 `.bbmodel`**，
没有走那条流水线。黑山羊 v6 的接入、动画与坐标系结论另见 `BLACK-GOAT-SERVANT-V6.md`
（关键是 §3：导出取反 + GeckoLib 载入再取反 = 游戏里等于编辑空间角度；pose_tool 不做第二次取反）。

`import_bbmodel.mjs` 把 Blockbench「Bedrock/GeckoLib 导出」的规则原样复刻了一遍 ——
规则不是猜的，是对着 Blockbench v5 源码 `js/io/formats/bedrock.js` 的
`parseCube` / `compileCube` / `compileGroup` 逐行核对过的：

| # | 规则 | 依据 |
|---|---|---|
| 1 | 骨骼 pivot、立方体 origin 的 **X 取反**；origin 取最小角且 `origin.x = -(from.x + sizeX)` | `compileCube:928`、`compileGroup:999-1000` |
| 2 | 骨骼 / 立方体 rotation 的 **X 与 Y 取反，Z 保留**；立方体 rotation 要带 `pivot` | `compileCube:934-937`、`compileBone:1001-1005` |
| 3 | **up / down 两面要翻成右下角 + 负 `uv_size`**，其余四面原样搬（不然顶/底面贴图颠倒） | `compileCube:966-971` |
| 4 | `texture_width/height` 填 **UV 空间**（bbmodel 的 `resolution`），不是贴图文件边长 | 贴图 4096² 而 UV 空间 512²，就是 8 倍降采样（脚本内置 PNG 盒式降采样，不依赖 Python） |
| 5 | 欧拉顺序 **xyz**（`Rx·Ry·Rz`）—— Blockbench 的 THREE `Euler('XYZ')` 与 GeckoLib 的
`Quaternionf.rotationXYZ` 是同一个顺序，所以角度可以直接搬，不做换序 | Blockbench 源码 + GeckoLib `RenderUtils` |

```powershell
# 角色：4096² 贴图缩到 512²（对应 UV 空间），输出 geo/beatrice.geo.json + textures/entity/beatrice.png
node import_bbmodel.mjs "交付模型/人物模型/35_贝阿朵莉切_正面纹样与胸结修正 - Converted.bbmodel" `
  beatrice.geo.json --texture beatrice.png --identifier geometry.beatrice --texture-scale 8

# 烟斗：bedrock 工程，贴着图原样（128×64）
node import_bbmodel.mjs "交付模型/贝阿朵莉切_烟斗.bbmodel" `
  beatrice_pipe.geo.json --texture beatrice_pipe.png --identifier geometry.beatrice_pipe

# 烟斗改成原版物品模型（非正方形图集要显式给 TEX_SIZE=128x64；握把取靠近嘴那段的杆）
$env:TEX_SIZE='128x64'
node geo_to_item_model.mjs beatrice_pipe.geo.json beatrice_pipe.json beatrice_pipe 0.6 "-9,8,0"
node verify_item_model.mjs
```

产物与约定：

- `geo/beatrice.geo.json` 313 骨骼 / 2159 立方体（4.1 MB），贴图 `textures/entity/beatrice.png` 512²
- `animations/beatrice.animation.json` 只有 5 段（idle/walk/attack/hurt/death），**动过的骨骼要连静止姿态的角度一起写**
  （如 `UpBody` 的 `-5`、`Head` 的 `7.5`、两只手臂的 `±15.5`、小腿的 `2.25`）—— GeckoLib 的 rotation 通道是绝对值，不是偏移
- 模型原生高度 45.9 单位（≈2.87 格），按 YSM 的 `height_scale = 0.7` 惯例在**渲染器**里 `withScale(0.7f)`
  收到 ≈2.0 格，与 `.sized(0.7f, 2.0f)` 对齐；geo 保持与交付模型 1:1，要改比例只动渲染器那一个数
- 烟斗走「物品 + `BlockAndItemGeoLayer`」：挂点骨骼 `RightHandLocator`（rotation 必须为 0），
  物品模型 `models/item/beatrice_pipe.json` 由 `geo_to_item_model.mjs` 生成，**别手改**
- ⚠ 烟斗的 83 个立方体里有 **36 个是自由角度旋转**（90° / 135° / 180° / 67.5° / 177.5° …）。
  原版物品模型只接受 `0 / ±22.5 / ±45`，**其它角度会让整个模型反序列化抛异常** ——
  表现是模型根本不加载、手上只剩 missing 的紫黑棋盘格方块。`geo_to_item_model.mjs` 现在会
  把这些角度**丢掉旋转**（保留轴对齐盒子，靠重叠维持体积）并打印统计；想保留圆环造型就得
  手做低模物品模型，或改用 GeckoLib 的 GeoItem（不受角度限制）
- 已知：源模型头部右侧有一块离头约 3 单位的独立小块（两种欧拉顺序下都存在），是交付模型自带的，
  不是转换引入的；要处理就在 Blockbench 里调整后再重新导入

### 七大罪 YSM 七人 + 炼狱之柱（GeoItem 手持）

第二条外部交付线：七位「炼狱之桩」（贝阿朵莉切的七名仆从）+ 一根炼狱之柱。
这次的源不是 `.bbmodel` 而是 **YSM 模型包**（`Characters/<Id>/YSM-source/`），
所以走 `import_purgatory_stakes.mjs` 而不是 `import_bbmodel.mjs`：

```powershell
# 默认读 <repo>/../_incoming/purgatory-stakes（交付 ZIP 的解压目录，不入库）
node import_purgatory_stakes.mjs
node import_purgatory_stakes.mjs --src "D:\some\where\purgatory-stakes"
```

三条结论（都踩过一次，别照着感觉改）：

1. **贴图不降采样**。YSM 的 `main.json` 已经是 bedrock geo（`texture_width/height` = 512×256 是 UV 空间），
   而交付的 PNG 是 2048×1024。GeckoLib 把 uv 归一化后再采样，PNG 保持 2048×1024 就是白拿 4 倍纹理密度；
   降成 512×256 才是丢细节（贝阿朵莉切那条线降采样是因为源图 4096² 对 512² 的 UV 空间，
   这里不一样，别套用）。
2. **uv 是逐面形式**（`{north:{uv,uv_size},…}`，465 个方块里 463 个），不是 box uv。
   GeckoLib 4.4.2 的 `UVUnion` 两种都收；`pose_tool.mjs` 也是按逐面读的。
3. **动画必须清 molang**。`main.animation.json` 里有一个**不存在的 `molang` 伪骨骼**，
   以及 `run` 的 `Root` 通道写着 `0.4*ysm.head_yaw`。脚本会把伪骨骼丢掉、非数字关键帧值置 0 并计数；
   顺带把 `lerp_mode: catmullrom` 一起丢掉（GeckoLib 4.4.2 只做线性插值）。
   rotation 通道保持**绝对值**语义原样搬，不要把 geo 里的静止姿态（手臂 ±15.5°）加回去。

产物：
- `geo/purgatory_stake_<id>.geo.json` ×7（314–316 骨 / 369–467 块，只改了 identifier）
- `textures/entity/purgatory_stake_<id>.png` ×7（2048×1024，原生）
- `animations/purgatory_stake.animation.json`（1 份共用，6 段：idle/walk/run/hurt/attack/death）
- `geo/item/purgatory_pillar.geo.json` + `textures/item/purgatory_pillar.png`（512×256）

**柱子走 GeoItem，不走 `geo_to_item_model.mjs`**：它 968 个方块里 **819 个带自由角度旋转**
（螺旋是斜置薄板拼的），`geo_to_item_model.mjs` 会把这些旋转全丢掉 —— 柱子会变成一个没有螺旋的疙瘩。
所以 `PurgatoryPillarItem implements GeoItem` + `GeoItemRenderer`（bedrock geo 通路，不受角度限制），
物品模型 `models/item/purgatory_pillar.json` 只写 `builtin/entity` + `display`。
手上的挂法仍是老规矩：`BlockAndItemGeoLayer` + `RightHandLocator`（和烟斗同一个挂点）。

⚠ GeoItem 的坐标系和原版 elements 不同：**geo 的原点在握柄中心**（交付约定），
不是原版物品的 `(8,8,8)` 中心。所以 `display.translation/rotation` 是绕握柄转的，
`pose_tool.mjs --item-geo` 也按 `center = (0,0,0)` 处理。

**朝向别再靠感觉调**（第一版竖着插在手上，被反馈成「像插在背后」）：人物正面是 **-Z**
（`pose_tool` 的 `front` 相机看到的就是脸，说明脸朝 -Z）。但**离屏推出来的角度只对 NPC 骨骼帧有效**：

| 用在谁身上 | display 槽位 | 值（实机标定） |
|---|---|---|
| NPC（`RightHandLocator` 骨骼帧） | `head` | `rotation [-85,0,15]`、`translation [0,0,0]` |
| 玩家第三人称 | `thirdperson_*` | `[0,0,0]` —— 原版手持帧本身就让 +Y 朝前 |
| 玩家第一人称 | `firstperson_*` | `rotation [-55,0,0]` —— 原版第一人称里 +Y 是竖的 |

⚠ 三个坑，全都在实机里踩过：

1. **NPC 骨骼帧 ≠ 原版手持帧**，两者差接近一个直角。三处写同一个 `-85` 的结果是：
   NPC 手里横持正确，玩家手里是一根朝下吊着的柱子。
2. **`translation` 对 NPC 必须为 0**。柱子模型原点就是握把中心、`RightHandLocator` 就在手心，
   给 `[-4,0,-2]` 会让握把离开手心 —— 实机看就是「武器错位」。
3. 只靠 `pose_tool` 离线推角度**推不出玩家手**：那个工具复刻的是骨骼帧。玩家手的三套数值
   只能在游戏里标定（做法见下），`verify_purgatory.mjs` 现在对三个槽位分别设了护栏。

标定手法（一次搞定，不用一轮一轮重启）：临时注册 N 个同模型、只有 `display` 不同的探针物品，
`/item replace entity @s weapon.mainhand with galboss:pillar_probe_K` 就能在第一人称逐个看；
第三人称则把探针塞给盔甲架（`HandItems:[{id:"galboss:pillar_probe_K",Count:1b},{}]`）。
这一次的探针是临时的，标完已删。

柱子有两段动画：`spin`（常态循环：金螺旋自转 + 流苏轻晃）与 `use`（右键一次性 0.45 秒，
柱体沿 **+Y（柱尖方向）** 向前突 2.6 单位再回收 —— 是「突刺」，不是「后坐」；写成负 Y 会变成开枪回弹）。
预览直接拿柱子 geo 当主模型：

```powershell
node pose_tool.mjs ../../src/main/resources/assets/galboss/geo/item/purgatory_pillar.geo.json `
  --anim ../../src/main/resources/assets/galboss/animations/item/purgatory_pillar.animation.json `
  --name animation.purgatory_pillar.use --t 0.15 `
  --texture ../../src/main/resources/assets/galboss/textures/item/purgatory_pillar.png `
  --render _preview/pillar_use.png --views side
```

### 动画迭代：pose_tool.mjs（不用 Blockbench）

Blockbench 靠 MCP 一次改一根骨骼太慢，而「抬手会不会穿模」「走路腿穿过裙子」本质是几何问题。
`pose_tool.mjs` 直接读 geo + animation，在任意时刻把骨骼姿态求值出来，**出图 + 穿模体检**：

```powershell
# 渲染某个动画在 t=1.5s 的姿态（front/side/three 任选）
node pose_tool.mjs ../../src/main/resources/assets/galboss/geo/beatrice.geo.json `
  --anim ../../src/main/resources/assets/galboss/animations/beatrice.animation.json `
  --name animation.beatrice.idle --t 1.5 `
  --texture ../../src/main/resources/assets/galboss/textures/entity/beatrice.png `
  --render _preview/idle.png --views front,three

# 试姿势：把候选角度写在 json 里（免得命令行引号地狱）
node pose_tool.mjs <geo> --pose-file _preview/pose-v3.json --show RightHandLocator,LeftHandLocator

# 穿模体检：A 组骨骼 × B 组骨骼，统计「真穿透」的立方体对数（3×3×3 体积采样，不是 AABB 高估）
node pose_tool.mjs <geo> --anim <anim> --name animation.beatrice.walk --t 0.3 `
  --clip "RightLeg,LeftLeg,RightFoot:Beatrice_EmbroideredOverskirt,Beatrice_WinePetticoat"
```

求值语义与 GeckoLib 4.4.2 对齐：rotation 通道是**绝对**值、position 是偏移；关键帧线性插值；
Molang 只支持 `Math.sin/cos/abs/max/min`（角度制）+ `query.anim_time`。

七位炼狱之桩接进来时补了三个能力（都是为了「柱子到底有没有挂到手上」这件事）：

```powershell
# 1. GeoItem 挂载预览：柱子不是原版 elements，走 --item-geo
node pose_tool.mjs ../../src/main/resources/assets/galboss/geo/purgatory_stake_asmodeus.geo.json `
  --anim ../../src/main/resources/assets/galboss/animations/purgatory_stake.animation.json `
  --name animation.purgatory_stake.idle --t 1.0 `
  --texture ../../src/main/resources/assets/galboss/textures/entity/purgatory_stake_asmodeus.png `
  --item-geo ../../src/main/resources/assets/galboss/geo/item/purgatory_pillar.geo.json `
  --item-texture ../../src/main/resources/assets/galboss/textures/item/purgatory_pillar.png `
  --item-disp-file _preview/disp-pillar.json --anchor RightHandLocator `
  --render _preview/stake_pillar.png --views front,three
```

- **`--item-geo`**：把 bedrock geo 按 `display` 变换挂到锚点骨骼上（`--item` 那条只读原版 elements）。
  传 `--item-disp-file` 时读单个 `{rotation,translation,scale}`（省得命令行引号地狱）。
  GeoItem 的模型原点是握柄中心，所以中心取 `(0,0,0)`，不是原版物品的 `(8,8,8)`。
- **标量通道**：`"scale": 1`（YSM 常这么写）按均匀缩放展开成 `[v,v,v]`，以前会直接 `TypeError`。
- **`{pre, post}` 关键帧**：以前只认裸数组，遇到 YSM 的 `{"0.0": {"post": [...]}}` 会崩；
  现在取 `post`（只有 `pre` 时取 `pre`），与线性插值语义一致。

**这次用它查出来的两件事**（数值都是立方体级真穿透对数）：

| | 修前 | 修后 | 静止基线 |
|---|---|---|---|
| 走路：腿/脚 × 裙摆 | 32 对 | 2~4 对 | 6 对 |
| 抬手：右臂 × 躯干/头 | — | 202 对 | 256 对（袖子本来就塞在胸衣里，属美术内置重叠） |

所以：**抬手是安全的**（举臂反而让重叠减少）；走路穿模的根因是腿摆 ±26° 太大，大腿从裙摆前后侧穿出去，
改成 ±7° + 膝盖 6° + 裙摆反向摆 ±2.5° 后低于静止基线。

## 验证清单

每次改完模型 / 动画，按顺序跑一遍：

```powershell
node pipeline.mjs --check         # 1. 离线自检（骨架层级 / 重名 / UV / 分段衔接 / 动画骨骼名与时长）
node verify_anims.mjs --char <角色>  # 2. 渲染动画关键帧，肉眼看 _preview/anim_<角色>_*.png
                                  #    （武器是物品、走渲染层，Blockbench 预览里**看不到武器**，
                                  #      持械姿态只能进游戏验）
# 3. 贴图侧：头顶该采到发色、第二层头顶该不透明；第一层 10 个立方体应全部 100% 不透明
#    （不然身上有洞）；手臂背面覆盖率
# 4. 改过几何：逐个 make，然后对比新 geo 与 _backup 里的旧版，确认只有预期的骨骼/UV 变化
gradlew build                     # 5. 打包通过
```

改了 `anims.mjs` 之后别忘了 `node build_all_anims.mjs`（三份动画文件一起更新）。

## 教学楼结构（`school_building.nbt`）

建筑本体在存档 `run/saves/小学教学楼/` 里手搭，导出成结构给模组用。

**尺寸 133 x 35 x 141**，6 层楼（楼板 y 0/5/10/15/20/25，玩家层 y 1/6/11/16/21/26），
每层 **18 间教室 = 3 翼 × 6 间**（翼中心 x≈25 / 69 / 113，每间中心 z=30/48/66/84/102/120）。
顶层中间翼正中间那间（x62..76 z58..74，中心 z=66）是 boss 房间。

```powershell
# 1. 从存档导出（会顺手把铰链装反的门翻正，并关门）
node export_structure.mjs "d:/game/mc/bossmod/run/saves/小学教学楼" 3 -60 -2 135 -26 138 `
  ../src/main/resources/data/galboss/structures/school_building.nbt

# 2. 往结构里塞战利品箱子 + 待机 boss（可重复跑，会先清掉旧的）
node add_structure_content.mjs ../src/main/resources/data/galboss/structures/school_building.nbt

# 3. 校验：结构自检 + 门几何 + 箱子战利品表 + 和存档逐格对比
node verify_structure.mjs "d:/game/mc/bossmod/run/saves/小学教学楼" `
  ../src/main/resources/data/galboss/structures/school_building.nbt 3 -60 -2 135 -26 138

# 4. 出图（35 层俯视 + 剖面）
node render_structure.mjs ../src/main/resources/data/galboss/structures/school_building.nbt _preview/school 1 6
```

辅助脚本：`analyze_floors.mjs`（每层连通域）、`analyze_rooms.mjs`（每层教室房间）、
`analyze_doors.mjs`（门的朝向/靠墙统计）、`fix_doors.mjs`（开门朝向表 + 翻正/复检）、
`probe_box.mjs`（扫存档某个盒子）、`scan_world_blocks.mjs`。

**结构里带了什么**（由步骤 2 写入，不在存档里）：

- **108 个箱子**，每个教室一个（贴教室北墙、正面朝房间中心），战利品表 `minecraft:chests/end_city_treasure`
- **3 个待机 boss**：顶层中间翼正中间那间教室，`(69,26,63)` 礼 / `(69,26,66)` 桐香 / `(69,26,69)` 郁子，
  带 `FsChallengeOnUse:1b` 标记。头顶名字**不写进结构**（写死中文的话英文客户端会看到中文），
  由实体按 `message.galboss.challenge_label` 拼成「右键挑战 · 角色名」，开战后自动换成角色本名

**游戏里**：`/place template galboss:school_building`（结构里的实体会一起生成）。

- 待机 boss 不会主动攻击、不会消失，**开战前打不动它**（只弹一句「还在等你发起挑战」）
- 右键任意一个 → 开场对白 → 3 秒后开战，**旁边还在待机的两个会被一起拉进战斗**（联动半径 32 格）
- 开战瞬间头顶的「右键挑战」自动撤掉，只剩角色本名（和血条上显示的是同一个名字）
- 潜行右键可跳过对话直接开战；用刷怪蛋/命令放的 boss 不带待机标记，行为与以前一致
