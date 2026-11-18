package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.BlackGoatServantEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 黑山羊仆从的 GeckoLib 模型入口：只做「实体 → 三份资源」的路径映射。
 *
 * <p>三份文件由 tools/blockbench 的黑山羊流水线产出（geo / 128×128 贴图 / 6 段动画），
 * 命名规则和实体注册名 {@code black_goat_servant} 保持一致，别在代码里另起一套名字。
 *
 * <p>渲染器不额外缩放：geo 按实际尺寸建模（总高 31~32 单位 ≈ 1.95~2.0 格，与实体碰撞箱一致）。
 */
public class BlackGoatServantModel extends GeoModel<BlackGoatServantEntity> {

    @Override
    public ResourceLocation getModelResource(BlackGoatServantEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/black_goat_servant.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BlackGoatServantEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/black_goat_servant.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BlackGoatServantEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/black_goat_servant.animation.json");
    }

    /**
     * 契约 §6.2 要求 {@code RenderType.entityCutoutNoCull}。
     *
     * <p>GeckoLib 4.4.2 的 {@link GeoModel} 默认实现本来就是这个（反编译确认它调
     * {@code RenderType.entityCutoutNoCull(texture)}），这里显式写出来，
     * 以后换基类或别处改动时不会静默退化成带背面剔除的版本（长袍下摆会漏面）。
     */
    @Override
    public RenderType getRenderType(BlackGoatServantEntity entity, ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }
}
