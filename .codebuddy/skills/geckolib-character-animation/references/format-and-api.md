# 动画 JSON / geo 格式与 GeckoLib 4.x API 速查

写具体 JSON 或 Java 代码前读这份。

## 一、动画 JSON

顶层两个键：`format_version`（`"1.8.0"` 或 `"1.12.0"`，两者都行）、`animations`。
GeckoLib 4.x 的 Blockbench 插件导出的还会带 `"geckolib_format_version": 2`。

每段动画的字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `loop` | `true` / `"hold_on_last_frame"` / 省略 | 省略 = 播完回默认姿态（等同 `false`） |
| `animation_length` | 数字（秒） | 可以大于最后一个关键帧时间，多出的部分是保持 |
| `bones` | 对象 | key = 骨骼名 |
| `override_previous_animation` | `true` | 该动画会清掉其他控制器叠加的姿态（少用） |
| `blend_weight` | 数字 | 混合权重（少用） |

`bones` 里每根骨骼可以有 `rotation` / `position` / `scale` 三个通道，单位和约定：

- **rotation：角度**（度），`[x, y, z]`，旋转顺序 XYZ。
- **position：像素**，16 像素 = 1 格。骨骼的局部坐标，相对父骨骼。
- **scale：倍率**，`[1,1,1]` 为原大小。0.01 这类极小值可用来做"消失"。

### 值的三种写法

```json
// 1) 裸数组（老写法）
"0.25": [0, 45, 0]

// 2) vector 简写（Blockbench 现代导出）
"0.25": { "vector": [0, 45, 0] }

// 3) 带贝塞尔手柄（Blockbench 的 pre/post 导出）
"0.25": { "pre": [0, 45, 0], "post": [0, 45, 0], "lerp_mode": "catmullrom" }
```

**不带时间键的 `{"vector": [...]}` = 整段恒定值**：

```json
"lower_arm_r": { "rotation": { "vector": [0, 0, 0] } }   // 整段保持 0，省 3 个关键帧
```

### 时间与帧网格

时间单位是**秒**，但 Blockbench 里是按帧编辑的，导出时会把时间吸附到**项目 fps 的整帧**上。
实测某项目 fps = 24，写 `0.35` 会被吸附成 `0.3333`（= 8/24）；另一个项目 fps = 48。

**因此源码与导出产物对不上是常态。** 两个办法：

1. 用帧号函数书写，让源码本身就是整帧：`const T = n => +(n / 24).toFixed(4);`，
   然后写 `time: T(6)`（= 0.25s）。
2. 用 `check_animation.py` 反查：它会自动探测 fps 并列出不在整帧上的时间。

`animation_length` 不一定要落在整帧上（它只是时长上限）。

### 缓动

GeckoLib 4.x 默认 catmullrom 插值，**大多数情况什么都不用写**。要显式控制时：

```json
"0.25": { "vector": [0, 45, 0], "easing": "easeInOutQuad" }
"0.25": { "post": [0, 45, 0], "lerp_mode": "catmullrom" }
```

`easing` 可用值（实测某模组用过的）：`linear` `step` `easeInSine` `easeOutSine`
`easeInOutSine` `easeInQuad` `easeOutQuad` `easeInOutQuad` `easeInCubic` `easeOutCubic`
`easeInOutCubic` `easeInQuart` `easeOutQuart` `easeInOutQuart` `easeInQuint` `easeOutQuint`
`easeInOutExpo`。

**建议全文件用一种**（或最多 2 种）。堆到 17 种时动作会显得"毛"。

### Molang 表达式

字符串可以直接写在 `vector` 的任意分量上。最有用的变量：

| 表达式 | 含义 |
|---|---|
| `query.anim_time` | 当前动画已播放的**秒数**（循环动画会回绕） |
| `query.life_time` | 实体存活时间（秒） |
| `query.frame_alpha` | 当前帧内的插值进度 0~1 |
| `math.sin(x)` / `math.cos(x)` | 三角函数 |

