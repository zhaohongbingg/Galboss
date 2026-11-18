package com.example.galboss.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 蝴蝶粒子公共实现：漂浮运动 + 逐帧拍翅 + 自成一套渲染类型（不走原版粒子图集）。
 *
 * <h2>两层结构</h2>
 * 单张图做不出「发光轮廓」：本体要<b>普通 alpha 混合</b>（保留硬边和镂空），辉光要<b>加色混合</b>
 * （{@code SRC_ALPHA, ONE}）才能透出背景形成光晕。一个粒子一次 render 只能用一个 RenderType，
 * 所以拆成两只：
 * <ul>
 *   <li>{@link ButterflyParticle} —— 本体，alpha 混合，逐帧拍翅；</li>
 *   <li>{@link ButterflyGlowParticle} —— 辉光，加色混合，用同一帧序号对齐。</li>
 * </ul>
 * 两只在同一位置生成（本体首 tick 时挂上辉光），看起来就是「发光的蝴蝶」。
 *
 * <h2>贴图与换帧</h2>
 * 图集布局由 {@link ButterflySprite} 描述（列/行/单帧像素/每帧 tick），当前用的是
 * {@link Butterflies#ORANGE_BODY}（ER 橙蝶，2 帧）。换素材只换 sprite，不用改这里的逻辑。
 * 贴图不进粒子图集：自定义 {@link ParticleRenderType} 里直接 {@code setShaderTexture}，
 * 注册走 Forge 的 {@code registerSpecial}（非 JSON 粒子）。
 *
 * <h2>其它</h2>
 * <ul>
 *   <li>自发光：{@link LightTexture#FULL_BRIGHT}，夜里也亮；</li>
 *   <li>运动：不用原版重力/摩擦，正弦漂移 + 缓慢盘旋，绕出生点小幅游荡；</li>
 *   <li>最近邻过滤 + 关 mipmap：像素画必须，否则被插值糊掉。</li>
 * </ul>
 */
public abstract class AbstractButterflyParticle extends SingleQuadParticle {

    /** 这一只用的图集布局（本体 / 辉光各一份，帧序必须一致）。 */
    protected final ButterflySprite sprite;

    /** 随机相位：让同一时刻的蝶群不在同一片翅膀上（错帧）。 */
    protected final int phaseOffset;

    /** 螺旋前进的水平方向（每只随机，整群看起来才不是齐步走）。 */
    protected final double driftAngle;
    /** 出生点：螺旋中心从这里"向前推"。 */
    private final double originX;
    private final double originY;
    private final double originZ;
    /** 螺旋半径（格）。 */
    private final double orbitRadius;
    /** 绕圈角速度（rad/tick）。 */
    private final double orbitSpeed;
    /** 螺旋中心的前进速度（格/tick）。 */
    private final double forwardSpeed;
    /** 上升速度（格/tick）。 */
    private final double riseSpeed;
    /** 起始转角（随机，避免同一批蝶同相位叠成一条线）。 */
    private final double orbitPhase;

    protected AbstractButterflyParticle(ClientLevel level, double x, double y, double z,
                                        double vx, double vy, double vz,
                                        int phaseOffset, ButterflySprite sprite) {
        super(level, x, y, z);
        this.sprite = sprite;
        this.setSize(0.2F, 0.2F);
        // 尺寸随机：0.22~0.45 格（原先 0.45~0.9 太大 —— 一只蝶快有一个玩家的头宽，
        // 尾迹叠起来糊成一条带子；缩小一半后单只蝶形清楚，也不会糊）
        this.quadSize = 0.22F + this.random.nextFloat() * 0.23F;
        this.lifetime = 80 + this.random.nextInt(120);
        this.phaseOffset = phaseOffset;
        this.driftAngle = this.random.nextDouble() * Math.PI * 2.0;
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.orbitRadius = 0.10 + this.random.nextDouble() * 0.10;
        this.orbitSpeed = 0.30 + this.random.nextDouble() * 0.15;
        this.forwardSpeed = 0.020 + this.random.nextDouble() * 0.015;
        this.riseSpeed = 0.004 + this.random.nextDouble() * 0.008;
        this.orbitPhase = this.random.nextDouble() * Math.PI * 2.0;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = 1.0F;
        this.gCol = 1.0F;
        this.bCol = 1.0F;
        this.alpha = 0.0F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }

        // 螺旋前进：绕自己的中心转小圈（半径 orbitRadius），中心同时沿 driftAngle 方向匀速前进。
        // 只绕圈不前进会像"原地打转的风扇"，只前进不绕圈就是一条直线 —— 两者叠起来才读得出"边转边飞"。
        double orbit = this.orbitPhase + this.age * this.orbitSpeed;
        double advance = this.forwardSpeed * this.age;
        this.x = this.originX + Math.cos(this.driftAngle) * advance + Math.cos(orbit) * this.orbitRadius;
        this.z = this.originZ + Math.sin(this.driftAngle) * advance + Math.sin(orbit) * this.orbitRadius;
        this.y = this.originY + this.riseSpeed * this.age
                + Math.sin(this.age * 0.18 + this.orbitPhase) * 0.035;
        this.setPos(this.x, this.y, this.z);

        // 淡入 / 淡出
        float life = this.age / (float) this.lifetime;
        if (life < 0.10F) {
            this.alpha = life / 0.10F;
        } else if (life > 0.82F) {
            this.alpha = (1.0F - life) / 0.18F;
        } else {
            this.alpha = 1.0F;
        }
    }

    /** 自发光：不吃场景光照，暗处/夜里也是暖色。 */
    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize;
    }

    // ------------------------------------------------------------ 帧选择

    protected int frameIndex() {
        return Math.floorMod(this.age / this.sprite.frameTicks() + this.phaseOffset, this.sprite.frames());
    }

    protected float frameU0() {
        return (frameIndex() % this.sprite.cols()) * this.sprite.frameU();
    }

    protected float frameV0() {
        return (frameIndex() / this.sprite.cols()) * this.sprite.frameV();
    }

    @Override
    protected float getU0() {
        return frameU0() + this.sprite.insetU();
    }

    @Override
    protected float getU1() {
        return frameU0() + this.sprite.frameU() - this.sprite.insetU();
    }

    @Override
    protected float getV0() {
        return frameV0() + this.sprite.insetV();
    }

    @Override
    protected float getV1() {
        return frameV0() + this.sprite.frameV() - this.sprite.insetV();
    }

    /**
     * 建一个渲染类型：绑定指定贴图 + 指定混合模式 + 最近邻采样。
     *
     * @param texture  用哪张图集
     * @param additive true = 加色（{@code SRC_ALPHA, ONE}，做辉光）；false = 普通 alpha 混合
     */
    protected static ParticleRenderType createRenderType(ResourceLocation texture, boolean additive) {
        return new ParticleRenderType() {
            private boolean filtered;

            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                if (!this.filtered) {
                    // 像素画必须最近邻、关 mipmap；否则会被插值糊掉
                    textureManager.getTexture(texture).setFilter(false, false);
                    this.filtered = true;
                }
                RenderSystem.depthMask(false);
                RenderSystem.enableBlend();
                // 顺序：先 setShader（它会应用 particle.json 自带的 blend），再覆盖 blendFunc。
                // 反过来设的话加色会被 shader 的默认 blend 覆盖掉（辉光变灰、变暗）。
                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderTexture(0, texture);
                if (additive) {
                    RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }

            @Override
            public void end(Tesselator tesselator) {
                tesselator.end();
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }

            @Override
            public String toString() {
                return "galboss_butterfly" + (additive ? "_glow" : "") + "@" + texture;
            }
        };
    }
}
