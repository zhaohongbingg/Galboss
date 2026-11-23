package com.example.galboss.client.renderer.layer;

import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.item.ModItems;
import com.example.galboss.particle.ModParticles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/**
 * 贝阿朵莉切右手长烟斗渲染层：渲染武器模型并从烟斗处持续飘出紫金魔力烟缕。
 */
public class BeatricePipeLayer extends BlockAndItemGeoLayer<BeatriceEntity> {

    private static final String ANCHOR = "RightHandLocator";

    private int lastEmitTick = -1;

    public BeatricePipeLayer(GeoRenderer<BeatriceEntity> renderer) {
        super(renderer);
    }

    @Override
    protected ItemStack getStackForBone(GeoBone bone, BeatriceEntity animatable) {
        return ANCHOR.equals(bone.getName())
                ? new ItemStack(ModItems.BEATRICE_PIPE.get())
                : null;
    }

    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, BeatriceEntity animatable) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    @Override
    public void renderForBone(PoseStack poseStack, BeatriceEntity animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay) {
        super.renderForBone(poseStack, animatable, bone, renderType, bufferSource, buffer,
                partialTick, packedLight, packedOverlay);

        if (!ANCHOR.equals(bone.getName()) || !animatable.isAlive() || animatable.level() == null) {
            return;
        }
        if (animatable.tickCount == this.lastEmitTick) {
            return;
        }
        this.lastEmitTick = animatable.tickCount;

        // 每 4 tick 从右手烟斗斗钵处升起一缕淡紫烟雾与金色余烬
        if (animatable.tickCount % 4 == 0) {
            float bodyYawRad = animatable.yBodyRot * Mth.DEG_TO_RAD;
            double forwardX = -Mth.sin(bodyYawRad);
            double forwardZ = Mth.cos(bodyYawRad);
            double rightX = -forwardZ;
            double rightZ = forwardX;

            double bowlX = animatable.getX() + forwardX * 0.58 + rightX * 0.34;
            double bowlY = animatable.getY() + animatable.getBbHeight() * 0.68
                    + Mth.sin(animatable.tickCount * 0.12f) * 0.03;
            double bowlZ = animatable.getZ() + forwardZ * 0.58 + rightZ * 0.34;

            animatable.level().addParticle(ParticleTypes.WITCH,
                    bowlX, bowlY, bowlZ,
                    forwardX * 0.008, 0.024, forwardZ * 0.008);

            if (animatable.tickCount % 12 == 0) {
                animatable.level().addParticle(ModParticles.BURST_DOT.get(),
                        bowlX, bowlY + 0.05, bowlZ,
                        rightX * 0.006, 0.018, rightZ * 0.006);
            }
        }
    }
}
