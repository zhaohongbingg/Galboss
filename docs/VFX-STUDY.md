# 铁魔法 / Traveloptics 特效栈研究

> **证据来源**
> - **ISS（Iron's Spells 'n Spellbooks）**：`github.com/iron431/irons-spells-n-spellbooks` 分支 `1.20.1-legacy` 源码 clone，官方映射，可读。
> - **Traveloptics 7.2.0-1.20.1**：jar 用 ForgeFlower 2.0.629.0 反编译（**SRG 名**，方法名是 `m_xxxxx_`），但 `assets/` 下的 shader 源码、json、粒子定义是**原始未混淆文件**，可直接读。
> - 本文所有数字都是从文件里数出来的，不是估计。

---

## 0. 一句话结论

**ISS 是「在原版渲染管线里把细节堆满」，Traveloptics 是「在它之上再加一层屏幕空间着色器」。**

- ISS：25 个手写粒子类、108 个法术实体文件、自定义 RenderType——**全工程 0 个 shader、0 条后处理**。
- Traveloptics：7 组 core shader + 8 条 post chain + 10 个触发包。

Boss 战里「打击感」那一层——命中白闪、径向模糊、爆炸折射、热浪——**ISS 一个都没有，Traveloptics 全有**。要学的是后者。

---

## 1. 五层特效栈

| 层 | 手段 | 成本 | ISS | Traveloptics | GalBoss 现状 |
|---|---|---|---|---|---|
| **L0 粒子** | `TextureSheetParticle` + 图集 | 低 | 25 类 | 41 个 particle json | ✅ ModParticles + 自定义 RenderType |
| **L1 世界内几何** | `EntityRenderer` + `RenderType` + 贴图 | 中 | 38 个法术实体目录 | 大量 | 🟡 pixelfx 序列帧（纯面片） |
| **L2 屏幕空间 quad** | core shader 采样 `SceneSampler` | 高 | ❌ | ✅ explosion/implosion burst | ❌ |
| **L3 后处理链** | `PostChain` + `program/*.fsh` | 高 | ❌ | ✅ 8 条 | ❌（`MAGIC-FX-FEASIBILITY.md` 判了不建议） |
| **L4 模型动画** | GeckoLib / PlayerAnimator | 美术 | ✅ 28 geo | ✅ 62 geo / 53 anim | ✅ GeckoLib 实体模型 |

---

## 2. ISS：把原版管线用到极限

### 2.1 粒子

- 25 个 `TextureSheetParticle` 子类，全部走 `RenderType.PARTICLE_SHEET_OPAQUE`(11) / `TRANSLUCENT`(7)，配 64 张贴图。
- 冲击波的套路（`ShockwaveParticle`）：`quadSize` 从 0 长到 `targetSize`；`lifetime` 由 scale 反推（`targetSize < 0 ? -scale*20 : |scale|*2.5`，负 scale 表示「收缩」）；每帧衰减速度。

### 2.2 自定义 RenderType（这里藏着最关键的一课）

`render/RenderHelper.CustomerRenderType` 用 `RENDERTYPE_ENERGY_SWIRL_SHADER` 拼出四种：

| 方法 | 混合 | 剔除 | 用途 |
|---|---|---|---|
| `magic` / `magicNoCull` | `ADDITIVE_TRANSPARENCY` | CULL / NO_CULL | 光束、法阵 |
| `magicSwirl` | `ADDITIVE_TRANSPARENCY` + `OffsetTexturingStateShard` | CULL | UV 流动的光带 |
| `darkGlow` | `ONE_MINUS`（反向） | NO_CULL | 暗蚀 |

> **要点：加色在实体渲染路径里是一行 `setTransparencyState(ADDITIVE_TRANSPARENCY)`。**
> 但**粒子路径拿不到这个**——原因见 §5.1，这正好是 GalBoss 现在的卡点。

### 2.3 法阵是程序化几何，没有模型文件

`TargetAreaRenderer`：`segments = 5*radius + 9` 生成圆环顶点，取 6 个 60° 方向采样地形高度做贴合，`RenderType.energySwirl` 绘制。

### 2.4 没有的东西

全工程搜不到 `.fsh` / `.vsh`，没有 `PostChain`，没有自定义 core shader。**ISS 的上限就是原版渲染管线的上限。**

---

## 3. Traveloptics：加了屏幕空间这一层

### 3.1 资产规模

791 纹理 / 293 模型 / 62 geo / 53 动画 / 41 粒子定义 / 147 ogg / **46 个 shader 相关文件**。

