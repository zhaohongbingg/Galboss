package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.item.PurgatoryPillarItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 炼狱之柱的物品模型（GeoItem 用）：三份资源都在 {@code item/} 子目录下。
 *
 * <p>geo 的 <code>texture_width/height</code> 是 512×256，贴图文件也是 512×256 —— 1:1，无缩放。
 * 柱子的原点在握柄中心（交付方约定），所以物品模型不需要额外的居中偏移。
 */
public class PurgatoryPillarModel extends GeoModel<PurgatoryPillarItem> {

    @Override
    public ResourceLocation getModelResource(PurgatoryPillarItem item) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/item/purgatory_pillar.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PurgatoryPillarItem item) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/item/purgatory_pillar.png");
    }

    @Override
    public ResourceLocation getAnimationResource(PurgatoryPillarItem item) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/item/purgatory_pillar.animation.json");
    }
}
