package com.example.galboss.client.renderer;

import com.example.galboss.client.model.FsGuardModel;
import com.example.galboss.entity.FsGuardEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class FsGuardRenderer extends GeoEntityRenderer<FsGuardEntity> {

    /** 比本体小一圈，方便在混战里一眼分辨。 */
    private static final float GUARD_SCALE = 0.72f;

    public FsGuardRenderer(EntityRendererProvider.Context context) {
        super(context, new FsGuardModel());
    }

    @Override
    public void render(FsGuardEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(GUARD_SCALE, GUARD_SCALE, GUARD_SCALE);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