### 3.2 两条着色器通道（必须分清）

| 通道 | 资产位置 | 注册时机 | 用途 |
|---|---|---|---|
| **core shader** | `shaders/core/<名>.json` + `.vsh` + `.fsh` | `RegisterShadersEvent` → `ShaderInstance` | 世界内 quad（折射、全屏面片） |
| **post chain** | `shaders/post/<名>.json` + `shaders/program/<名>.fsh` | `RegisterClientReloadListenersEvent`（**可热重载**） | 全屏后处理 |

core shader 的 json 必须写全三块：

```json
{
  "vertex": "modid:explosion_burst",
  "fragment": "modid:explosion_burst",
  "attributes": [ "Position", "UV0" ],
  "samplers": [ { "name": "SceneSampler" }, { "name": "DepthSampler" } ],
  "uniforms": [ { "name": "Progress", "type": "float", "count": 1, "values": [ 0.0 ] }, ... ]
}
```

`attributes` 用 `["Position","UV0"]` 对应 `DefaultVertexFormat.POSITION_TEX`；**`samplers` 里不列出的 sampler 名字在 java 里绑了也无效**。

### 3.3 最值钱的一个：`DirectCoreShaderRenderer`（世界内折射冲击波）

这是 Traveloptics 全部特效里技术含量最高、也最适合 Boss 战的东西。**分两个渲染阶段**：

1. **`Stage.AFTER_PARTICLES`（HIGHEST）**：`glCopyTexSubImage2D` 把主 framebuffer 的**颜色**拷进 `TextureTarget sceneCopy`（深度走 `glBlitFramebuffer`），同时存下当时的 view / proj 矩阵。
2. **`Stage.AFTER_LEVEL`（HIGH）**：恢复矩阵与深度 → `RenderSystem.setShader(shader)` → 绑 `SceneSampler`/`DepthSampler` → 画一个**朝向相机的 quad**（两三角形，UV ∈ [-1,1]）→ `BufferUploader.draw`。

fsh 里四个关键技巧：

| 技巧 | 代码 | 解决什么 |
|---|---|---|
| 屏幕占比淡出 | `pxPerUnit = 1/fwidth(localPos.x)`；`smoothstep(3,24,pxPerUnit)` | 远处/小尺寸时自动不闪 |
| 高斯壳 | `shell(r,c,w) = exp(-((r-c)/w)²)` | 冲击波边缘柔和、可控宽度 |
| 不规则爆散 | `fbm` 4 阶噪声扰动壳半径 | 避免完美圆圈的塑料感 |
| **深度裁切** | `linearize(depth)` 后与壳半径比较 | **被前景挡住的折射自动消失** ← 这是它比贴图面片真实的原因 |

一个 shader 同时干两件事：uniform 里既有 `Progress/Extent/Color`（冲击波），也有 `FlashProgress/FlashExtent/FlashStrength/FlashColor`（命中白闪），用 `Seed` 打散。

### 3.4 全屏后处理：`DirectPostShaderRenderer`

就是 `PostChain` 的薄包装：

```java
postChain = new PostChain(mc.getTextureManager(), resourceManager, mc.getMainRenderTarget(), location);
postChain.resize(w, h);
// renderToScreen():
mainTarget.bindWrite(false);
tempTarget.bindWrite(false);
GlStateManager._glBlitFrameBuffer(...);   // 保护
mainTarget.bindWrite(false);
postChain.process(delta);                  // 链自己把结果写回 minecraft:main
mainTarget.unbindWrite();
```

`setUniform(name, v)` 的做法是**遍历 `postChain.passes`，对每个 pass 的 effect 按名字 set**——所以 json 里 uniform 名字写错不会崩，只是静默没效果（调试时很容易踩）。

post json 固定骨架：

```json
{ "targets": [ "swap" ],
  "passes": [
    { "name": "modid:radial_blur", "intarget": "minecraft:main", "outtarget": "swap",
      "uniforms": [ { "name": "Strength", "values": [ 0.5 ] } ] },
    { "name": "blit", "intarget": "swap", "outtarget": "minecraft:main" } ] }
```

八条链，按对 Boss 战的用处排序：

