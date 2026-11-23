package com.example.galboss.client.particle;

import com.example.galboss.particle.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * 「蝴蝶命中」爆炸后飞出的蝶：命中点为中心弹出、螺旋外扩、缓慢上升、20 tick 内淡出。
 * 自带同步的加色内芯辉光层。
 */
public class ButterflyBurstParticle extends SingleQuadParticle {

    private static final ButterflySprite SPRITE = Butterflies.ORANGE_BODY;

    public static final ParticleRenderType RENDER_TYPE =
            GalBossParticleRenderTypes.alpha(SPRITE.texture());

    private final double originX;
    private final double originY;
    private final double originZ;
    private final double spin;
    private final double angle0;
    private final double radius0;
    private final double radialSpeed;
    private final double riseSpeed;
    private final float targetSize;
    private final int phaseOffset;
    private boolean glowSpawned;

    protected ButterflyBurstParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.spin = this.random.nextBoolean() ? 1.0 : -1.0;
        this.angle0 = this.random.nextDouble() * Math.PI * 2.0;
        this.radius0 = 0.14 + this.random.nextDouble() * 0.14;
        this.radialSpeed = 0.052 + this.random.nextDouble() * 0.022;
        this.riseSpeed = 0.011 + this.random.nextDouble() * 0.012;
        this.targetSize = 0.17F + this.random.nextFloat() * 0.08F;
        this.quadSize = this.targetSize * 0.45F;
        this.phaseOffset = this.random.nextInt(SPRITE.frames());
        this.lifetime = 20;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.alpha = 0.0F;
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
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

        // 螺旋外扩：前快后稳，带轻微拍翅起伏
        double angle = this.angle0 + this.spin * (0.17 * this.age + 0.005 * this.age * this.age);
        double radius = this.radius0 + this.radialSpeed * this.age;
        double flapBob = Math.sin((this.age / (double) SPRITE.frameTicks() + this.phaseOffset) * Math.PI) * 0.015;
        this.x = this.originX + Math.cos(angle) * radius;
        this.z = this.originZ + Math.sin(angle) * radius;
        this.y = this.originY + this.riseSpeed * this.age + flapBob;
        this.setPos(this.x, this.y, this.z);

        // 弹出缩放曲线：0~3 tick 迅速张开至 1.15 倍，随后平滑收束至 0.85 倍
        if (this.age <= 3) {
            float t = this.age / 3.0F;
            this.quadSize = this.targetSize * Mth.lerp(t, 0.45F, 1.15F);
        } else {
            float t = (this.age - 3) / (float) (this.lifetime - 3);
            this.quadSize = this.targetSize * Mth.lerp(t, 1.15F, 0.85F);
        }

        // 螺旋余晖星屑（节流为每 3 tick 一次，兼顾拖尾连续性与同屏性能）
        if (this.age % 3 == 0) {
            this.level.addParticle(ModParticles.BURST_DOT.get(), this.x, this.y, this.z,
                    0.0, 0.004, 0.0);
        } else if (this.age % 3 == 1 && this.age <= 12) {
            double a = this.random.nextDouble() * Math.PI * 2.0;
            double r = 0.12 + this.random.nextDouble() * 0.16;
            this.level.addParticle(ModParticles.BURST_DOT.get(),
                    this.x + Math.cos(a) * r,
                    this.y + (this.random.nextDouble() - 0.4) * 0.25,
                    this.z + Math.sin(a) * r,
                    Math.cos(a) * 0.004, 0.006, Math.sin(a) * 0.004);
        }

        // 前 2 tick 淡入，最后 6 tick 淡出
        if (this.age <= 2) {
            this.alpha = this.age / 2.0F;
        } else if (this.age >= this.lifetime - 6) {
            this.alpha = (this.lifetime - this.age) / 6.0F;
        } else {
            this.alpha = 1.0F;
        }

        if (!this.glowSpawned) {
            this.glowSpawned = true;
            Minecraft.getInstance().particleEngine.add(new BurstButterflyGlow(this));
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
        return Math.floorMod(this.age / SPRITE.frameTicks() + this.phaseOffset, SPRITE.frames());
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

    /** 爆散蝴蝶专属同步加色辉光从属粒子。 */
    private static final class BurstButterflyGlow extends SingleQuadParticle {

        private static final ButterflySprite GLOW_SPRITE = Butterflies.ORANGE_GLOW;
        private static final ParticleRenderType GLOW_RENDER_TYPE =
                GalBossParticleRenderTypes.glow(GLOW_SPRITE.texture());

        private final ButterflyBurstParticle parent;

        BurstButterflyGlow(ButterflyBurstParticle parent) {
            super((ClientLevel) parent.level, parent.x, parent.y, parent.z);
            this.parent = parent;
            this.lifetime = parent.lifetime;
            this.gravity = 0.0F;
            this.rCol = 1.0F;
            this.gCol = 0.90F;
            this.bCol = 0.60F;
            sync();
        }

        @Override
        public void tick() {
            if (!this.parent.isAlive()) {
                this.remove();
                return;
            }
            this.age = this.parent.age;
            sync();
        }

        private void sync() {
            this.xo = this.parent.xo;
            this.yo = this.parent.yo;
            this.zo = this.parent.zo;
            this.setPos(this.parent.x, this.parent.y, this.parent.z);
            this.quadSize = this.parent.quadSize * 1.28F;
            this.alpha = this.parent.alpha * 0.58F;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return GLOW_RENDER_TYPE;
        }

        @Override
        protected int getLightColor(float partialTick) {
            return LightTexture.FULL_BRIGHT;
        }

        private int frame() {
            return Math.floorMod(this.parent.frameIndex(), GLOW_SPRITE.frames());
        }

        @Override
        protected float getU0() {
            return (frame() % GLOW_SPRITE.cols()) * GLOW_SPRITE.frameU() + GLOW_SPRITE.insetU();
        }

        @Override
        protected float getU1() {
            return (frame() % GLOW_SPRITE.cols()) * GLOW_SPRITE.frameU() + GLOW_SPRITE.frameU() - GLOW_SPRITE.insetU();
        }

        @Override
        protected float getV0() {
            return (frame() / GLOW_SPRITE.cols()) * GLOW_SPRITE.frameV() + GLOW_SPRITE.insetV();
        }

        @Override
        protected float getV1() {
            return (frame() / GLOW_SPRITE.cols()) * GLOW_SPRITE.frameV() + GLOW_SPRITE.frameV() - GLOW_SPRITE.insetV();
        }
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
