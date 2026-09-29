package com.example.galboss.client.renderer.layer;

import com.example.galboss.entity.TadasugawaReiEntity;
import com.example.galboss.item.ModItems;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/**
 * 把手枪作为**物品**渲染在右手上 —— 和 {@link IkukoKatanaLayer} 同一套做法。
 *
 * 早先这里是「独立 GeoModel + 手算矩阵」的方案（TACZ 的 glock 有自己的 1024 贴图，
 * 塞不进角色那张 64x128），代码里堆了一大串偏移常量。换成物品之后这些全都不需要了：
 * 位置与朝向交给原版物品系统的 {@link ItemDisplayContext} 标准握持变换，
 * 贴图走物品自己的贴图系统，模型里也没有骨骼，角色动画不会按名字套上来。
 *
 * 握持姿态在 {@code models/item/rei_gun.json} 的 display 块里调，由
 * {@code tools/blockbench/geo_to_item_model.mjs} 生成（枪管沿 -Z，所以没套原版 handheld 的
 * [0,-90,55] —— 那个 yaw 会把枪管甩到侧面去）。
 *
 * 挂点在 {@code weapon_anchor}（骨架 v2 里挂在 right_hand 之下），所以肘和腕都会带着枪走。
 * 枪的朝向在动画里由 {@code right_hand} 微调（"转腕"）：礼的 {@code shoot} 用它做后坐枪口上跳、
 * {@code combo} 用它把枪托转向下。骨骼链与"为什么 weapon_anchor 的 rotation 必须为 0"
 * 见 {@link IkukoKatanaLayer} 的类注释。
 */
public class ReiGunLayer extends BlockAndItemGeoLayer<TadasugawaReiEntity> {

    /** 武器挂在角色模型的这个骨骼上（blockbench 里可自由拖动）。 */
    private static final String ANCHOR = "weapon_anchor";

    public ReiGunLayer(GeoRenderer<TadasugawaReiEntity> renderer) {
        super(renderer);
    }

    /** 只有挂点骨骼上渲染武器，其余骨骼返回 null 表示不渲染。 */
    @Override
    protected ItemStack getStackForBone(GeoBone bone, TadasugawaReiEntity animatable) {
        return ANCHOR.equals(bone.getName())
                ? new ItemStack(ModItems.REI_GUN.get())
                : null;
    }

    /** 用第三人称右手的标准握持姿态（display 块里对应的那组变换）。 */
    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, TadasugawaReiEntity animatable) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
}
