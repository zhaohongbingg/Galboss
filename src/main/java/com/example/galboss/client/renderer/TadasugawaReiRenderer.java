package com.example.galboss.client.renderer;

import com.example.galboss.client.model.TadasugawaReiModel;
import com.example.galboss.client.renderer.layer.ReiGunLayer;
import com.example.galboss.entity.TadasugawaReiEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class TadasugawaReiRenderer extends GeoEntityRenderer<TadasugawaReiEntity> {

    public TadasugawaReiRenderer(EntityRendererProvider.Context context) {
        super(context, new TadasugawaReiModel());
        // 手枪是「物品」，走原版物品握持变换画在 weapon_anchor 骨骼上
        this.addRenderLayer(new ReiGunLayer(this));
    }

    @Override
    public void render(TadasugawaReiEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(1.0f, 1.0f, 1.0f);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
