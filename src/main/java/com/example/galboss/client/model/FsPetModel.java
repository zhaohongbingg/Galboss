package com.example.galboss.client.model;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 宠物模型：按变体复用三个 boss 已有的 geo / 贴图 / 动画，不新增美术资源。
 *
 * <p>GeckoLib 4 的这三个方法都带实体参数，所以一个模型类就能服务三个变体，
 * 不需要像 boss 那边一样一个角色一个 Model。
 */
public class FsPetModel extends GeoModel<FsPetEntity> {

    @Override
    public ResourceLocation getModelResource(FsPetEntity pet) {
        return resource("geo/" + pet.getVariant().getEntityId() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(FsPetEntity pet) {
        return resource("textures/entity/" + pet.getVariant().getEntityId() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(FsPetEntity pet) {
        return resource("animations/" + pet.getVariant().getEntityId() + ".animation.json");
    }

    private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, path);
    }
}
