package com.example.galboss.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;

/**
 * 蝴蝶 · 辉光层（加色混合）。
 *
 * <p>挂载在 {@link ButterflyParticle} 上时，每帧严格镜像本体的位置、拍翅帧与生命周期，
 * 消除随机运动漂移，并随翅膀开合相位做呼吸式光晕脉动。
 */
public class ButterflyGlowParticle extends AbstractButterflyParticle {

    private static final ButterflySprite SPRITE = Butterflies.ORANGE_GLOW;

    public static final ParticleRenderType RENDER_TYPE = createRenderType(SPRITE.texture(), true);

    @Nullable
    private final ButterflyParticle parent;

    /** 由本体创建：严格锁定父级位置、帧序号与淡入淡出。 */
    ButterflyGlowParticle(ButterflyParticle parent) {
        super(parent.clientLevel(),
                parent.currentX(), parent.currentY(), parent.currentZ(),
                0.0, 0.0, 0.0,
                parent.phaseOffset, SPRITE);
        this.parent = parent;
        this.lifetime = parent.lifeTicks();
        // 暖白金高光色调：加色叠加时中心偏白金、边缘保持琥珀金
        this.rCol = 1.0F;
        this.gCol = 0.90F;
        this.bCol = 0.62F;
        syncWithParent();
    }

    /** 独立生成构造器（兼容直接通过 ParticleType 调用）。 */
    protected ButterflyGlowParticle(ClientLevel level, double x, double y, double z,
                                    double vx, double vy, double vz, int phaseOffset) {
        super(level, x, y, z, vx, vy, vz, phaseOffset, SPRITE);
        this.parent = null;
        this.quadSize *= 1.25F;
        this.rCol = 1.0F;
        this.gCol = 0.90F;
        this.bCol = 0.62F;
        this.alpha = 0.0F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    public void tick() {
        if (this.parent != null) {
            if (!this.parent.isAlive()) {
                this.remove();
                return;
            }
            this.age = this.parent.currentAge();
            syncWithParent();
            return;
        }

        // 独立模式回退逻辑
        super.tick();
        float pulse = 0.48F + 0.18F * Mth.sin(this.age * 0.95F + this.phaseOffset);
        this.alpha *= pulse;
    }

    private void syncWithParent() {
        if (this.parent == null) {
            return;
        }
        this.xo = this.parent.prevX();
        this.yo = this.parent.prevY();
        this.zo = this.parent.prevZ();
        this.setPos(this.parent.currentX(), this.parent.currentY(), this.parent.currentZ());

        float flapWave = Mth.sin((this.age / (float) Math.max(1, SPRITE.frameTicks()) + this.phaseOffset) * (float) Math.PI);
        this.quadSize = this.parent.currentQuadSize() * (1.25F + 0.07F * flapWave);
        this.alpha = this.parent.currentAlpha() * (0.48F + 0.18F * flapWave);
    }

    @Override
    protected int frameIndex() {
        if (this.parent != null) {
            return Math.floorMod(this.parent.currentFrameIndex(), SPRITE.frames());
        }
        return super.frameIndex();
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ButterflyGlowParticle(level, x, y, z, vx, vy, vz, level.random.nextInt(SPRITE.frames()));
        }
    }
}
