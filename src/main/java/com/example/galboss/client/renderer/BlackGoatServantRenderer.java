package com.example.galboss.client.renderer;

import com.example.galboss.client.model.BlackGoatServantModel;
import com.example.galboss.entity.BlackGoatServantEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * 黑山羊仆从渲染器。
 *
 * <p><b>尺寸：3 格高的壮硕仆从</b>。交付的 v6 模型实测包围盒 43.184 单位 = <b>2.699 格</b>（1:1），
 * 不是整数 3 格；要让它在游戏里正好 3 格，渲染缩放取 {@code 3.00 × 16 / 43.184 = 1.1115}，
 * 实体碰撞箱同步放大到 {@code .sized(0.9f, 3.0f)}（{@code ModEntities}）—— 两边必须一起改，
 * 否则会出现「看起来穿天花板」或「明明还有半个身位却打不到」。
 *
 * <p>要改大小只动这两个地方：这里的 {@link #RENDER_SCALE} 与 {@code ModEntities} 的 {@code sized}。
 * 想严格按交付模型 1:1（2.70 格）：缩放改 {@code 1.0f}、碰撞箱改 {@code .sized(0.78f, 2.70f)}。
 *
 * <p>发光遮罩层：GeckoLib 4.4.2 的 {@link AutoGlowingGeoLayer} 按「主贴图路径 + {@code _glowmask}」
 * 找贴图，即 {@code textures/entity/black_goat_servant_glowmask.png}（只画红瞳，其余透明，
 * 由 {@code tools/blockbench/make_goat_v6_glowmask.py} 从交付贴图按色相抽出，尺寸与主贴图同为 1024²）。
 */
public class BlackGoatServantRenderer extends GeoEntityRenderer<BlackGoatServantEntity> {

    /** 3.00 格 ÷ (43.184 单位 / 16) = 1.1115。碰撞箱同步为 0.9 × 3.0（见 ModEntities）。 */
    private static final float RENDER_SCALE = 1.1115f;

    public BlackGoatServantRenderer(EntityRendererProvider.Context context) {
        super(context, new BlackGoatServantModel());
        this.withScale(RENDER_SCALE);
        // 发光红瞳：叠加在主贴图之上，不影响主体明暗
        this.addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }
}
