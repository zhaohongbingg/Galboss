package com.example.galboss.client.fx;

import com.example.galboss.GalBoss;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 世界空间的 pixelfx 序列帧渲染器。
 *
 * <h2>为什么挂在 {@code AFTER_PARTICLES}</h2>
 * 这是世界里「半透明内容全部画完、还没进 HUD」的阶段：既能盖在地面和实体之上，
 * 又不会像 HUD 那样糊在 GUI 上。同一阶段里 Forge 允许自己写顶点直接画。
 *
 * <h2>矩阵约定（这一块最容易写错，已按 MC 1.20.1 源码核对）</h2>
 * <ul>
 *   <li>{@code RenderLevelStageEvent#getPoseStack()} 里<b>只有相机旋转</b>（GameRenderer 只做了
 *       xRot / yRot+180 / roll 三次 mulPose），<b>没有相机平移</b>；原版实体是靠
 *       {@code translate(pos - camPos)} 自己挪的。所以这里也要自己减掉相机位置。</li>
 *   <li>{@code BufferUploader.drawWithShader()} 会用 {@code RenderSystem.getModelViewMatrix()}
 *       覆盖 shader 的 {@code ModelViewMat}。既然视图矩阵已经烘焙进顶点，
 *       画之前就必须把 modelViewStack 压成单位阵，画完再弹回来。</li>
 *   <li>顶点调用顺序必须与 {@link DefaultVertexFormat#POSITION_TEX_COLOR} 的元素顺序一致：
 *       {@code vertex → uv → color}。错序时 {@code BufferVertexConsumer} 会静默忽略，
 *       最后 {@code endVertex()} 抛 “Not filled all elements of the vertex”。</li>
 * </ul>
 *
 * <h2>两种锚点</h2>
 * <ul>
 *   <li><b>ground</b>：图像右 = 世界 +X，图像下 = 世界 +Z（即图像上 = -Z）。先绕 Y 转 yaw，
 *       再绕 X 转 -90° 把竖直四边形放平，pivot 落在 {@code pos} 上。</li>
 *   <li><b>billboard</b>：只绕 Y 朝相机（保持像素竖直，不做全向 billboard），
 *       图像右 = 相机右向量，pivot（pixelfx 的 meta.origin）对齐 {@code pos}。</li>
 * </ul>
 *
 * <p>两种锚点用的是同一套四边形本地坐标（{@code localX = u - pivotX}、
 * {@code localY = pivotY - v}），差别只有 ground 多绕一次 X 轴 -90°。
 *
 * <p>纯客户端类。服务端拓扑里永远不会被加载。
 */
public final class PixelfxRenderer {

    /** 超过这个距离不画（但保留计时，走近了会接着播）。 */
    private static final double MAX_RENDER_DISTANCE = 128.0;

    /** 同屏实例上限，防止异常情况下把帧时间拖垮。 */
    private static final int MAX_EFFECTS = 64;

    private static final List<Effect> EFFECTS = new ArrayList<>();

    private static final Set<String> WARNED = new HashSet<>();

    /** 当前特效列表归属的客户端世界；换维度 / 退出重进时整体清空。 */
    private static Object currentLevel;

    private PixelfxRenderer() {
    }

    /** 场上一个特效实例。age 由世界 gameTime 推进，所以暂停/卡顿时进度不会跑飞。 */
    private static final class Effect {
        final Vec3 pos;
        final String slug;
        final float scale;
        final float yaw;
        final long spawnTick;
        final int lifeTicks;

        Effect(Vec3 pos, String slug, float scale, float yaw, long spawnTick, int lifeTicks) {
            this.pos = pos;
            this.slug = slug;
            this.scale = scale;
            this.yaw = yaw;
            this.spawnTick = spawnTick;
            this.lifeTicks = lifeTicks;
        }
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 在客户端本地登记一个特效。
     *
     * @param scale     缩放倍数（1.0 = 元数据 defaultScale）
     * @param lifeTicks 0 = 一次性特效；>0 = 寿命上限（循环特效用）
     */
    public static void spawn(Vec3 pos, String slug, float scale, float yaw, int lifeTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || pos == null || slug == null) {
            return;
        }
        if (currentLevel != minecraft.level) {
            EFFECTS.clear();
            currentLevel = minecraft.level;
        }
        if (EFFECTS.size() >= MAX_EFFECTS) {
            // 极端刷屏（例如连段一次刷十几个）时丢掉最老的，宁可少一个也不要掉帧
            EFFECTS.remove(0);
        }
        EFFECTS.add(new Effect(pos, slug, scale, yaw, minecraft.level.getGameTime(), lifeTicks));
    }

    /** 退出世界 / 切换维度时清场。 */
    public static void clear() {
        EFFECTS.clear();
        currentLevel = null;
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * Forge 世界渲染阶段回调。只在 {@code AFTER_PARTICLES} 干活，其余阶段立刻返回。
     *
     * <p>用方法引用挂到 {@code MinecraftForge.EVENT_BUS}（见 {@code ClientSetup}）。
     */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            EFFECTS.clear();
            currentLevel = null;
            return;
        }
        if (EFFECTS.isEmpty()) {
            return;
        }
        if (currentLevel != minecraft.level) {
            EFFECTS.clear();
            currentLevel = minecraft.level;
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick();
        long gameTime = minecraft.level.getGameTime();
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

        Iterator<Effect> iterator = EFFECTS.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            if (effect.pos == null || !isFinite(effect.pos)) {
                iterator.remove();
                continue;
            }
            int age = (int) Math.max(0L, gameTime - effect.spawnTick);

            Optional<PixelfxMetadata> metadata = PixelfxAssets.metadata(effect.slug);
            if (metadata.isEmpty()) {
                // 元数据缺失 / 损坏：这条实例没有意义了（日志已经打过一次）
                iterator.remove();
                continue;
            }
            PixelfxMetadata meta = metadata.get();

            // 生命周期：一次性播完移除；循环到寿命上限移除
            if (effect.lifeTicks > 0) {
                if (age >= effect.lifeTicks) {
                    iterator.remove();
                    continue;
                }
            } else if (age >= meta.lifeTicks()) {
                // 注意：这里**不能**再加 `!meta.loop()` 判断。
                // 循环特效（epitaph_gungnir / epitaph_storm_sky / tohka_phase2_eclipse）如果走
                // 一次性 PixelfxFx.play()（lifeTicks = 0），旧代码既不进上面那支、又被 !loop 挡住，
                // 于是永远不会被移除 —— 它一直留在 EFFECTS 里，最后把 MAX_EFFECTS = 64 占满，
                // 后续所有特效都播不出来。循环特效「播一次」的语义 = 播完一圈（meta.lifeTicks()）。
                iterator.remove();
                continue;
            }

            // 距离剔除：只跳过渲染，计时继续走（走近时会看到正确的进度）
            if (effect.pos.distanceToSqr(cameraPos) > maxDistanceSq) {
                continue;
            }

            try {
                renderEffect(effect, meta, camera, poseStack, age, partialTick);
            } catch (RuntimeException e) {
                // 单个特效画挂了不该让整个世界渲染崩掉：记一次日志、跳过这一帧
                String key = "render:" + effect.slug;
                if (WARNED.add(key)) {
                    GalBoss.LOGGER.warn("[pixelfx] 渲染特效 {} 时出错，后续将不再报告: {}",
                            effect.slug, e.toString());
                }
            }
        }
    }

    private static void renderEffect(Effect effect, PixelfxMetadata meta, Camera camera,
                                     PoseStack poseStack, int age, float partialTick) {
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            if (WARNED.add("shader")) {
                GalBoss.LOGGER.warn("[pixelfx] position_tex_color shader 尚未就绪，跳过世界特效渲染");
            }
            return;
        }

        // ---- 帧选择：t 秒 → floor(t * fps)，循环取模，一次性夹到最后一帧
        double seconds = (age + partialTick) / 20.0;
        int frame = (int) Math.floor(seconds * meta.fps());
        if (meta.loop()) {
            frame = Math.floorMod(frame, meta.frames());
        } else {
            frame = Mth.clamp(frame, 0, meta.frames() - 1);
        }
        int col = frame % meta.cols();
        int row = frame / meta.cols();

        // ---- UV：网格里该帧的矩形，四边各内缩半个纹素，避免采样到相邻帧
        float insetU = 0.5f / meta.textureWidth();
        float insetV = 0.5f / meta.textureHeight();
        float u0 = col / (float) meta.cols() + insetU;
        float u1 = (col + 1) / (float) meta.cols() - insetU;
        float v0 = row / (float) meta.rows() + insetV;
        float v1 = (row + 1) / (float) meta.rows() - insetV;

        // 四顶点按「图像左上 → 左下 → 右下 → 右上」排列，UV 对应 (0,0)→(0,1)→(1,1)→(1,0)
        float frameW = meta.frameW();
        float frameH = meta.frameH();
        float[] pixelU = {0.0f, 0.0f, frameW, frameW};
        float[] pixelV = {0.0f, frameH, frameH, 0.0f};
        float[] texU = {u0, u0, u1, u1};
        float[] texV = {v0, v1, v1, v0};

        float unitsPerPixel = meta.defaultScale() * (effect.scale > 0.0f ? effect.scale : 1.0f);
        Vec3 cameraPos = camera.getPosition();

        poseStack.pushPose();
        try {
            poseStack.translate(effect.pos.x - cameraPos.x,
                    effect.pos.y - cameraPos.y,
                    effect.pos.z - cameraPos.z);
            if (meta.billboard()) {
                // 只绕 Y 朝相机：像素列保持竖直，不会随俯仰翻滚
                poseStack.mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
            } else {
                // 先按 yaw 绕 Y（MC 习惯：正角度俯视顺时针），再绕 X -90° 把竖直四边形放平
                poseStack.mulPose(Axis.YP.rotationDegrees(-effect.yaw));
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
            }
            Matrix4f matrix = poseStack.last().pose();

            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            // 贴地四边形与地面共面，靠多边形偏移压过去，避免 z-fighting 闪烁；
            // 立牌虽然站着，也一并偏移（没有副作用）
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1.0f, -10.0f);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

            // 顶点里已经含视图旋转，ModelViewMat 必须是单位阵，否则会转两次
            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushPose();
            modelViewStack.setIdentity();
            RenderSystem.applyModelViewMatrix();
            try {
                // 颜色趟：普通 alpha 混合（position_tex_color 自带的 blend 节点就是这个）
                float tintR = ((meta.tint() >> 16) & 0xFF) / 255.0f;
                float tintG = ((meta.tint() >> 8) & 0xFF) / 255.0f;
                float tintB = (meta.tint() & 0xFF) / 255.0f;
                RenderSystem.setShaderColor(tintR, tintG, tintB, 1.0f);
                if (meta.premultiplied()) {
                    // 预乘 alpha：贴图 rgb 已经乘过 a，源因子必须用 ONE，
                    // 否则 SRC_ALPHA 会把柔边二次压暗（实测这批预乘图的半透明像素亮度
                    // 只有不透明像素的 7%~26%，用错混合就是一圈黑边）
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                if (PixelfxAssets.bindTexture(meta.texture())) {
                    drawQuad(matrix, meta, unitsPerPixel, pixelU, pixelV, texU, texV);
                }
                // 加色 light 趟：SRC_ALPHA / ONE，把发光像素叠到画面上。
                //
                // 注意这里有一个依赖实现细节的假设：BufferUploader 走 VertexBuffer.drawWithShader，
                // 它会先设好 uniform 再 shader.apply()；apply() 里只有 BlendMode.apply() 可能改混合状态，
                // 而同一个 ShaderInstance 连续两次 apply 时 BlendMode.equals(lastApplied) 必然成立、
                // 直接早退 —— 所以颜色趟之后的状态不会被重置，这里手设的混合因子能活到 light 趟的绘制。
                // 若未来换 shader / 渲染管线（例如改用 RenderType 或自定义 pipeline），务必复核这一条。
                if (meta.lightTexture() != null && PixelfxAssets.bindTexture(meta.lightTexture())) {
                    // lightStrength 直接乘在颜色上：加色混合的贡献就是 rgb × a，
                    // 调色 = 调亮度。这批 Godot 图的 light 层 RGB 均值只有 20~60，不放大几乎看不见。
                    float strength = meta.lightStrength();
                    RenderSystem.setShaderColor(tintR * strength, tintG * strength, tintB * strength, 1.0f);
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                    drawQuad(matrix, meta, unitsPerPixel, pixelU, pixelV, texU, texV);
                }
            } finally {
                modelViewStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        } finally {
            poseStack.popPose();
            // 恢复现场：AFTER_PARTICLES 之后还有天气、云、HUD 要画，不能把状态留脏
            RenderSystem.polygonOffset(0.0f, 0.0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    /**
     * 画一个四边形。
     *
     * @param matrix        已含「相机相对平移 + 朝向」的矩阵，直接烘焙进顶点
     * @param unitsPerPixel 像素到世界单位的换算（defaultScale × 缩放倍数）
     */
    private static void drawQuad(Matrix4f matrix, PixelfxMetadata meta, float unitsPerPixel,
                                 float[] pixelU, float[] pixelV, float[] texU, float[] texV) {
        BufferBuilder builder = beginQuads();
        for (int i = 0; i < 4; i++) {
            // 图像的像素坐标换算到四边形本地坐标（左上原点、图像下 = +v）。
            // 两种锚点共用同一套本地坐标：localX = (u - pivotX)、localY = (pivotY - v)，
            // 区别只在朝向 —— ground 再绕 X -90° 把这张竖直的图放平躺到 XZ 平面。
            // pivot 是 pixelfx 的 meta.origin（语义原点），必须严格对齐 pos，不要改成「底边中点」。
            float localX = (pixelU[i] - meta.pivotX()) * unitsPerPixel;
            float localY = (meta.pivotY() - pixelV[i]) * unitsPerPixel;
            builder.vertex(matrix, localX, localY, 0.0f)
                    .uv(texU[i], texV[i])
                    .color(1.0f, 1.0f, 1.0f, 1.0f)
                    .endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    /** 取共享 Tesselator 的 builder；万一上一任使用者没 end 就抛了，先把脏数据收掉再重来。 */
    private static BufferBuilder beginQuads() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        if (builder.building()) {
            try {
                builder.end();
            } catch (RuntimeException ignored) {
                // 状态已经不可救：让下面的 begin 抛出，由上层的「渲染异常只记一次」兜住
            }
        }
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        return builder;
    }

    private static boolean isFinite(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
