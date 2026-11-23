package com.example.galboss.client.renderer.layer;

import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import com.example.galboss.item.ModItems;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/**
 * 炼狱之柱的手持渲染层 —— 七位炼狱之桩右手各持一根柱子，做法和贝阿朵莉切的烟斗一样
 * （{@link BeatricePipeLayer}）：把「柱子」这个物品画到模型的 {@code RightHandLocator} 骨骼上。
 *
 * <p><b>为什么走物品而不是独立 GeoModel</b>（与烟斗/武士刀/手枪同一条经验）：
 * <ul>
 *   <li>位置与朝向交给原版 {@code ItemDisplayContext} 的标准握持变换，代码里零常量、零手算矩阵；</li>
 *   <li>独立 GeoModel 会被 GeckoLib 按骨骼名把角色动画套到武器上，柱子会自己乱转；</li>
 *   <li>柱子的贴图与角色的 2048×1024 图集完全独立，不必合并图集、不必重写 968 个方块的 UV。</li>
 * </ul>
 *
 * <p><b>为什么柱子的物品模型不做成 elements</b>：柱子的 819/968 个方块带自由角度旋转，
 * 而原版物品模型只接受 0 / ±22.5 / ±45（超了直接反序列化失败，手上只剩紫黑棋盘格）。
 * 所以柱子注册成 GeckoLib 的 GeoItem（{@code item/PurgatoryPillarItem}），
 * 由 {@code GeoItemRenderer} 渲染，几何原样保留。
 *
 * <p>骨骼不带 rotation 是硬要求：{@code GeoRenderer.renderRecursively} 已经套过一次骨骼变换，
 * {@code BlockAndItemGeoLayer} 又会套一次 —— 骨骼上带角度会被应用两遍（转 θ 变转 2θ）。
 * 要调朝向就改 {@code models/item/purgatory_pillar.json} 的 {@code display}。
 */
public class PurgatoryPillarLayer extends BlockAndItemGeoLayer<AbstractPurgatoryStakeEntity> {

    /** YSM 源模型自带的右手挂点（七份 geo 都有，且 rotation 为空）。 */
    private static final String ANCHOR = "RightHandLocator";

    public PurgatoryPillarLayer(GeoRenderer<AbstractPurgatoryStakeEntity> renderer) {
        super(renderer);
    }

    @Override
    protected ItemStack getStackForBone(GeoBone bone, AbstractPurgatoryStakeEntity animatable) {
        return ANCHOR.equals(bone.getName())
                ? new ItemStack(ModItems.PURGATORY_PILLAR.get())
                : null;
    }

    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack,
                                                          AbstractPurgatoryStakeEntity animatable) {
        // ⚠ 这里**故意**不用 THIRD_PERSON_RIGHT_HAND：
        // `RightHandLocator` 是一个几乎轴对齐的骨骼帧，而玩家的手是原版那套
        // 「先绕模型中心旋转、再平移」的手持帧 —— 两者差着大约一个直角。
        // 用同一个 display 会导致修好一方必然写坏另一方（实测：套用玩家手的角度，
        // 柱子在 NPC 手里会横着穿出身体；套用骨骼帧的角度，玩家第一人称里柱子朝下）。
        // 所以 NPC 这一路独占 `head` 这个槽位（柱子永远不会被戴在头上，不会冲突），
        // 玩家第一/第三人称继续用原版手持帧的数值（参照原版三叉戟的
        // `trident_in_hand.json`，它同样是 +Y 方向的长杆武器）。
        return ItemDisplayContext.HEAD;
    }
}
