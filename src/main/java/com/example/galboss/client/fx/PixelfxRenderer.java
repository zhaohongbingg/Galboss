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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 世界空间的 pixelfx 序列帧合批渲染器（挂在 {@code AFTER_PARTICLES}）。
 *
 * <p>三种摆放方式：
 * <ul>
 *   <li>{@code billboard} —— 正对相机，可用 metadata 的 {@code roll} 固定构图朝向；</li>
 *   <li>{@code ground} —— 绕 X 轴放平贴地，朝向由调用方的 {@code yaw} 决定；</li>
 *   <li>{@code link} —— 沿两个实体的连线拉伸（见 {@link #spawnLink}），
 *       横向铺满两点距离，纵向按 {@code defaultScale}。</li>
 * </ul>
 */
public final class PixelfxRenderer {

    private static final double MAX_RENDER_DISTANCE = 128.0;
    private static final int MAX_EFFECTS = 64;

    private static final List<Effect> EFFECTS = new ArrayList<>();
    private static final Set<String> WARNED = new HashSet<>();

    /** 合批桶复用，避免每帧频繁分配集合对象。 */
    private static final Map<BatchKey, List<QuadVertexData>> BASE_BATCHES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, List<QuadVertexData>> LIGHT_BATCHES = new LinkedHashMap<>();

    private static Object currentLevel;
    private static int nextSlot;

    // ---------------------------------------------------------------- link 电弧（客户端粒子）

    /** 电弧每 2 tick 重画一次：20tps / 2 = 10 次/秒，既有噼啪感又不会糊。 */
    private static final int ARC_INTERVAL_TICKS = 2;
    /** 单次最多画几条索的电弧，避免 12 条链同时铺满粒子。 */
    private static final int MAX_ARC_LINKS = 6;
    /** 每格补多少颗粒子（水母项链用的是 10，这里压到 5 控制同屏量）。 */
    private static final double ARC_PARTICLES_PER_BLOCK = 5.0;
    /** 折线抖动幅度（格）。只抖中间点，两端固定贴住目标。 */
    private static final double ARC_JITTER = 0.16;

    /** 紫罗兰色魂火，和 tohka_soul_chain 的 tint 对齐。 */
    private static final DustParticleOptions SOUL_DUST =
            new DustParticleOptions(new Vector3f(0.70f, 0.55f, 0.95f), 0.9f);

    private static long lastArcTick = Long.MIN_VALUE;
    private static int arcRotation;

    private PixelfxRenderer() {
    }

    private enum BaseBlend {
        STRAIGHT_ALPHA,
        PREMULTIPLIED_ALPHA
    }

    private record BatchKey(ResourceLocation texture, BaseBlend blend) {
    }

    private record QuadVertexData(
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float r, float g, float b, float a) {
    }

    private static final class Effect {
        final Vec3 pos;
        final String slug;
        final float scale;
        final float yaw;
        final long spawnTick;
        final int lifeTicks;
        final int slot;
        /** link 模式的两个实体 id；-1 表示不是 link 模式。 */
        final int linkA;
        final int linkB;

        Effect(Vec3 pos, String slug, float scale, float yaw, long spawnTick, int lifeTicks, int slot,
               int linkA, int linkB) {
            this.pos = pos;
            this.slug = slug;
            this.scale = scale;
            this.yaw = yaw;
            this.spawnTick = spawnTick;
            this.lifeTicks = lifeTicks;
            this.slot = slot;
            this.linkA = linkA;
            this.linkB = linkB;
        }

        boolean isLink() {
            return this.linkA >= 0 && this.linkB >= 0;
        }
    }

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
            EFFECTS.remove(0);
        }
        int slot = (nextSlot++) & 15;
        EFFECTS.add(new Effect(pos, slug, scale, yaw, minecraft.level.getGameTime(), lifeTicks, slot, -1, -1));
    }

    /** 在两个实体之间拉伸播放（链子类特效）。位置每帧从实体实时读取。 */
    public static void spawnLink(int entityA, int entityB, String slug, float scale, int lifeTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || slug == null) {
            return;
        }
        if (currentLevel != minecraft.level) {
            EFFECTS.clear();
            currentLevel = minecraft.level;
        }
        if (EFFECTS.size() >= MAX_EFFECTS) {
            EFFECTS.remove(0);
        }
        int slot = (nextSlot++) & 15;
        EFFECTS.add(new Effect(Vec3.ZERO, slug, scale, 0.0f, minecraft.level.getGameTime(),
                lifeTicks, slot, entityA, entityB));
    }

    public static void clear() {
        EFFECTS.clear();
        BASE_BATCHES.clear();
        LIGHT_BATCHES.clear();
        currentLevel = null;
        lastArcTick = Long.MIN_VALUE;
        arcRotation = 0;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return;
        }
        if (EFFECTS.isEmpty()) {
            return;
        }
        if (currentLevel != minecraft.level) {
            clear();
            currentLevel = minecraft.level;
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick();
        long gameTime = minecraft.level.getGameTime();
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

        BASE_BATCHES.clear();
        LIGHT_BATCHES.clear();

        Iterator<Effect> iterator = EFFECTS.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            int age = (int) Math.max(0L, gameTime - effect.spawnTick);

            Optional<PixelfxMetadata> metadata = PixelfxAssets.metadata(effect.slug);
            if (metadata.isEmpty()) {
                iterator.remove();
                continue;
            }
            PixelfxMetadata meta = metadata.get();
            int totalLife = effect.lifeTicks > 0 ? effect.lifeTicks : meta.lifeTicks();

            if (age >= totalLife) {
                iterator.remove();
                continue;
            }

            if (effect.isLink()) {
                // link 模式的位置来自实体，实体没了就提前收掉
                ClientLevel clientLevel = minecraft.level;
                if (clientLevel == null) {
                    continue;
                }
                Entity a = clientLevel.getEntity(effect.linkA);
                Entity b = clientLevel.getEntity(effect.linkB);
                if (a == null || b == null || !a.isAlive() || !b.isAlive()) {
                    iterator.remove();
                    continue;
                }
            } else if (effect.pos == null || !isFinite(effect.pos)) {
                iterator.remove();
                continue;
            } else if (effect.pos.distanceToSqr(cameraPos) > maxDistanceSq) {
                continue;
            }

            try {
                collectEffectQuads(effect, meta, camera, cameraPos, poseStack, age, totalLife, partialTick);
            } catch (RuntimeException e) {
                String key = "render:" + effect.slug;
                if (WARNED.add(key)) {
                    GalBoss.LOGGER.warn("[pixelfx] 准备特效 {} 顶点时出错，后续将不再报告: {}",
                            effect.slug, e.toString());
                }
            }
        }

        if (!BASE_BATCHES.isEmpty() || !LIGHT_BATCHES.isEmpty()) {
            flushBatches();
        }

        // link 特效额外叠一层「噼啪电弧」——贴图是静止的，不加这层读起来像根发光绳子。
        if (gameTime != lastArcTick) {
            lastArcTick = gameTime;
            if (gameTime % ARC_INTERVAL_TICKS == 0) {
                drawLinkArcs(minecraft.level, partialTick);
            }
        }
    }

    /**
     * 给 link 特效叠一层每 tick 重随机的折线电弧。
     *
     * <p>画法照搬 Octo-Studios/relics 的 {@code ParticleUtils.createLightning}：
     * 把两点按「每格 2.5 段」切分，中间点随机抖动，再沿每段补粒子。
     * 关键差别是**这里在客户端画**——服务端每 tick 发几百个 sendParticles 包会直接打爆带宽。
     *
     * <p>同时最多画 {@link #MAX_ARC_LINKS} 条，用 {@link #arcRotation} 轮转，
     * 保证 12 条链也不会一帧全铺满。
     */
    private static void drawLinkArcs(ClientLevel level, float partialTick) {
        List<Effect> links = new ArrayList<>();
        for (Effect effect : EFFECTS) {
            if (effect.isLink()) {
                links.add(effect);
            }
        }
        if (links.isEmpty()) {
            return;
        }

        int drawn = 0;
        int size = links.size();
        for (int i = 0; i < size && drawn < MAX_ARC_LINKS; i++) {
            Effect effect = links.get((arcRotation + i) % size);
            Entity a = level.getEntity(effect.linkA);
            Entity b = level.getEntity(effect.linkB);
            if (a == null || b == null || !a.isAlive() || !b.isAlive()) {
                continue;
            }
            Vec3 from = a.getPosition(partialTick).add(0.0, a.getBbHeight() * 0.5, 0.0);
            Vec3 to = b.getPosition(partialTick).add(0.0, b.getBbHeight() * 0.5, 0.0);
            arcParticles(level, from, to);
            drawn++;
        }
        arcRotation = (arcRotation + MAX_ARC_LINKS) % Math.max(1, size);
    }

    /** 一条折线电弧：分段 → 中间点抖动 → 每段补粒子。两端不抖，保证贴住目标。 */
    private static void arcParticles(ClientLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < 0.15 || distance > 48.0) {
            return;
        }
        int segments = Math.max(2, (int) Math.round(distance * 2.5));
        Vec3 previous = from;

        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3 next = from.add(delta.scale(t));
            if (i < segments) {
                next = next.add(
                        (level.random.nextDouble() * 2.0 - 1.0) * ARC_JITTER,
                        (level.random.nextDouble() * 2.0 - 1.0) * ARC_JITTER,
                        (level.random.nextDouble() * 2.0 - 1.0) * ARC_JITTER);
            }
            Vec3 step = next.subtract(previous);
            int amount = Math.max(1, (int) Math.ceil(step.length() * ARC_PARTICLES_PER_BLOCK));
            for (int k = 0; k < amount; k++) {
                Vec3 at = previous.add(step.scale(k / (double) amount));
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 0.0, 0.0, 0.0);
                if ((i + k) % 3 == 0) {
                    level.addParticle(SOUL_DUST, at.x, at.y, at.z, 0.0, 0.0, 0.0);
                }
            }
            previous = next;
        }
    }

    private static void collectEffectQuads(Effect effect, PixelfxMetadata meta, Camera camera,
                                           Vec3 cameraPos, PoseStack poseStack,
                                           int age, int totalLife, float partialTick) {
        double seconds = (age + partialTick) / 20.0;
        int frame = (int) Math.floor(seconds * meta.fps());
        if (meta.loop()) {
            frame = Math.floorMod(frame, meta.frames());
        } else {
            frame = Mth.clamp(frame, 0, meta.frames() - 1);
        }
        int col = frame % meta.cols();
        int row = frame / meta.cols();

        float insetU = 0.5f / meta.textureWidth();
        float insetV = 0.5f / meta.textureHeight();
        float u0 = col / (float) meta.cols() + insetU;
        float u1 = (col + 1) / (float) meta.cols() - insetU;
        float v0 = row / (float) meta.rows() + insetV;
        float v1 = (row + 1) / (float) meta.rows() - insetV;

        float frameW = meta.frameW();
        float frameH = meta.frameH();
        float[] pixelU = {0.0f, 0.0f, frameW, frameW};
        float[] pixelV = {0.0f, frameH, frameH, 0.0f};
        float[] texU = {u0, u0, u1, u1};
        float[] texV = {v0, v1, v1, v0};

        float progress = Mth.clamp((age + partialTick) / (float) Math.max(1, totalLife), 0.0f, 1.0f);
        float alphaEnv = meta.envelopeAlpha(progress);
        if (alphaEnv <= 0.004f) {
            return;
        }

        float[] vx = new float[4];
        float[] vy = new float[4];
        float[] vz = new float[4];

        if (effect.isLink()) {
            if (!computeLinkCorners(effect, meta, cameraPos, poseStack, partialTick,
                    pixelU, pixelV, vx, vy, vz)) {
                return;
            }
        } else {
            computePlacedCorners(effect, meta, camera, cameraPos, poseStack, pixelU, pixelV, vx, vy, vz);
        }
        pushQuad(meta, progress, alphaEnv, vx, vy, vz, texU, texV);
    }

    /** billboard / ground 两种摆放方式：推矩阵后把四个角变换到相机空间。 */
    private static void computePlacedCorners(Effect effect, PixelfxMetadata meta, Camera camera,
                                             Vec3 cameraPos, PoseStack poseStack,
                                             float[] pixelU, float[] pixelV,
                                             float[] vx, float[] vy, float[] vz) {
        float unitsPerPixel = meta.defaultScale() * (effect.scale > 0.0f ? effect.scale : 1.0f);
        // 地面贴合特效增加微米级槽位高度差，避免多重叠加法阵/冲击波共面闪烁
        double groundJitterY = meta.billboard() ? 0.0 : (effect.slot * 0.0025);

        poseStack.pushPose();
        try {
            poseStack.translate(effect.pos.x - cameraPos.x,
                    effect.pos.y + groundJitterY - cameraPos.y,
                    effect.pos.z - cameraPos.z);
            if (meta.billboard()) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
                // billboard 的朝向被相机覆盖，yaw 参数无效；素材自身的构图朝向只能靠 roll 表达。
                // 正角 = 屏幕平面内逆时针（本地 +Z 指向相机）。
                if (meta.roll() != 0.0f) {
                    poseStack.mulPose(Axis.ZP.rotationDegrees(meta.roll()));
                }
            } else {
                poseStack.mulPose(Axis.YP.rotationDegrees(-effect.yaw));
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
            }
            Matrix4f matrix = poseStack.last().pose();

            org.joml.Vector4f vec = new org.joml.Vector4f();
            for (int i = 0; i < 4; i++) {
                float localX = (pixelU[i] - meta.pivotX()) * unitsPerPixel;
                float localY = (meta.pivotY() - pixelV[i]) * unitsPerPixel;
                vec.set(localX, localY, 0.0f, 1.0f).mul(matrix);
                vx[i] = vec.x();
                vy[i] = vec.y();
                vz[i] = vec.z();
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * link 摆放：贴图左端（pivot）贴住实体 A，右端贴住实体 B，横向铺满两点距离。
     *
     * <p>在世界空间算出四个角，再用 {@code poseStack} 上的视图矩阵变换到相机空间——
     * 这一步不能省：{@link #flushBatches} 最后把 modelview 设成单位矩阵，
     * 顶点必须已经是相机空间坐标，否则整片会被画到屏幕外。
     *
     * <p>「上」方向取 {@code dir × 视线} 再叉乘一次，保证面片尽量正对相机（ribbon billboard）。
     */
    private static boolean computeLinkCorners(Effect effect, PixelfxMetadata meta, Vec3 cameraPos,
                                              PoseStack poseStack, float partialTick,
                                              float[] pixelU, float[] pixelV,
                                              float[] vx, float[] vy, float[] vz) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) {
            return false;
        }
        Entity a = clientLevel.getEntity(effect.linkA);
        Entity b = clientLevel.getEntity(effect.linkB);
        if (a == null || b == null) {
            return false;
        }

        // 取身体中段，避免从脚底连到头顶
        Vec3 from = a.getPosition(partialTick).add(0.0, a.getBbHeight() * 0.5, 0.0);
        Vec3 to = b.getPosition(partialTick).add(0.0, b.getBbHeight() * 0.5, 0.0);
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.05) {
            return false;
        }
        Vec3 dir = delta.scale(1.0 / length);

        Vec3 mid = from.add(delta.scale(0.5));
        Vec3 toCam = cameraPos.subtract(mid);
        if (toCam.lengthSqr() < 1.0E-6) {
            return false;
        }
        Vec3 side = dir.cross(toCam.normalize());
        if (side.lengthSqr() < 1.0E-4) {
            // 连线几乎与视线平行，退化时随便挑一个垂直方向
            side = dir.cross(new Vec3(0.0, 1.0, 0.0));
            if (side.lengthSqr() < 1.0E-4) {
                side = new Vec3(1.0, 0.0, 0.0);
            }
        }
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();

        // 等比缩放：帧宽铺满两点距离的同时，纵向用同一个比例。
        // 否则距离一长，链节会被横向拉成椭圆（链环、扣环这类有明确形状的素材尤其明显）。
        // 代价是长距离时整条链会变粗——对魂链这种「被拉紧的索」反而是对的。
        float unitsPerPixel = (float) (length / Math.max(1, meta.frameW()))
                * (effect.scale > 0.0f ? effect.scale : 1.0f);
        float unitsPerPixelX = unitsPerPixel;
        float unitsPerPixelY = unitsPerPixel;

        // 关键：世界空间 → 相机空间。poseStack 上带着相机的 yaw/pitch 旋转，
        // 不乘这一步顶点会落在错误坐标系里（画面外 / 退化成点）。
        Matrix4f view = poseStack.last().pose();
        org.joml.Vector4f vec = new org.joml.Vector4f();
        for (int i = 0; i < 4; i++) {
            float lx = (pixelU[i] - meta.pivotX()) * unitsPerPixelX;
            float ly = (meta.pivotY() - pixelV[i]) * unitsPerPixelY;
            Vec3 world = from.add(dir.scale(lx)).add(up.scale(ly));
            vec.set((float) (world.x - cameraPos.x),
                    (float) (world.y - cameraPos.y),
                    (float) (world.z - cameraPos.z),
                    1.0f).mul(view);
            vx[i] = vec.x();
            vy[i] = vec.y();
            vz[i] = vec.z();
        }
        return true;
    }

    /** 把四个角按 (贴图, 混合模式) 入桶：基础层一个桶，加色高光层另一个桶。 */
    private static void pushQuad(PixelfxMetadata meta, float progress, float alphaEnv,
                                 float[] vx, float[] vy, float[] vz,
                                 float[] texU, float[] texV) {
        float tintR = ((meta.tint() >> 16) & 0xFF) / 255.0f;
        float tintG = ((meta.tint() >> 8) & 0xFF) / 255.0f;
        float tintB = (meta.tint() & 0xFF) / 255.0f;

        // 1) 基础层（预乘 alpha 需同步衰减 RGB 与 A，防止消散期泛灰边）
        BaseBlend blend = meta.premultiplied() ? BaseBlend.PREMULTIPLIED_ALPHA : BaseBlend.STRAIGHT_ALPHA;
        float baseR = meta.premultiplied() ? tintR * alphaEnv : tintR;
        float baseG = meta.premultiplied() ? tintG * alphaEnv : tintG;
        float baseB = meta.premultiplied() ? tintB * alphaEnv : tintB;
        BASE_BATCHES.computeIfAbsent(new BatchKey(meta.texture(), blend), k -> new ArrayList<>())
                .add(new QuadVertexData(
                        vx[0], vy[0], vz[0], texU[0], texV[0],
                        vx[1], vy[1], vz[1], texU[1], texV[1],
                        vx[2], vy[2], vz[2], texU[2], texV[2],
                        vx[3], vy[3], vz[3], texU[3], texV[3],
                        baseR, baseG, baseB, alphaEnv));

        // 2) 加色高光层
        if (meta.lightTexture() != null) {
            float strength = meta.lightStrength() * meta.lightSurgeMultiplier(progress) * alphaEnv;
            LIGHT_BATCHES.computeIfAbsent(meta.lightTexture(), k -> new ArrayList<>())
                    .add(new QuadVertexData(
                            vx[0], vy[0], vz[0], texU[0], texV[0],
                            vx[1], vy[1], vz[1], texU[1], texV[1],
                            vx[2], vy[2], vz[2], texU[2], texV[2],
                            vx[3], vy[3], vz[3], texU[3], texV[3],
                            tintR * strength, tintG * strength, tintB * strength, alphaEnv));
        }
    }

    private static void flushBatches() {
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            if (WARNED.add("shader")) {
                GalBoss.LOGGER.warn("[pixelfx] position_tex_color shader 尚未就绪，跳过世界特效渲染");
            }
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0f, -10.0f);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            // 阶段一：绘制全部基础层
            for (Map.Entry<BatchKey, List<QuadVertexData>> entry : BASE_BATCHES.entrySet()) {
                BatchKey key = entry.getKey();
                if (!PixelfxAssets.bindTexture(key.texture())) {
                    continue;
                }
                if (key.blend() == BaseBlend.PREMULTIPLIED_ALPHA) {
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                drawBatch(entry.getValue());
            }

            // 阶段二：统一绘制全部加色高光层（先同步 shader.apply() 锁定 BlendMode）
            if (!LIGHT_BATCHES.isEmpty()) {
                shader.apply();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                for (Map.Entry<ResourceLocation, List<QuadVertexData>> entry : LIGHT_BATCHES.entrySet()) {
                    if (!PixelfxAssets.bindTexture(entry.getKey())) {
                        continue;
                    }
                    drawBatch(entry.getValue());
                }
            }
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.polygonOffset(0.0f, 0.0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static void drawBatch(List<QuadVertexData> quads) {
        if (quads.isEmpty()) {
            return;
        }
        BufferBuilder builder = beginQuads();
        for (QuadVertexData q : quads) {
            builder.vertex(q.x0, q.y0, q.z0).uv(q.u0, q.v0).color(q.r, q.g, q.b, q.a).endVertex();
            builder.vertex(q.x1, q.y1, q.z1).uv(q.u1, q.v1).color(q.r, q.g, q.b, q.a).endVertex();
            builder.vertex(q.x2, q.y2, q.z2).uv(q.u2, q.v2).color(q.r, q.g, q.b, q.a).endVertex();
            builder.vertex(q.x3, q.y3, q.z3).uv(q.u3, q.v3).color(q.r, q.g, q.b, q.a).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private static BufferBuilder beginQuads() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        if (builder.building()) {
            try {
                builder.end();
            } catch (RuntimeException ignored) {
            }
        }
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        return builder;
    }

    private static boolean isFinite(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
