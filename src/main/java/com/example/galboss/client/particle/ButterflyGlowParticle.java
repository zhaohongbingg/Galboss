package com.example.galboss.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 蝴蝶 · 辉光层（加色混合）。
 *
 * <p>加色混合（{@code SRC_ALPHA, ONE}）下，亮部会「烧」出光晕、透明处 alpha=0 完全不贡献颜色，
 * 于是镂空依旧透出背景。图集是同布局的发光版（{@link Butterflies#ORANGE_GLOW}）。
 *
 * <p>与本体<b>同帧</b>：本体创建这只时把自己的相位传进来，辉光用同一相位取帧，
 * 两层轮廓严格重合（否则光斑会糊在别处）。
 */
public class ButterflyGlowParticle extends AbstractButterflyParticle {

    /** 辉光用的图集。 */
    private static final ButterflySprite SPRITE = Butterflies.ORANGE_GLOW;

    /** 辉光渲染类型：辉光图集 + 全亮 + 加色混合。 */
    public static final ParticleRenderType RENDER_TYPE = createRenderType(SPRITE.texture(), true);

    protected ButterflyGlowParticle(ClientLevel level, double x, double y, double z,
                                    double vx, double vy, double vz, int phaseOffset) {
        super(level, x, y, z, vx, vy, vz, phaseOffset, SPRITE);
        // 辉光比本体略大一圈（bloom 感），但别大到糊掉轮廓
        this.quadSize *= 1.22F;
        // 辉光整体弱一些，避免加色叠爆
        this.alpha = 0.5F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    public void tick() {
        super.tick();
        // 淡入淡出由本体控制节奏，这里保持一个略低的固定亮度（加色下太亮会糊）
        this.alpha = 0.5F;
    }

    /** 注册到 {@code RegisterParticleProvidersEvent} 的工厂。 */
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ButterflyGlowParticle(level, x, y, z, vx, vy, vz, level.random.nextInt(SPRITE.frames()));
        }
    }
}
