package com.example.galboss.client.fx;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;

/**
 * 一个 pixelfx 特效的元数据，对应 {@code assets/galboss/pixelfx/<slug>.json}。
 */
public record PixelfxMetadata(
        String slug,
        ResourceLocation texture,
        @Nullable ResourceLocation lightTexture,
        int frameW,
        int frameH,
        int cols,
        int rows,
        int frames,
        int fps,
        boolean loop,
        int durationTicks,
        float pivotX,
        float pivotY,
        boolean billboard,
        // 屏幕平面内旋转角（度，正 = 逆时针）。仅 billboard 生效；ground 的朝向由调用方 yaw 决定。
        float roll,
        float defaultScale,
        int tint,
        float lightStrength,
        boolean premultiplied) {

    public int textureWidth() {
        return Math.max(1, this.cols * this.frameW);
    }

    public int textureHeight() {
        return Math.max(1, this.rows * this.frameH);
    }

    public int lifeTicks() {
        return this.durationTicks > 0 ? this.durationTicks : Math.max(1, this.frames);
    }

    /**
     * 计算当前生命周期进度 [0, 1] 下的透明度包络（平滑淡入 + 末尾渐隐，消除硬切消失）。
     */
    public float envelopeAlpha(float progress) {
        float clamped = Mth.clamp(progress, 0.0f, 1.0f);
        float fadeInSpan = this.loop ? 0.12f : 0.07f;
        float fadeOutStart = this.loop ? 0.84f : 0.78f;

        if (clamped < fadeInSpan) {
            return clamped / fadeInSpan;
        }
        if (clamped > fadeOutStart) {
            float t = (1.0f - clamped) / (1.0f - fadeOutStart);
            return t * t;
        }
        return 1.0f;
    }

    /**
     * 加色高光层在爆发前期的白金过曝脉冲系数。
     */
    public float lightSurgeMultiplier(float progress) {
        if (this.loop) {
            return 1.0f + 0.12f * Mth.sin(progress * (float) (Math.PI * 4.0));
        }
        if (progress < 0.22f) {
            float peak = 1.0f - Math.abs(progress - 0.10f) / 0.12f;
            return 1.0f + 0.32f * Mth.clamp(peak, 0.0f, 1.0f);
        }
        return 1.0f;
    }
}
