package com.example.galboss.client.renderer.layer;

import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.item.ModItems;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/**
 * 把烟斗作为**物品**渲染在右手上 —— 与 {@link IkukoKatanaLayer} / {@link ReiGunLayer} 同一套做法。
 *
 * <p>为什么不用「独立 GeoModel + 手算矩阵」：烟斗有自己的贴图，塞不进角色那张 64x128 的图集；
 * 而且独立 GeoModel 的位置要靠一串魔法偏移常量对齐，角色动画一动就飘。
 * 走 {@link BlockAndItemGeoLayer} 之后，位置与朝向交给原版物品系统的
 * {@link ItemDisplayContext} 标准握持变换，模型里也没有骨骼，角色动画不会按名字套到烟斗上。
 *
 * <p>两个必须守住的约定（细节见 {@link IkukoKatanaLayer} 的类注释，那里踩过坑）：
 * <ul>
 *   <li>挂点骨骼 {@code RightHandLocator} 的 rotation 必须保持全 0。
 *       BlockAndItemGeoLayer 会把目标骨骼的旋转再应用一次，转 θ 会变成 2θ；</li>
 *   <li>握持姿态与缩放只写在 models/item/beatrice_pipe.json 的 display 块里，
 *       该文件由 tools/blockbench 从 geo/beatrice_pipe.geo.json 生成，别手改。</li>
 * </ul>
 *
 * <p>骨骼是「定位器」而不是「武器骨」：动画里不要驱动它，驱动 {@code right_hand} 之类的
 * 手臂骨骼，手腕的动作自然会带着烟斗走。
 */
public class BeatricePipeLayer extends BlockAndItemGeoLayer<BeatriceEntity> {

    /** 烟斗挂在角色模型的这个骨骼上（geo 里存在，rotation 全 0）。 */
    private static final String ANCHOR = "RightHandLocator";

    public BeatricePipeLayer(GeoRenderer<BeatriceEntity> renderer) {
        super(renderer);
    }

    /** 只有挂点骨骼上渲染烟斗，其余骨骼返回 null 表示不渲染。 */
    @Override
    protected ItemStack getStackForBone(GeoBone bone, BeatriceEntity animatable) {
        return ANCHOR.equals(bone.getName())
                ? new ItemStack(ModItems.BEATRICE_PIPE.get())
                : null;
    }

    /** 用第三人称右手的标准握持姿态（display 块里对应的那组变换）。 */
    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, BeatriceEntity animatable) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
}