```json
"right_arm": { "rotation": { "vector": ["math.sin(query.anim_time * 360) * 45", 0, 0] } }
```

`* 360` = 1 周期/秒。频率由系数决定，与 `animation_length` 无关，天然无缝循环。

**注意**：不同模组/版本里 `Math.` 与 `math.` 大小写都出现过，写之前确认目标 GeckoLib 版本；
不确定就用手写关键帧。

## 二、Bedrock geo 结构

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [{
    "description": {
      "identifier": "geometry.foo",          // 必须与 Blockbench 项目名一致
      "texture_width": 64, "texture_height": 128,
      "visible_bounds_width": 3, "visible_bounds_height": 3.5,
      "visible_bounds_offset": [0, 1.25, 0]  // 影响剔除，写小了远处会突然消失
    },
    "bones": [
      { "name": "root", "pivot": [0, 0, 0] },
      { "name": "body", "parent": "root", "pivot": [0, 12, 0],
        "cubes": [{ "origin": [-4, 12, -2], "size": [8, 12, 4],
                    "uv": { "north": { "uv": [20, 20], "uv_size": [8, 12] }, ... } }] }
    ]
  }]
}
```

- **`pivot` 是模型空间的绝对坐标**，不是相对父骨骼的。父骨骼只决定旋转继承。
- **`origin` 是立方体的最小角**（不是中心），`size` 是体积；这是和 Blockbench 的
  `from` / `to` 不同的地方（导出器负责换算）。
- 骨骼数组是**扁平列表**，靠 `parent` 串起来；也可以写成嵌套的 `children`。
- **没有 `parent` 的骨骼就是顶层。** 一个模型**只应该有一个顶层骨骼**（root）——
  多个顶层骨骼会让整模型级别的变换失效（这是 `--check` 里那条"顶层骨骼只有 root"的由来）。

### UV 的坐标约定

每个面自己带 `uv`（起点）与 `uv_size`。**`up` / `down` 面导出后 `uv_size` 常是负数**
（uv 取右下角 + 负尺寸表示翻转），每个立方体 2 个面，属正常，不要去"修"。

展开公式（`uv` 起点 `(U,V)`、盒子 `(w,h,d)`）：

```
up    = (U+d,     V)      size (w, d)
down  = (U+d+w,   V)      size (w, d)
east  = (U,       V+d)    size (d, h)
north = (U+d,     V+d)    size (w, h)
west  = (U+d+w,   V+d)    size (d, h)
south = (U+d+w+d, V+d)    size (w, h)
```

`up` 与 `down` 写反的典型症状：**头顶采到脖子、第二层头顶采到空白 = 看起来像光头**。

### 把一条肢体拆成两段（关节分段的数学）

目标：`from=[x, y0, z] to=[x+w, y0+h, z+d]` 的立方体，在 `y = y0 + h/2` 处拆成上下两段，
**体积与 UV 覆盖完全不变**（静止时逐像素一致）。

```
上段（含原来的 up 面）:  from=[x, ym, z] to=[x+w, y0+h, z+d]    pivot=[关节 x, ym, 关节 z]
下段（含原来的 down 面）: from=[x, y0, z] to=[x+w, ym,   z+d]    pivot=[关节 x, y0, 关节 z]
其中 ym = y0 + h/2
```

四个侧面（north/south/east/west）各取原矩形的上下两半：

```
上半 = [U, V,        W, H/2]
下半 = [U, V + H/2,  W, H/2]
```

`up` / `down` 两个端面**两段都给**：上段的 `up` / 下段的 `down` 是真正的端面，
另外两个是关节处的**断面** —— 静止时两段贴合互相遮住，弯下去才露出来，
所以断面直接复用对侧端面的贴图即可。

**验证方式**：写脚本比对拆分前后的 geo，确认两段的体积和恰好等于原体积、
四个侧面的 UV 上下相接且总高等于原矩形。只靠肉眼看不出来。

## 三、导出时的符号转换（Bedrock 坐标系对齐）

编辑器里的值与导出文件里的值**不是一回事**。实测结论：

| 项目 | 是否取反 |
|---|---|
| 骨骼 `pivot.x`、立方体 `origin.x` | **是** |
| 旋转的 **X 与 Y** | **是** |
| 旋转的 **Z** | 否 |
| `position` 的三个分量 | 否 |

例：编辑器里 `right_shin` 写 `-26`，导出文件是 `+26`；
编辑器里 `body.position = [0, 0.4, -0.8]`，导出还是 `[0, 0.4, -0.8]`。

**关键**：这层转换是为了对齐 Bedrock 坐标系，**编辑器的视口与游戏画面是一致的**。
所以按编辑器里的直觉书写即可，**不要为了"让文件里的数值看起来对"而反过来改符号**。

坐标直觉（编辑空间）：`+Y` 向上；`-Z` 是角色正面（脸的贴图在 `-Z` 侧）；
角色右手在 `+X` 侧。由此推出：

- **下垂的四肢**：`+X` 旋转 = 向前摆，`-X` = 向后甩
- **朝上的部位（root / body / head）**：`+X` = 向后仰，`-X` = 向前倾
- **肘**：前臂 `+X` 是屈肘（手往身前收）
- **膝**：小腿 **`-X`** 才是屈膝（脚跟往后收）。写成 `+X` 会得到反关节的腿
- **走路对侧律**：右腿向前（`+X`）时右臂要向后（`-X`）

**如何自查符号搞反了**：在 Blockbench 里把动画停在某个拍点截图，侧视相机放在 `+X` 看向原点，
此时**屏幕右侧就是角色正面**。看躯干/手臂往哪边倒即可。

## 四、GeckoLib 4.x API 速查

包名是 4.x 的 `software.bernie.geckolib.*`（3.x 是 `software.bernie.geckolib3.*`）。

### 实体

```java
public class FooEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar c) { ... }
}
```

### 控制器

```java
new AnimationController<>(this, "movement", transitionTicks, this::predicate)
controller.triggerableAnim(name, RawAnimation.begin().then(name, Animation.LoopType.PLAY_ONCE));
controllers.add(controller.receiveTriggeredAnimations());
this.triggerAnim(controllerName, animationName);           // 只在服务端调
this.isPlayingTriggeredAnimation();                        // predicate 里的守卫
state.getController().setAnimationSpeed(1.15f);            // predicate 里按状态变速
state.setAndContinue(RawAnimation);                        // 返回 PlayState
state.getPartialTick();
```

`PlayState`：`CONTINUE`（保持当前）/ `STOP`（停止该控制器，让位给别的）。

`RawAnimation` 的收尾方式：

| 方法 | 效果 |
|---|---|
| `.thenLoop(name)` | 循环 |
| `.thenPlay(name)` | 播一次 |
| `.then(name, LoopType.PLAY_ONCE)` | 显式指定循环类型 |
| `.thenWait(ticks)` | 等待若干 tick |
| `.thenLoop(name)` 之后再 `.then(...)` | 串接第二段 |

### 模型与渲染

```java
public class FooModel extends GeoModel<FooEntity> {
    public ResourceLocation getModelResource(FooEntity e)     { ... "geo/foo.geo.json" }
    public ResourceLocation getTextureResource(FooEntity e)   { ... "textures/entity/foo.png" }
    public ResourceLocation getAnimationResource(FooEntity e) { ... "animations/foo.animation.json" }
}

