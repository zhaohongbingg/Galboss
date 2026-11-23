package com.example.galboss.client.renderer;

import com.example.galboss.client.model.PurgatoryStakeModel;
import com.example.galboss.client.renderer.layer.PurgatoryPillarLayer;
import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 炼狱七桩的渲染器（七个实体共用，模型内部按 {@code assetId()} 选资源）。
 *
 * <p><b>尺寸</b>：交付模型 45.33–45.54 单位（≈2.85 格，1:1），按 YSM 的
 * <code>height_scale = 0.7</code> 惯例乘 0.7 收到 ≈1.99 格，与
 * {@code ModEntities} 的 <code>.sized(0.7f, 2.0f)</code> 对齐 —— 和贝阿朵莉切用的是同一套数
 * （她的 geo 也是 45.9 单位 × 0.7）。要改大小只动这里的 {@link #RENDER_SCALE} 与
 * {@code ModEntities} 的 {@code sized}，两处必须一起改。
 *
 * <p><b>手持炼狱之柱</b>：{@link PurgatoryPillarLayer} 挂在 {@code RightHandLocator} 骨骼上，
 * 和贝阿朵莉切挂烟斗（{@code BeatricePipeLayer}）是同一套机制 —— 柱子注册成物品，
 * 由 GeckoLib 的 {@code BlockAndItemGeoLayer} 按原版 {@code ItemDisplayContext} 画到手上，
 * 代码里没有任何手算的偏移矩阵。
 */
public class PurgatoryStakeRenderer extends GeoEntityRenderer<AbstractPurgatoryStakeEntity> {

    /** 1.99 格高（45.54 单位 × 0.7 / 16）。碰撞箱同步为 0.7 × 2.0，见 ModEntities。 */
    private static final float RENDER_SCALE = 0.7f;

    public PurgatoryStakeRenderer(EntityRendererProvider.Context context) {
        super(context, new PurgatoryStakeModel());
        this.withScale(RENDER_SCALE);
        this.addRenderLayer(new PurgatoryPillarLayer(this));
    }
}
