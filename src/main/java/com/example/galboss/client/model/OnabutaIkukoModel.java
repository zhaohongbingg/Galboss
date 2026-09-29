package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.OnabutaIkukoEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class OnabutaIkukoModel extends GeoModel<OnabutaIkukoEntity> {

    @Override
    public ResourceLocation getModelResource(OnabutaIkukoEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "geo/onabuta_ikuko.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(OnabutaIkukoEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/entity/onabuta_ikuko.png");
    }

    @Override
    public ResourceLocation getAnimationResource(OnabutaIkukoEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "animations/onabuta_ikuko.animation.json");
    }
}
