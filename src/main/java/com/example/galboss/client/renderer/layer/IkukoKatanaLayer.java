package com.example.galboss.client.renderer.layer;

import com.example.galboss.entity.OnabutaIkukoEntity;
import com.example.galboss.item.ModItems;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/**
 * 把武士刀作为**物品**渲染在右手上 —— 走 GeckoLib 官方的 {@link BlockAndItemGeoLayer}。
 *
 * 这是 Mowzie's Mobs / 灾变 / 原版 ItemInHandLayer 的做法：位置与朝向交给原版物品系统
 * 的 {@link ItemDisplayContext} 标准握持变换，代码里不需要任何矩阵或偏移常量。
 *
 * 相比之前「独立 GeoModel + 自定义渲染层」的方案，这里避免了三个坑：
 *   1. 位置不准 —— 不再手算偏移，用原版标准握姿
 *   2. 武器绕自身轴自转 —— 物品模型没有骨骼，角色动画不会按名字套上来
 *   3. 单贴图限制 —— 物品有自己的贴图系统，不需要合成图集
 *
 * 握持姿态与缩放改在 models/item/ikuko_katana.json 的 display 块里调
 * （由 tools/blockbench/geo_to_item_model.mjs 生成，别手改那个文件）。
 *
 * ---------------------------------------------------------------------------
 * 这一层拿到的 PoseStack 到底是什么状态（核对过 GeckoLib 4.4.2 源码，别再猜）：
 *
 *   GeoRenderer.renderRecursively() 的顺序是
 *       pushPose() -> RenderUtils.prepMatrixForBone(bone) -> renderCubesOfBone()
 *       -> applyRenderLayersForBone()   // 就是我们这里
 *   而 prepMatrixForBone = translateMatrixToBone + 枢轴平移 + 旋转 + 缩放 + 平移回去，
 *   所以回调进来时，PoseStack 上**已经叠好了 weapon_anchor 从 root 一路累积下来的变换**。
 *
 *   BlockAndItemGeoLayer 自己又调了一次
 *       RenderUtils.translateAndRotateMatrixForBone(poseStack, bone)
 *   —— 只有「平移枢轴 + 旋转」，没有 translateMatrixToBone。净效果是：
 *
 *       Frame(anchor) * T(pivot) * R(anchor) * R(anchor)
 *
 *   枢轴位置不会被挪动（T(pivot) 被上一层 prepMatrixForBone 末尾的 T(-pivot) 抵消），
 *   但 **anchor 自己的旋转被应用了两次**：在 blockbench 里把 weapon_anchor 转 θ，
 *   游戏里武器会转 2θ。所以挂点骨骼保持 rotation=0，朝向去改物品模型的 display。
 *
 *   枢轴：weapon_anchor 现在挂在 right_hand 之下（骨架 v2），它的 pivot.x 必须和祖先里那条
 *   **有立方体的手臂** right_arm 同号。
 *   right_arm 的立方体在 x −8..−4（手心 −6），所以挂点是 pivot=[-6,12,0]。
 *
 *   ⚠ 这里踩过坑：GeckoLib 载入 bedrock geo 时对骨骼 pivot 和立方体 origin 都做 X 取反
 *   （BakedModelFactory 的 `updatePivot(-pivot.x, ...)` 与 `origin = -(origin.x + size.x)`），
 *   两者被同样处理，所以「符号相同」在任何坐标系里都成立。曾经把挂点写成 +6，
 *   看着像「手心」其实是**另一条手臂**的位置：武器悬在身体另一侧，手臂摆动时还在旁边乱晃。
 *   修正脚本：`tools/blockbench/fix_weapon_anchor_side.mjs`。
 *
 *   枢轴在手心 = 手臂动画一动它就跟着动，武器因此固定在手上。
 *
 *   骨骼链（骨架 v2）：body > right_arm > right_forearm > right_hand > weapon_anchor。
 *   挂点挂到 right_hand 之下之后，挥肘、转腕都会带着武器走。分工是：
 *
 *     - right_arm / right_forearm —— 管手臂姿势（大臂、肘）
 *     - right_hand                —— 管**武器朝向**，也就是"转腕"。它的旋转只会被应用一次
 *       （被重复应用的只有作为本层目标骨骼的 weapon_anchor），所以可以直接在动画里用：
 *       ±25° 以内的小角度用来调刀身 / 枪口的朝向，视觉上就是手腕的动作；再大就会像武器
 *       在手心里自转 —— 因为手部网格（前臂立方体的下半截）并不会跟着转。
 *     - weapon_anchor             —— 只决定"握在哪儿"，rotation 必须保持 0。
 *
 *   当前用到 right_hand 的动画：郁子的 attack / combo / cast（转腕摆刀身）、
 *   礼的 shoot / combo（后坐枪口上跳、枪托朝下）。定义在 tools/blockbench/anims.mjs 的 CHARACTER_ANIMS。
 *
 *   物品侧还要满足一个约定：**握把要落在物品模型空间的中心 (8,8,8)**。
 *   原版物品渲染会先绕模型中心做 display 的旋转/缩放，再 T(-0.5,-0.5,-0.5)，
 *   所以握把在中心 = 旋转轴在握把上 = 无论 display 怎么转，握把都停在骨骼原点。
 * ---------------------------------------------------------------------------
 */
public class IkukoKatanaLayer extends BlockAndItemGeoLayer<OnabutaIkukoEntity> {

    /** 武器挂在角色模型的这个骨骼上（blockbench 里可自由拖动旋转）。 */
    private static final String ANCHOR = "weapon_anchor";

    public IkukoKatanaLayer(GeoRenderer<OnabutaIkukoEntity> renderer) {
        super(renderer);
    }

    /** 只有挂点骨骼上渲染武器，其余骨骼返回 null 表示不渲染。 */
    @Override
    protected ItemStack getStackForBone(GeoBone bone, OnabutaIkukoEntity animatable) {
        return ANCHOR.equals(bone.getName())
                ? new ItemStack(ModItems.IKUKO_KATANA.get())
                : null;
    }

    /** 用第三人称右手的标准握持姿态（display 块里对应的那组变换）。 */
    @Override
    protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, OnabutaIkukoEntity animatable) {
        return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
}
