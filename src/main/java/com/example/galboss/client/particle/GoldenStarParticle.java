package com.example.galboss.client.particle;

import com.example.galboss.GalBoss;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;

/**
 * 「命中瞬间」的星光爆闪：4 角星形，加色混合，先放大再收回。
 *
 * <p>设计图第 1 阶段（0~2 tick）用的就是这种星芒；这里做成 6 tick 生命：
 * 前 2 tick 迅速放大到 1.6 倍，之后收缩淡出。
 */
public class GoldenStarParticle extends SingleQuadParticle {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/particle/burst_star.png");

    public static final ParticleRenderType RENDER_TYPE = GalBossParticleRenderTypes.additive(TEXTURE);

    private final float maxSize;

    protected GoldenStarParticle(ClientLevel level, double x, double y, double z, float size) {
        super(level, x, y, z);
        this.maxSize = size;
        this.quadSize = size * 0.35F;
        this.lifetime = 6;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.alpha = 1.0F;
        // 顺手给一点点随机旋转，几颗星不会一模一样
        this.oRoll = this.roll = (this.random.nextFloat() - 0.5F) * 0.6F;
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
        float t = this.age / (float) this.lifetime;
        // 0→1 tick 放大，之后收缩
        float grow = t < 0.35F ? (t / 0.35F) : (1.0F - (t - 0.35F) / 0.65F);
        this.quadSize = this.maxSize * (0.35F + 0.65F * Math.max(0.0F, grow));
        this.alpha = Math.max(0.0F, 1.0F - t * t);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

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

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            // 星芒大小：0.6~0.95 格。设计图里这颗星是整段特效最亮的一下，要压得住场
            return new GoldenStarParticle(level, x, y, z, 0.6F + level.random.nextFloat() * 0.35F);
        }
    }
}
