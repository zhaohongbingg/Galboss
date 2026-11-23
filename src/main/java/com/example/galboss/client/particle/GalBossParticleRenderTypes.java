package com.example.galboss.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 粒子渲染类型工厂：绑定独立贴图 + 最近邻采样 + 稳定混合模式。
 */
public final class GalBossParticleRenderTypes {

    private GalBossParticleRenderTypes() {
    }

    /** 普通 alpha 混合（本体：保留锐利轮廓与镂空孔）。 */
    public static ParticleRenderType alpha(ResourceLocation texture) {
        return create(texture, false);
    }

    /** 加色辉光混合（SRC_ALPHA, ONE：亮部叠加出白金光晕，暗部完全透明）。 */
    public static ParticleRenderType glow(ResourceLocation texture) {
        return create(texture, true);
    }

    /** 兼容旧命名：等同 {@link #glow(ResourceLocation)}。 */
    public static ParticleRenderType additive(ResourceLocation texture) {
        return create(texture, true);
    }

    static ParticleRenderType create(ResourceLocation texture, boolean additive) {
        return new ParticleRenderType() {
            private boolean filtered;

            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                if (!this.filtered) {
                    textureManager.getTexture(texture).setFilter(false, false);
                    this.filtered = true;
                }
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                RenderSystem.enableBlend();

                RenderSystem.setShader(GameRenderer::getParticleShader);
                RenderSystem.setShaderTexture(0, texture);

                // 预先触发一次 ShaderInstance#apply()，使 BlendMode.lastApplied 同步为当前 particleShader，
                // 防止随后的 BufferUploader#drawWithShader 在首次批次时把加色 blendFunc 覆盖回默认 alpha。
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    shader.apply();
                }

                if (additive) {
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE,
                            GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }

            @Override
            public void end(Tesselator tesselator) {
                tesselator.end();
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
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
