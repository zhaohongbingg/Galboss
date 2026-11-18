package com.example.galboss.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 粒子渲染类型工厂：绑自己的贴图 + 最近邻无 mipmap + 指定混合模式。
 *
 * <p>为什么不用原版粒子图集：pixelfx/蝴蝶这类「自己一张图集」的粒子逐个 setShaderTexture 更直接，
 * 不受 particles/*.json 与图集尺寸限制。
 *
 * @author 见 tools/particle/README.md
 */
public final class GalBossParticleRenderTypes {

    private GalBossParticleRenderTypes() {
    }

    /** 普通 alpha 混合（本体：保留锐利轮廓与镂空孔）。 */
    public static ParticleRenderType alpha(ResourceLocation texture) {
        return create(texture, false);
    }

    /**
     * 「辉光」渲染类型。
     *
     * <p>老实说：真正的加色混合（SRC_ALPHA, ONE）在 MC 里没法靠这里的 {@code blendFunc} 稳定实现——
     * {@code BufferUploader} 每批绘制都会调 {@code ShaderInstance#apply()}，它按 shader JSON 的
     * blend 节点重置混合状态（particle.json 是 srcalpha/1-srcalpha）。要稳定加色得注册自己的
     * core shader（带 additive blend 节点）。
     *
     * <p>所以这一版用**普通 alpha 混合 + 高亮贴图**模拟辉光：贴图的亮部接近白金、边缘柔和，
     * 叠在暗场景上观感接近发光；后续如需要更「烧」的效果，再补自定义 shader。
     */
    public static ParticleRenderType glow(ResourceLocation texture) {
        return create(texture, false);
    }

    /**
     * 兼容旧命名：当前实现等同 {@link #glow(ResourceLocation)}（见上面的说明）。
     */
    public static ParticleRenderType additive(ResourceLocation texture) {
        return create(texture, false);
    }

    private static ParticleRenderType create(ResourceLocation texture, boolean additive) {
        return new ParticleRenderType() {
            private boolean filtered;

            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                if (!this.filtered) {
                    textureManager.getTexture(texture).setFilter(false, false);
                    this.filtered = true;
                }
                RenderSystem.depthMask(false);
                RenderSystem.enableBlend();
                // 顺序很重要：必须先 setShader 再 blendFunc。
                // particle.json 自带 blend 节点（srcalpha / 1-srcalpha），setShader 会把它应用一遍，
                // 如果先设加色再 setShader，加色会被覆盖回普通 alpha（表现为发光变灰、变暗）。
                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderTexture(0, texture);
                if (additive) {
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }

            @Override
            public void end(Tesselator tesselator) {
                tesselator.end();
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }

            @Override
            public String toString() {
                return (additive ? "galboss_additive_" : "galboss_alpha_") + texture;
            }
        };
    }
}
