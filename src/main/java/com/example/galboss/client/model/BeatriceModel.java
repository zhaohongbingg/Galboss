package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.BeatriceEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 贝阿朵莉切的 GeckoLib 模型入口：这里只做「实体 → 三份资源」的路径映射。
 *
 * <p>三份文件由 tools/blockbench 的导出脚本生成（geo / 贴图 / 动画），
 * 命名规则和实体注册名 {@code beatrice} 保持一致，别在代码里另起一套名字，
 * 否则资源一改名就要回来改 Java。
 */
public class BeatriceModel extends GeoModel<BeatriceEntity> {

    @Override
    public ResourceLocation getModelResource(BeatriceEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/beatrice.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BeatriceEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/beatrice.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BeatriceEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/beatrice.animation.json");
    }
}