| 链 | 干什么 | 适合用在哪 |
|---|---|---|
| `impact_frame` | 阈值化 + 垂直 seam 填充 + 色偏 + 高光/阴影染色 | **大招命中那 1–2 帧** |
| `radial_blur` | 12 采样径向模糊 + 8 条能量线 + 中心遮罩 | **受击 / 破防** |
| `boss_break` | 破防演出 | Boss 血条断裂 |
| `heat_haze` | 热扰动 | 炼狱 / 火焰场地 |
| `parry_impact` | 格挡瞬间 | 弹反 |
| `gradient_cinematic` | 上下渐变压暗 | 阶段转场（电影感） |
| `chromatic_aberration` | RGB 分离 | 雷电 / 精神控制（**短促**） |
| `water_distortion` | 水波 | 水下 / 领域展开 |

### 3.5 叠加策略（这条必须抄）

`RadialBlurHandler` **不是**把多个 blur 的强度相加，而是遍历所有 active 记录，**取 strength 最大的那个 dominant 单独渲染**：

```java
for (ActiveBlur blur : activeBlurs)
    if (blur.strength(player, delta) > dominantStrength) { dominant = blur; ... }
shaderRenderer.setUniform("Strength", Math.min(dominantStrength, 1.0f));
```

避免多个效果同时触发时强度爆表、互相打架。

### 3.6 触发：一效果一包

10 个 S2C 包：`ExplosionBurstPacket` / `ImplosionBurstPacket` / `RadialBlurPacket` / `HeatHazePacket` / `ImpactFramePacket` / `ChromaticAberrationPacket` / `WaterDistortionPacket` / `GradientCinematicPacket` / `BossBreakPacket` / `ParryEffectPacket` / `LetterboxPacket`。

客户端 handler 静态注册（`@EventBusSubscriber(Dist.CLIENT, bus = FORGE)`），包只负责往 handler 的 `ACTIVE` 列表塞一条 `(position, radius, durationMs, color, flash...)` 记录，**渲染完全由客户端 tick/渲染事件驱动**。服务端只管「什么时候该播」。

### 3.7 客户端配置与不兼容

- 三个开关：`shaders.enable_chromatic_aberration` / `enable_impact_frame` / `enable_parry_impact`（默认 true）。
- `IncompatibilityCheckerEvent` 把 **OptiFine** 明确列为不兼容，首登弹提示。

---

## 4. 为什么 ISS 不做后处理

不是不会，是**定位不同**：

| | ISS | Traveloptics |
|---|---|---|
| 定位 | 通用法术框架，要求**任何整合包里都不炸** | 演出导向，**接受挑环境** |
| 依赖 | geckolib / curios / caelus / playeranimator | 上面全部 + cataclysm + alexscaves + 自己的 SpellLib |
| 客户端风险 | 零 shader，光影包随便换 | 明确不兼容 OptiFine |

**结论：`MAGIC-FX-FEASIBILITY.md` §34 那条「全屏后处理不建议」的判据本身没错（冲突/易碎是真的），但结论下得太死。** Traveloptics 证明了 1.20.1 Forge 上这套是能跑、能量产、能配开关的——它用的是 8 条链 + 3 个客户端开关 + 一个不兼容清单，而不是「不用」。

---

## 5. 对 GalBoss 的落地清单

### 5.1 先解掉自己写下的那个限制（最高性价比）

`client/particle/GalBossParticleRenderTypes.java` 里现在写着：

> 真正的加色混合（SRC_ALPHA, ONE）在 MC 里没法靠这里的 `blendFunc` 稳定实现——`BufferUploader` 每批绘制都会调 `ShaderInstance#apply()`，它按 shader JSON 的 blend 节点重置混合状态。

**这个判断是对的，解法 Traveloptics 已经给出：自己注册一个带加色 blend 节点的 core shader。**

具体做法。vanilla 1.20.1 `assets/minecraft/shaders/core/particle.json`（已从 `client.jar` 实际取出核对）是：

```json
{ "blend": { "func": "add", "srcrgb": "srcalpha", "dstrgb": "1-srcalpha" },
  "vertex": "particle", "fragment": "particle",
  "attributes": [ "Position", "UV0", "Color", "UV2" ],
  "samplers": [ { "name": "Sampler0" }, { "name": "Sampler2" } ], ... }
```

**注意这里的命名陷阱**：`"func": "add"` 是混合**方程**（`GL_FUNC_ADD`），不是加色；真正决定是普通 alpha 的是 `dstrgb: "1-srcalpha"`。

