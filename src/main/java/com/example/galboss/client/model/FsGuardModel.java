package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsGuardEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** FS Guard 直接复用冷泉院桐香的模型 / 贴图 / 动画资源。 */
public class FsGuardModel extends GeoModel<FsGuardEntity> {

    @Override
    public ResourceLocation getModelResource(FsGuardEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/reizein_tohka.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(FsGuardEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/reizein_tohka.png");
    }

    @Override
    public ResourceLocation getAnimationResource(FsGuardEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/reizein_tohka.animation.json");
    }
}
