package com.example.galboss.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 狙击的「红外线」世界渲染：一条从射手眼睛指向目标的红色细线，**穿过目标**再延伸一点。
 *
 * <h2>为什么不用粒子</h2>
 * 粒子走深度测试，会被墙、被玩家模型挡住 —— 而瞄准镜那种红外线恰恰要求「贯穿一切」。
 * 所以这里自己画四边形，并且<b>关掉深度测试</b>：
 * 线会盖在墙、地面、玩家身上，正好是"从瞄准镜里看出去那条红线"的观感。
 *
 * <h2>怎么画</h2>
 * 一个四边形 = 线宽方向 × 两个端点。线宽方向取「线段方向 × 视线方向的单位叉乘」，
 * 这样从任何角度看都是一条等宽的带子（不随视角变细）。
 *
 * <p>矩阵约定与 {@link PixelfxRenderer} 完全一致（AFTER_PARTICLES 阶段里
 * poseStack 只有相机旋转、没有平移，顶点里要自己减相机位置；ModelView 压成单位阵）。
 *
 * <p>纯客户端类。
 */
public final class SniperBeamRenderer {

    /** 线宽（格）：0.035 ≈ 半个像素宽，远了看就是一条细线。 */
    private static final float WIDTH = 0.035f;

    /** 线在目标之后多延伸多少格（"贯穿"的观感）。 */
    private static final double OVERSHOOT = 2.5;

    private static final int MAX_BEAMS = 16;

    private static final List<Beam> BEAMS = new ArrayList<>();

    private static Object currentLevel;

    private SniperBeamRenderer() {
    }

    private static final class Beam {
        final Vec3 from;
        final Vec3 to;
        final int lifeTicks;
        final long bornTick;

        Beam(Vec3 from, Vec3 to, int lifeTicks, long bornTick) {
            this.from = from;
            this.to = to;
            this.lifeTicks = lifeTicks;
            this.bornTick = bornTick;
        }
    }

    /** 收到网络包时登记一条线。 */
    public static void push(Vec3 from, Vec3 to, int ticks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || from == null || to == null) {
            return;
        }
        if (currentLevel != minecraft.level) {
            BEAMS.clear();
            currentLevel = minecraft.level;
        }
        if (BEAMS.size() >= MAX_BEAMS) {
            BEAMS.remove(0);
        }
        BEAMS.add(new Beam(from, to, Math.max(1, ticks), minecraft.level.getGameTime()));
    }

    public static void clear() {
        BEAMS.clear();
        currentLevel = null;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            BEAMS.clear();
            currentLevel = null;
            return;
        }
        if (currentLevel != minecraft.level) {
            BEAMS.clear();
            currentLevel = minecraft.level;
            return;
        }
        if (BEAMS.isEmpty()) {
            return;
        }

        long gameTime = minecraft.level.getGameTime();
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        Iterator<Beam> iterator = BEAMS.iterator();
        while (iterator.hasNext()) {
            Beam beam = iterator.next();
            int age = (int) (gameTime - beam.bornTick);
            if (age < 0 || age > beam.lifeTicks) {
                iterator.remove();
                continue;
            }
            if (beam.from.distanceToSqr(cameraPos) > 160.0 * 160.0) {
                continue;
            }
            drawBeam(beam, cameraPos, poseStack, age / (float) Math.max(1, beam.lifeTicks));
        }
    }

    private static void drawBeam(Beam beam, Vec3 cameraPos, PoseStack poseStack, float progress) {
        Vec3 dir = beam.to.subtract(beam.from);
        double length = dir.length();
        if (length < 0.05) {
            return;
        }
        dir = dir.scale(1.0 / length);
        Vec3 end = beam.to.add(dir.scale(OVERSHOOT));

        // 线宽方向：线段方向 × 视线方向，与两者都垂直 → 从任何角度看都是等宽带子
        Vec3 view = new Vec3(0.0, -0.25, 1.0).normalize();
        Vec3 toCam = cameraPos.subtract(beam.to);
        if (toCam.lengthSqr() > 1.0E-4) {
            view = toCam.normalize();
        }
        Vec3 side = dir.cross(view);
        if (side.lengthSqr() < 1.0E-6) {
            side = dir.cross(new Vec3(0.0, 1.0, 0.0));
        }
        if (side.lengthSqr() < 1.0E-6) {
            return;
        }
        side = side.normalize().scale(WIDTH * 0.5);

        // 淡入淡出 + 低频闪烁：先快速亮起，最后 40% 逐渐消失
        float fadeIn = Mth.clamp(progress * 6.0f, 0.0f, 1.0f);
        float fadeOut = Mth.clamp((1.0f - progress) / 0.4f, 0.0f, 1.0f);
        float flicker = 0.75f + 0.25f * Mth.sin((System.currentTimeMillis() % 100000L) / 60.0f);
        float alpha = Mth.clamp(fadeIn * fadeOut * flicker, 0.0f, 1.0f);
        if (alpha <= 0.02f) {
            return;
        }

        poseStack.pushPose();
        try {
            poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            Matrix4f matrix = poseStack.last().pose();

            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);

            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushPose();
            modelViewStack.setIdentity();
            RenderSystem.applyModelViewMatrix();
            try {
                BufferBuilder builder = Tesselator.getInstance().getBuilder();
                if (builder.building()) {
                    builder.end();
                }
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                // 起点稍窄、终点（贯穿处）更亮：读起来像"从枪口射出去"
                addVertex(builder, matrix, beam.from, side.scale(0.6), alpha * 0.75f);
                addVertex(builder, matrix, end, side.scale(0.2), alpha * 0.12f);
                addVertex(builder, matrix, end, side.scale(-0.2), alpha * 0.12f);
                addVertex(builder, matrix, beam.from, side.scale(-0.6), alpha * 0.75f);
                BufferUploader.drawWithShader(builder.end());
            } finally {
                modelViewStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        } finally {
            poseStack.popPose();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static void addVertex(BufferBuilder builder, Matrix4f matrix, Vec3 point, Vec3 offset, float alpha) {
        builder.vertex(matrix,
                        (float) (point.x + offset.x),
                        (float) (point.y + offset.y),
                        (float) (point.z + offset.z))
                .color(1.0f, 0.12f, 0.16f, alpha)
                .endVertex();
    }
}
