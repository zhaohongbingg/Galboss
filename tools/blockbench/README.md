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
| `geo_to_item_model.mjs` | **武器核心**：geo → 原版物品模型（uv 归一化 / X 镜像 / 握把居中 / element 旋转 / display 预设） |
| `verify_item_model.mjs` | 物品模型离线体检：uv 空间 / 逐面采样贴图 / element 旋转合法性（angle 必须 0·±22.5·±45） |
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
