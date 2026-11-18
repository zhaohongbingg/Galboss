package com.example.galboss.client.renderer;

import com.example.galboss.client.model.FsPetModel;
import com.example.galboss.entity.FsPetEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 宠物渲染：比 boss 本体小一半，一眼能分清谁是本体谁是伙伴。
 */
public class FsPetRenderer extends GeoEntityRenderer<FsPetEntity> {

    private static final float PET_SCALE = 0.5f;

    public FsPetRenderer(EntityRendererProvider.Context context) {
        super(context, new FsPetModel());
    }

    @Override
    public void render(FsPetEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(PET_SCALE, PET_SCALE, PET_SCALE);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
