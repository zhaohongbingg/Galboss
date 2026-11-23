package com.example.galboss.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 蝴蝶 · 本体层（普通 alpha 混合，逐帧拍翅，首 tick 自动挂载锁定同步的加色辉光层）。
 */
public class ButterflyParticle extends AbstractButterflyParticle {

    private static final ButterflySprite SPRITE = Butterflies.ORANGE_BODY;

    public static final ParticleRenderType RENDER_TYPE = createRenderType(SPRITE.texture(), false);

    private boolean glowSpawned;

    protected ButterflyParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, int phaseOffset) {
        super(level, x, y, z, vx, vy, vz, phaseOffset, SPRITE);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed && !this.glowSpawned) {
            this.glowSpawned = true;
            Minecraft.getInstance().particleEngine.add(new ButterflyGlowParticle(this));
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ButterflyParticle(level, x, y, z, vx, vy, vz, level.random.nextInt(SPRITE.frames()));
        }
    }
}
