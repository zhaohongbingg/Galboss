package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.ReizenTohkaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ReizenTohkaModel extends GeoModel<ReizenTohkaEntity> {

    @Override
    public ResourceLocation getModelResource(ReizenTohkaEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/reizein_tohka.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ReizenTohkaEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/reizein_tohka.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ReizenTohkaEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/reizein_tohka.animation.json");
    }
}
