package com.example.galboss.client.fx;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一个 pixelfx 特效的元数据，对应 {@code assets/galboss/pixelfx/<slug>.json}
 * （格式见 CONTRACT.md §4，字段名与那边一一对应）。
 *
 * <p>一张贴图是一个网格：{@code cols × rows} 格里按行优先摆着 {@code frames} 帧，
 * 每帧 {@code frameW × frameH} 像素。所以第 f 帧的 UV 就是
 * {@code (f % cols) / cols} 到 {@code (f % cols + 1) / cols}，纵向同理 ÷ rows。
 *
 * @param pivotX      画面原点在裁剪后单帧里的像素 X（左上原点）
 * @param pivotY      同上，像素 Y
 * @param billboard   true = 竖直立牌（绕 Y 朝相机）；false = 贴地（躺在 XZ 平面）
 * @param lightTexture 加色光通道贴图；JSON 里为 null 时此字段为 null
 * @param tint        颜色调制（0xRRGGBB）。这批图是从 Godot 烘出来的，HDR/bloom 已经在
 *                    烘焙时压成了纯白，主层里大量 255 白核；用 tint 把它们染回主题色，
 *                    是「不重烘也能救」的唯一办法。默认 0xFFFFFF（不改色）
 * @param lightStrength 加色光层强度倍数。实测这批图的 light 贴图 RGB 均值只有 20~60，
 *                    在 {@code SRC_ALPHA/ONE} 下几乎不发光，需要放大才看得出辉光。
 *                    默认 1.0（保持原状）
 * @param premultiplied 贴图是否为<b>预乘 alpha</b>。Godot 2D 默认预乘，但导出管线不统一：
 *                    实测 {@code epitaph_lightning_bolt} / {@code epitaph_storm_sky} 是预乘，
 *                    其余是直通。预乘图用直通混合会把柔边压暗（实测半透明像素亮度只有
 *                    不透明像素的 7%~26%），必须换成 {@code ONE / ONE_MINUS_SRC_ALPHA}
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
        float defaultScale,
        int tint,
        float lightStrength,
        boolean premultiplied) {

    /** 网格贴图的真实宽度（像素）。半像素内缩时要用。 */
    public int textureWidth() {
        return Math.max(1, this.cols * this.frameW);
    }

    /** 网格贴图的真实高度（像素）。 */
    public int textureHeight() {
        return Math.max(1, this.rows * this.frameH);
    }

    /** 该特效的完整播放时长（tick）。元数据缺 durationTicks 时退化成帧数。 */
    public int lifeTicks() {
        return this.durationTicks > 0 ? this.durationTicks : Math.max(1, this.frames);
    }
}
