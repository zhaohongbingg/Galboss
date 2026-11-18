package com.example.galboss.client.renderer;

import com.example.galboss.client.model.BeatriceModel;
import com.example.galboss.client.renderer.layer.BeatricePipeLayer;
import com.example.galboss.entity.BeatriceEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 贝阿朵莉切的渲染器：模型本体 + 一个「烟斗」物品层。
 *
 * <p>缩放 0.7：交付的 .bbmodel 是按 YSM 的比例做的（YSM 默认 {@code height_scale = 0.7}，
 * 它把 43 单位左右的原生模型压到 1.9 格高）。实测 {geo/beatrice.geo.json} 的包围盒是
 * y ∈ [-0.58, 45.33]（45.9 单位 ≈ 2.87 格），不缩放的话她比另外三个 FS boss（2.0 格）高出
 * 近一半，碰撞箱也得跟着放大到 2.8 格。×0.7 后高度约 2.0 格，正好和 {@code .sized(0.7f, 2.0f)}
 * 对齐 —— 裙摆宽度也从 1.8 格收到 1.27 格，站在教室里不会把走道堵死。
 *
 * <p>缩放挂在渲染器（{@code withScale}）而不是写进 geo：geo 保持与交付模型 1:1，
 * 以后要改比例只动这一个数。
 */
public class BeatriceRenderer extends GeoEntityRenderer<BeatriceEntity> {

    public BeatriceRenderer(EntityRendererProvider.Context context) {
        super(context, new BeatriceModel());
        this.withScale(0.7f);
        // 烟斗是「物品」，走原版物品握持变换画在 RightHandLocator 骨骼上
        this.addRenderLayer(new BeatricePipeLayer(this));
    }
}