public class FooRenderer extends GeoEntityRenderer<FooEntity> {
    public FooRenderer(Context ctx) { super(ctx, new FooModel());
        this.addRenderLayer(new SomeGeoRenderLayer(this)); }
    public RenderType getRenderType(FooEntity a, ResourceLocation tex, ...) {
        return RenderType.entityTranslucent(tex); }        // 半透明
}
```

自发光：自定义 `GeoRenderLayer` + `RenderType.eyes(texture)` + `getDefaultBakedModel()`。

### 挂武器（`BlockAndItemGeoLayer`）

```java
public class GunLayer extends BlockAndItemGeoLayer<FooEntity> {
    protected ItemStack getStackForBone(GeoBone bone, FooEntity a) {
        return "weapon_anchor".equals(bone.getName()) ? new ItemStack(MyItems.GUN.get()) : null; }
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack s, FooEntity a) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND; }
}
```

**挂点的三条铁律：**

1. **`weapon_anchor` 自己的 `rotation` 必须保持 0。**
   渲染顺序是 `GeoRenderer.renderRecursively() → pushPose() → prepMatrixForBone(bone)`
   `→ renderCubesOfBone() → applyRenderLayersForBone()`，而 `BlockAndItemGeoLayer` 又会调一次
   `RenderUtils.translateAndRotateMatrixForBone(poseStack, bone)`（只有"平移枢轴 + 旋转"）。
   净效果是 `Frame(anchor) * T(pivot) * R(anchor) * R(anchor)` ——
   **挂点自己的旋转被应用了两次**，在编辑器里转 θ 游戏里会转 2θ。
   枢轴位置不受影响（两次枢轴平移互相抵消），所以朝向一律去改物品模型的 `display`。
2. **`pivot.x` 必须和祖先里那条有立方体的手臂同号。** 两者在导出时被同样取反，
   所以"同号"在任何坐标系里都成立。写反的后果：武器悬在身体另一侧，手臂一摆还跟着乱晃。
3. **想调武器朝向，用挂点的父骨骼（手腕 `hand_r`）。** 它是普通骨骼、旋转只应用一次，
   可以在动画里做 ±25° 以内的"转腕"；角度再大会像武器在手心里自转
   （因为手部网格并不会跟着转）。

**武器为什么建议做成物品而不是独立 GeoModel**：位置朝向交给原版 `ItemDisplayContext`
的标准握持变换（零矩阵常量）、不会因为 GeckoLib 按骨骼名把角色动画套上去而绕自身轴自转、
有自己的贴图系统。物品侧还有一个约定：**握把要落在物品模型空间的中心 (8,8,8)** ——
原版物品渲染先绕模型中心做 display 的旋转/缩放再平移，握把在中心 = 旋转轴在握把上。

## 五、症状 → 原因 对照表

| 症状 | 最可能的原因 |
|---|---|
| 动画照播，某根骨骼就是不动 | **骨骼名拼错**（静默失效）；或那根骨骼既没有立方体也没有子骨骼 |
| 转 `root` 完全没反应 | `root` 是空骨骼、子骨骼被别的东西挂走了（检查是否所有骨骼都在 root 之下） |
| 扭腰时腰部裂开 | 腿没有挂在躯干（`body`）之下，`body` 旋转只带动了上半身 |
| 走路/待机看不出动静 | 只写了 rotation 且幅度太小；考虑加 `body.position` 的重心起伏 |
| 循环处"跳"一下 | 首尾关键帧不一致，或 `loop: true` 却缺 t=0 / t=len |
| 头顶是光头 / 头顶缺覆盖 | `up` / `down` 面的 UV 起点算法写反 |
| 动画结尾姿势残留不回正 | 用了 `hold_on_last_frame` 但代码没在结束后切回 idle |
| 攻击动画卡住不结束 | `attackingPredicate` 只看 `getAttackAnim() > 0`，缺 tick 超时兜底 |
| 一次性招式被移动动画打断 | predicate 里少了 `isPlayingTriggeredAnimation()` 守卫 |
| 武器转了两倍角度 | 给 `weapon_anchor` 写了 rotation（见上文铁律 1） |
| 武器在身体另一侧 | `weapon_anchor.pivot.x` 与手臂立方体异号 |
| 远处模型突然消失 | `visible_bounds_*` 写小了 |
| 改了动画时长后伤害判定错位 | Java 侧的 tick 常量没有同步改 |
