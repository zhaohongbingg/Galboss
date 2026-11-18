package com.example.galboss.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 蝴蝶 · 本体层（普通 alpha 混合，逐帧拍翅）。
 *
 * <p>素材是 ER 预览表里的 {@code butterfly_orange_flap}（见 {@link Butterflies#ORANGE_BODY}）。
 * 辉光在 {@link ButterflyGlowParticle} 里用加色混合再画一遍，两层叠起来就是「会发光的橙蝶」。
 * 想换回金色镂空蝶：把这里的 sprite 与渲染类型换成 {@link Butterflies#GOLD_BODY} 即可。
 */
public class ButterflyParticle extends AbstractButterflyParticle {

    /** 本体用的图集。 */
    private static final ButterflySprite SPRITE = Butterflies.ORANGE_BODY;

    /** 本体渲染类型：自己的图集 + 全亮 + 普通 alpha 混合。 */
    public static final ParticleRenderType RENDER_TYPE = createRenderType(SPRITE.texture(), false);

    /** 辉光层是否已经挂上去了（首 tick 时挂）。 */
    private boolean glowSpawned;

    protected ButterflyParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, int phaseOffset) {
        super(level, x, y, z, vx, vy, vz, phaseOffset, SPRITE);
    }

    @Override
    public void tick() {
        super.tick();
        // 首 tick 挂上同相位的辉光层：两层逐帧严格对齐，叠出「发光轮廓」。
        // 放在 tick 而不在 createParticle 里，是为了不在粒子引擎渲染迭代中改它的列表。
        if (!this.glowSpawned) {
            this.glowSpawned = true;
            Minecraft.getInstance().particleEngine.add(
                    new ButterflyGlowParticle(this.level, this.x, this.y, this.z,
                            this.xd, this.yd, this.zd, this.phaseOffset));
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    /** 注册到 {@code RegisterParticleProvidersEvent} 的工厂。 */
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            // 相位随机 → 蝶群不会整齐刷同一步拍翅
            return new ButterflyParticle(level, x, y, z, vx, vy, vz, level.random.nextInt(SPRITE.frames()));
        }
    }
}
