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
 * 「金色蝴蝶攻击」的余晖光点：小圆光，加色混合，轻微上飘 + 闪烁。
 *
 * <p>对应设计图素材里的「光点」。数量 8~12 个，散在命中点周围，寿命 10~20 tick，
 * 与蝴蝶一起把特效"铺开"。
 */
public class GoldenDotParticle extends SingleQuadParticle {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/particle/burst_dot.png");

    public static final ParticleRenderType RENDER_TYPE = GalBossParticleRenderTypes.additive(TEXTURE);

    private final float baseSize;

    protected GoldenDotParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz) {
        super(level, x, y, z);
        this.baseSize = 0.06F + this.random.nextFloat() * 0.06F;
        this.quadSize = this.baseSize;
        // 设计图「生命周期 16~20 tick」，光点要能撑到蝶群盘旋结束（第 3 阶段）
        this.lifetime = 16 + this.random.nextInt(5);
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.alpha = 0.0F;
        // 星屑色 #F8C84E（贴图是白的，这里上色）
        this.rCol = 0.97F;
        this.gCol = 0.78F;
        this.bCol = 0.31F;
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
        // 缓慢上飘 + 阻尼
        this.yd += 0.0015;
        this.xd *= 0.96;
        this.yd *= 0.98;
        this.zd *= 0.96;
        this.move(this.xd, this.yd, this.zd);

        float t = this.age / (float) this.lifetime;
        // 闪烁：正弦轻微缩放
        this.quadSize = this.baseSize * (0.7F + 0.5F * Math.abs((float) Math.sin(this.age * 0.9F)));
        this.alpha = t < 0.1F ? t / 0.1F : Math.max(0.0F, 1.0F - (t - 0.1F) / 0.9F);
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
            // 用粒子包给的扩散速度（生成器会按球面随机给）
            return new GoldenDotParticle(level, x, y, z, vx, vy, vz);
        }
    }
}
