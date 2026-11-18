package com.example.galboss.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;

/**
 * 血珠 / 血花（素材：ER 粒子表 {@code blood_drop} / {@code blood_splash}，各 1 帧 32×32）。
 *
 * <p>用途：狙击命中后从被击中的玩家身上喷出来的血。两张图分工：
 * <ul>
 *   <li>{@code blood_drop} —— 血珠：小、受重力、12~22 tick 落地消失；</li>
 *   <li>{@code blood_splash} —— 血花：大一圈、初速更散、更短命，用来表现"喷"的那一下。</li>
 * </ul>
 *
 * <p>用普通 alpha 混合（血不该发光），自发光到 FULL_BRIGHT 是为了夜里也能看见 ——
 * 这一点和蝴蝶不同：血是"暗"的东西，所以颜色上不再乘任何色偏，贴图自带暗红。
 */
public class BloodParticle extends SingleQuadParticle {

    /** 血珠。 */
    private static final ResourceLocation DROP =
            ResourceLocation.fromNamespaceAndPath("galboss", "textures/particle/blood_drop.png");
    /** 血花。 */
    private static final ResourceLocation SPLASH =
            ResourceLocation.fromNamespaceAndPath("galboss", "textures/particle/blood_splash.png");

    public static final ParticleRenderType DROP_RENDER = GalBossParticleRenderTypes.alpha(DROP);
    public static final ParticleRenderType SPLASH_RENDER = GalBossParticleRenderTypes.alpha(SPLASH);

    private final ParticleRenderType renderType;

    protected BloodParticle(ClientLevel level, double x, double y, double z,
                            double vx, double vy, double vz, boolean splash) {
        super(level, x, y, z);
        this.renderType = splash ? SPLASH_RENDER : DROP_RENDER;
        this.setSize(0.2F, 0.2F);
        this.quadSize = splash ? 0.34F + this.random.nextFloat() * 0.16F
                : 0.12F + this.random.nextFloat() * 0.10F;
        this.lifetime = splash ? 10 + this.random.nextInt(6) : 12 + this.random.nextInt(11);
        this.gravity = splash ? 0.6F : 0.9F;
        this.friction = 0.97F;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = 1.0F;
        this.gCol = 1.0F;
        this.bCol = 1.0F;
        this.alpha = 1.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }
        // 血珠：后半段淡出（不是闪没），落地速度归零后基本就没了
        float life = this.age / (float) this.lifetime;
        this.alpha = life > 0.6F ? (1.0F - life) / 0.4F : 1.0F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.renderType;
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    // 两张图都是单帧整图，UV 直接铺满（不涉及图集子矩形）
    @Override
    protected float getU0() {
        return 0.0F;
    }

    @Override
    protected float getU1() {
        return 1.0F;
    }

    @Override
    protected float getV0() {
        return 0.0F;
    }

    @Override
    protected float getV1() {
        return 1.0F;
    }

    /** 血珠工厂。 */
    public static class DropProvider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new BloodParticle(level, x, y, z, vx, vy, vz, false);
        }
    }

    /** 血花工厂。 */
    public static class SplashProvider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new BloodParticle(level, x, y, z, vx, vy, vz, true);
        }
    }
}
