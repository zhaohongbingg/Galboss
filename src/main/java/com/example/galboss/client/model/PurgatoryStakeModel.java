package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 炼狱七桩的 GeckoLib 模型入口：七人共用一套代码，只按 {@code assetId()} 换三条资源路径。
 *
 * <pre>
 * geo/        purgatory_stake_&lt;id&gt;.geo.json      （7 份，来自 YSM 源包的 bedrock geo）
 * textures/entity/purgatory_stake_&lt;id&gt;.png    （7 份，2048×1024，不降采样）
 * animations/ purgatory_stake.animation.json     （1 份共用）
 * </pre>
 *
 * <p>共用动画只有 6 段（idle / walk / run / hurt / attack / death），是从 YSM 的
 * <code>idle / walk / run / attacked / swing_hand / death</code> 裁出来的，并且：
 * <ul>
 *   <li>丢掉了 YSM 的 <code>molang</code> 伪骨骼（模型里没有这个骨，GeckoLib 会当未知骨忽略，
 *       但留着容易误导后来人）；</li>
 *   <li>把 <code>run</code> 里唯一的 molang 表达式（<code>0.4*ysm.head_yaw</code>）置 0 ——
 *       GeckoLib 的关键帧只认数字；</li>
 *   <li>rotation 通道保持**绝对值**语义（GeckoLib 4.4.2 如此，YSM 的数值也是绝对值），
 *       所以没有把 geo 里的静止姿态（例如手臂 ±15.5°）加回去。</li>
 * </ul>
 */
public class PurgatoryStakeModel extends GeoModel<AbstractPurgatoryStakeEntity> {

    @Override
    public ResourceLocation getModelResource(AbstractPurgatoryStakeEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID,
                "geo/purgatory_stake_" + entity.assetId() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(AbstractPurgatoryStakeEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID,
                "textures/entity/purgatory_stake_" + entity.assetId() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(AbstractPurgatoryStakeEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID,
                "animations/purgatory_stake.animation.json");
    }

    /**
     * 双手都是带爪的、模型有大量单面薄片（裙摆、发片、衣摆）。
     *
     * <p>必须用 {@code entityCutoutNoCull}：带背面剔除的版本会让薄片的背面消失，
     * 侧看就是「身上有洞」。GeoModel 默认实现本来就是 NoCull，这里显式写死，
     * 避免以后换基类时静默退化。
     */
    @Override
    public RenderType getRenderType(AbstractPurgatoryStakeEntity entity, ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }
}
