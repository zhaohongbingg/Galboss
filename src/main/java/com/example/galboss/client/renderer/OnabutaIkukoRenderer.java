package com.example.galboss.client.renderer;

import com.example.galboss.client.model.OnabutaIkukoModel;
import com.example.galboss.client.renderer.layer.IkukoKatanaLayer;
import com.example.galboss.entity.OnabutaIkukoEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class OnabutaIkukoRenderer extends GeoEntityRenderer<OnabutaIkukoEntity> {

    public OnabutaIkukoRenderer(EntityRendererProvider.Context context) {
        super(context, new OnabutaIkukoModel());
        // 武士刀是「物品」，走原版物品握持变换画在 weapon_anchor 骨骼上
        this.addRenderLayer(new IkukoKatanaLayer(this));
    }

    @Override
    public void render(OnabutaIkukoEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(1.0f, 1.0f, 1.0f);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
