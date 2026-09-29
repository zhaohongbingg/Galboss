package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.TadasugawaReiEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class TadasugawaReiModel extends GeoModel<TadasugawaReiEntity> {

    @Override
    public ResourceLocation getModelResource(TadasugawaReiEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/tadasugawa_rei.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(TadasugawaReiEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/tadasugawa_rei.png");
    }

    @Override
    public ResourceLocation getAnimationResource(TadasugawaReiEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/tadasugawa_rei.animation.json");
    }
}
