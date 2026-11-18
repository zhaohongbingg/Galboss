package com.example.galboss.client.particle;

import com.example.galboss.particle.ModParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 「蝴蝶命中」爆炸后飞出的蝶：命中点为中心螺旋外扩、缓慢上升、20 tick 内淡出。
 *
 * <p>素材与飞行尾迹<b>同一只</b> ER 橙蝶（{@link Butterflies#ORANGE_BODY}，2 帧拍翅 × 32px）：
 * 打出去的那只和砸在身上的那只同一个形象，整段读起来才是「一个技能」。
 * 帧长直接用 sprite 自带的（3 tick），20 tick 生命内拍 3 轮。
 *
 * <p>混合用<b>普通 alpha</b>：橙蝶是实心像素，走加色会烧成白斑、丢掉蝶形；
 * 闪光交给同时发出的 {@code burst_dot} 星屑。
 *
 * <p>轨迹与碎屑照原设计图 4 阶段：每 2 tick 身后留一颗余晖光点画螺旋线，
 * 奇数 tick 在翅膀外圈撒一颗星屑把每只蝶包住。
 *
 * <p><b>尺寸已按用户要求整体缩半</b>（0.30~0.45 → 0.16~0.24 格）。早先按设计图放大过一轮
 * （0.16~0.22 格在战斗距离只剩一个点），现在因为整只蝶换成了描边清晰的 ER 原画、
 * 加上尾迹也是同一只，缩小后仍然认得出蝶形；若某些距离上又显得太小，
 * 把构造器里的 {@code quadSize} 调回 0.30~0.45 即可（扩散半径不用动）。
 */
public class ButterflyBurstParticle extends SingleQuadParticle {

    /** 与飞行尾迹同一套精灵表（2 帧 × 32px，2 列 1 行）。 */
    private static final ButterflySprite SPRITE = Butterflies.ORANGE_BODY;

    public static final ParticleRenderType RENDER_TYPE =
            GalBossParticleRenderTypes.alpha(SPRITE.texture());

    /** 出生点（盘旋中心）。 */
    private final double originX;
    private final double originY;
    private final double originZ;
    /** 盘旋方向（+1 / -1）与角速度。 */
    private final double spin;
    private final double angle0;
    /** 出生半径（让蝶群一开始就散开，而不是挤在一点）。 */
    private final double radius0;
    /** 半径增长速度与上升速度（格 / tick）。 */
    private final double radialSpeed;
    private final double riseSpeed;

    protected ButterflyBurstParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.spin = this.random.nextBoolean() ? 1.0 : -1.0;
        this.angle0 = this.random.nextDouble() * Math.PI * 2.0;
        this.radius0 = 0.15 + this.random.nextDouble() * 0.15;
        this.radialSpeed = 0.050 + this.random.nextDouble() * 0.020;
        this.riseSpeed = 0.010 + this.random.nextDouble() * 0.012;
        this.quadSize = 0.16F + this.random.nextFloat() * 0.08F;
        this.lifetime = 20;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.alpha = 0.0F;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        // 不再乘金色: 橙蝶贴图自带配色，乘任何色偏都会让它偏离 ER 原画
        this.rCol = 1.0F;
        this.gCol = 1.0F;
        this.bCol = 1.0F;
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
        // 螺旋线：角度累计、半径渐大、缓慢上升（角速度从 0.15 缓增，20 tick 约转 0.5 圈）
        double angle = this.angle0 + this.spin * (0.15 * this.age + 0.006 * this.age * this.age);
        double radius = this.radius0 + this.radialSpeed * this.age;
        this.x = this.originX + Math.cos(angle) * radius;
        this.z = this.originZ + Math.sin(angle) * radius;
        this.y = this.originY + this.riseSpeed * this.age;

        // 身后留一颗余晖光点，画出螺旋轨迹（单张蝶贴图做不出这部分）
        if (this.age % 2 == 0) {
            this.level.addParticle(ModParticles.BURST_DOT.get(), this.x, this.y, this.z,
                    0.0, 0.004, 0.0);
        }

        // 翅膀外圈的星屑：让每只蝶都被一层闪光包住
        if (this.age % 2 == 1) {
            double a = this.random.nextDouble() * Math.PI * 2.0;
            double r = 0.12 + this.random.nextDouble() * 0.18;
            this.level.addParticle(ModParticles.BURST_DOT.get(),
                    this.x + Math.cos(a) * r,
                    this.y + (this.random.nextDouble() - 0.4) * 0.30,
                    this.z + Math.sin(a) * r,
                    Math.cos(a) * 0.004, 0.006, Math.sin(a) * 0.004);
        }

        // 前 2 tick 淡入，最后 6 tick 淡出
        if (this.age <= 2) {
            this.alpha = this.age / 3.0F;
        } else if (this.age >= this.lifetime - 6) {
            this.alpha = (this.lifetime - this.age) / 6.0F;
        } else {
            this.alpha = 1.0F;
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    private int frameIndex() {
        return Math.floorMod(this.age / SPRITE.frameTicks(), SPRITE.frames());
    }

    @Override
    protected float getU0() {
        return (frameIndex() % SPRITE.cols()) * SPRITE.frameU() + SPRITE.insetU();
    }

    @Override
    protected float getU1() {
        return (frameIndex() % SPRITE.cols()) * SPRITE.frameU() + SPRITE.frameU() - SPRITE.insetU();
    }

    @Override
    protected float getV0() {
        return (frameIndex() / SPRITE.cols()) * SPRITE.frameV() + SPRITE.insetV();
    }

    @Override
    protected float getV1() {
        return (frameIndex() / SPRITE.cols()) * SPRITE.frameV() + SPRITE.frameV() - SPRITE.insetV();
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ButterflyBurstParticle(level, x, y, z);
        }
    }
}
