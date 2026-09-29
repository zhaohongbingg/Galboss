package com.example.galboss.client.renderer;

import com.example.galboss.client.model.ReizenTohkaModel;
import com.example.galboss.entity.ReizenTohkaEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ReizenTohkaRenderer extends GeoEntityRenderer<ReizenTohkaEntity> {

    public ReizenTohkaRenderer(EntityRendererProvider.Context context) {
        super(context, new ReizenTohkaModel());
    }

    @Override
    public void render(ReizenTohkaEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(1.0f, 1.0f, 1.0f);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
