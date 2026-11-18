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
 * 「命中瞬间」的光环：俯视椭圆环，加色混合，快速向外扩张后淡出。
 *
 * <p>对应设计图素材里的「光环」，在 0~2 tick 与星光一起出现，10 tick 内扩到约 1.6 格。
 */
public class GoldenRingParticle extends SingleQuadParticle {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/particle/burst_ring.png");

    public static final ParticleRenderType RENDER_TYPE = GalBossParticleRenderTypes.additive(TEXTURE);

    private final float startSize;
    private final float endSize;

    protected GoldenRingParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.startSize = 0.22F;
        // 设计图第 1 阶段那圈光环有 1.5 格左右宽；旧版只扩到 0.72~0.84（约 0.8 格），偏小
        this.endSize = 1.00F + this.random.nextFloat() * 0.20F;
        this.quadSize = this.startSize;
        this.lifetime = 12;
        this.gravity = 0.0F;
        this.friction = 1.0F;
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
        float t = this.age / (float) this.lifetime;
        // 先快后慢地扩张（ease-out）
        float e = 1.0F - (1.0F - t) * (1.0F - t);
        this.quadSize = this.startSize + (this.endSize - this.startSize) * e;
        this.alpha = t < 0.15F ? t / 0.15F : Math.max(0.0F, 1.0F - (t - 0.15F) / 0.85F);
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
            return new GoldenRingParticle(level, x, y, z);
        }
    }
}
