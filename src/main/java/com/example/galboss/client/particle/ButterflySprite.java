package com.example.galboss.client.particle;

import net.minecraft.resources.ResourceLocation;

/**
 * 蝴蝶粒子图集的布局描述（几列几行、单帧像素、每帧停留几 tick）。
 *
 * <p>为什么要有这个：{@link ButterflyParticle} 的换帧逻辑是「按 UV 子矩形采样一张图集」，
 * 布局写死成常量的话，换一套素材（比如从 8 帧金蝶换成 2 帧 ER 橙蝶）就得改三处常量、
 * 还会连带把辉光层改错。现在布局跟着 sprite 走，换素材只换 {@link Butterflies} 里的一行。
 *
 * @param texture      图集贴图路径（{@code setShaderTexture} 用）
 * @param cols         横向帧数
 * @param rows         纵向帧数
 * @param framePixels  单帧边长（像素）；只用来算 0.5px 的 UV 内缩，防止最近邻采到邻帧
 * @param frameTicks   每帧停留 tick 数（20 tick = 1 秒）
 */
public record ButterflySprite(ResourceLocation texture, int cols, int rows, int framePixels, int frameTicks) {

    public ButterflySprite {
        if (cols < 1 || rows < 1 || framePixels < 1 || frameTicks < 1) {
            throw new IllegalArgumentException("蝴蝶图集参数非法: cols=" + cols + " rows=" + rows
                    + " framePixels=" + framePixels + " frameTicks=" + frameTicks);
        }
    }

    /** 总帧数。相位随机的上界也是它。 */
    public int frames() {
        return cols * rows;
    }

    public float frameU() {
        return 1.0F / cols;
    }

    public float frameV() {
        return 1.0F / rows;
    }

    /** 每帧四边内缩半个像素：最近邻采样下不内缩会采到隔壁帧的边缘。 */
    public float insetU() {
        return 0.5F / (cols * framePixels);
    }

    public float insetV() {
        return 0.5F / (rows * framePixels);
    }
}