1. 复制这份 json 到 `assets/galboss/shaders/core/particle_additive.json`，**只改一处**：`"dstrgb": "1-srcalpha"` → `"dstrgb": "one"`（即 `SRC_ALPHA / ONE`，真加色）。`attributes` 与 `samplers` 原样保留，否则 `DefaultVertexFormat.PARTICLE` 对不上。
2. `RegisterShadersEvent` 里 `event.registerShader(new ShaderInstance(provider, id("particle_additive"), DefaultVertexFormat.PARTICLE), s -> ADDITIVE = s)`。
3. 在 `ParticleRenderType.begin()` 里 `RenderSystem.setShader(GalBossShaders::getAdditiveParticle)`，**不要再手动 `blendFunc`**（会被 shader 的 blend 节点覆盖，这正是现在注释里描述的现象）。

> ⚠️ 这条链我没实机跑过（本次只做研究，没改代码）。改动只有一处 json + 一行注册 + 一行 setShader，风险很低，但需要一次 `runClient` 目视确认加色是否真的变亮。

这样 `glow()` / `additive()` 才是真加色，而不是「高亮贴图模拟」。

### 5.2 按性价比排的落地顺序

| 优先级 | 做什么 | 为什么 | 工作量 |
|---|---|---|---|
| **P0** | 加色粒子 core shader | 解 5.1 的卡点，所有现有粒子立刻变亮变「烧」 | 半天 |
| **P1** | `impact_frame` 后处理 | Boss 战打击感最大单点提升，一条链一个开关 | 1–2 天 |
| **P2** | `DirectCoreShaderRenderer` + 冲击波 core shader | 雷击落点、爆炸、神兵砸地都用它；带深度裁切 | 2–3 天 |
| **P3** | `radial_blur` | 受击/破防；抄 dominant 叠加策略 | 1 天 |
| **P4** | `heat_haze` | 炼狱场地氛围 | 1 天 |
| **P5** | `boss_break` | 血条断裂演出 | 1 天 |
| ❌ | `chromatic_aberration` 常开 | 长时间看会晕；只做 <200ms 的短促脉冲 | — |
| ❌ | `water_distortion` 全场 | 与领域/水下玩法绑定才有意义 | — |

### 5.3 现成的接入点

- `client/fx/PixelfxRenderer` —— L1 层已成熟，`impact_frame` 挂在它播完的瞬间最省事。
- `network/S2CCameraShakePacket` —— 已有 S2C 通道，新增 `S2CPostFxPacket` 照抄它的注册方式即可。
- `client/ScreenShake` —— 后处理强度可以和震屏共用一条衰减曲线。
- `spell/SpellFx` —— 17 个特效 slug 的调度中心，`impact_frame` 按 slug 挂。

---

## 6. 可直接抄的骨架

> 名字按 **official mapping** 写（GalBoss 用的就是 official）。Traveloptics 反编译出来是 SRG 名，对照关系见 §7。

### 6.1 core shader 注册

```java
@Mod.EventBusSubscriber(modid = GalBoss.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GalBossShaders {
    private static ShaderInstance explosionBurst;
    private static ShaderInstance particleAdditive;

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent e) throws IOException {
        e.registerShader(new ShaderInstance(e.getResourceProvider(),
                GalBoss.id("explosion_burst"), DefaultVertexFormat.POSITION_TEX),
                s -> explosionBurst = s);
        e.registerShader(new ShaderInstance(e.getResourceProvider(),
                GalBoss.id("particle_additive"), DefaultVertexFormat.PARTICLE),
                s -> particleAdditive = s);
    }

    public static ShaderInstance getExplosionBurst() { return explosionBurst; }
    public static ShaderInstance getParticleAdditive() { return particleAdditive; }
}
```

### 6.2 世界内折射 quad（精简版，去掉了 Traveloptics 的 depth-restore 分支）

```java
public final class DistortionQuadRenderer {
    private static TextureTarget sceneCopy;
    private static final Matrix4f view = new Matrix4f(), proj = new Matrix4f();
    private static boolean pending, captured;

    public static void requestCapture() { pending = true; }

    // 阶段 1：抓颜色 + 存矩阵
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAfterParticles(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!pending) { captured = false; return; }
        view.set(e.getPoseStack().last().pose());
        proj.set(e.getProjectionMatrix());
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (sceneCopy == null || sceneCopy.width != main.width || sceneCopy.height != main.height) {
            if (sceneCopy != null) sceneCopy.destroyBuffers();
            sceneCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        }
        // 用 glCopyTexSubImage2D 抓颜色（比 blit 便宜，只要颜色）
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GlStateManager._bindTexture(sceneCopy.getColorTextureId());
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, main.width, main.height);
        GlStateManager._bindTexture(0);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, readFbo);
        captured = true; pending = false;
    }

    // 阶段 2：画 billboard quad，采样 SceneSampler 做折射
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAfterLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !captured) return;
        ShaderInstance shader = GalBossShaders.getExplosionBurst();
        if (shader == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(false);
        shader.setSampler("SceneSampler", sceneCopy.getColorTextureId());
        shader.setSampler("DepthSampler", sceneCopy.getDepthTextureId());
        // ... setUniform(Progress/Extent/Color/...) 然后画 POSITION_TEX 的两三角形
        // 顶点 UV 用 [-1,1]，fsh 里 localPos = UV0
        RenderSystem.setShader(GalBossShaders::getExplosionBurst);
        BufferUploader.drawWithShader(buf.end());
    }
}
```

### 6.3 后处理 handler 骨架

```java
public final class PostFxRenderer {          // 对应 DirectPostShaderRenderer
    private PostChain chain; private RenderTarget temp;
    private final ResourceLocation loc;
    public void init(ResourceManager rm) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (chain != null) chain.close();
        chain = new PostChain(mc.getTextureManager(), rm, mc.getMainRenderTarget(), loc);
        chain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
    }
    public void renderToScreen(float delta) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        main.bindWrite(false);
        chain.process(delta);
        main.unbindWrite();
    }
    public void setUniform(String name, float v) {
        for (PostPass p : chain.passes)
            if (p.getEffect().getUniform(name) != null) p.getEffect().getUniform(name).set(v);
    }
}
```

---

## 7. 坑清单

1. **SRG 名**：Traveloptics 反编译里 `Minecraft.m_91087_()` = `getInstance()`，`m_91385_` = `getMainRenderTarget()`，`m_91268_` = `getWindow()`，`m_91097_` = `getTextureManager()`，`f_83915_/f_83916_` = `width/height`，`m_83947_` = `bindWrite(boolean)`，`m_83956_` = `unbindWrite()`，`m_83941_` = `resize(w,h,boolean)`，`m_83930_` = `destroyBuffers()`，`f_110009_` = `PostChain.passes`，`m_110074_` = `PostPass.getEffect()`，`m_173350_` = `ShaderInstance.setSampler(String,int)`。**别直接抄 SRG 名进 official mapping 工程。**
2. **uniform 名字写错不报错**：`setUniform` 是「找到才 set」，json 里漏写只是静默失效。
3. **`samplers` 必须声明**：core shader json 里没列的 sampler，java 绑了也不生效。
4. **`RenderSystem.setShader` 会重置混合状态**：手动 `blendFunc` 必须在 setShader 之后（或干脆交给 shader 的 blend 节点）——这正是 GalBoss 现有注释踩到的坑。
5. **渲染阶段**：抓 framebuffer 必须在 `AFTER_PARTICLES` 之后、`AFTER_LEVEL` 之前；抓早了会漏粒子，抓晚了世界已经画完。
6. **尺寸变化要重建**：窗口 resize 后 `RenderTarget` 必须 `resize`，否则拉伸/花屏。
7. **光影包**：Traveloptics 自己把 OptiFine 列进不兼容清单。GalBoss 若要上后处理，**必须配客户端开关 + 首登提示**，且默认值要考虑整合包。
8. **`TextureTarget` 是 1.20.1 的类**：1.21 改成了 `TextureTarget` 的替代实现（`TextureTarget` 被移除/重构），跨版本迁移时这一层要重写。
9. **一效果一包 vs 一包多效果**：Traveloptics 选择前者（包多但互不干扰），GalBoss 现在的 `S2CPixelfxPacket` 是后者。后处理建议照前者做，避免一个包出错整段演出哑掉。

---

## 8. 参考路径

| 内容 | 路径 |
|---|---|
| ISS 源码（clone） | `%TEMP%\irons-iss` |
| Traveloptics 反编译（SRG） | `%TEMP%\trav\src\gametechbc\traveloptics` |
| Traveloptics 原始资产（shader/json/粒子） | `%TEMP%\trav\x\assets\traveloptics` |
| Traveloptics 着色器入口 | `shaders/TravelopticsShaders.java`、`api/client/shader/DirectPostShaderRenderer.java`、`api/client/shader/DirectCoreShaderRenderer.java` |
| ISS 自定义 RenderType | `render/RenderHelper.java`（`CustomerRenderType`） |
| GalBoss 现有粒子渲染 | `client/particle/GalBossParticleRenderTypes.java` |
| GalBoss 现有特效调度 | `client/fx/PixelfxRenderer.java`、`spell/SpellFx.java` |

> `%TEMP%` 会被系统清理。要长期保留 Traveloptics 的反编译源码和资产，需要挪到工程内的 `tools/refs/`。
