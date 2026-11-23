package com.example.galboss.client.renderer;

import com.example.galboss.client.model.PurgatoryPillarModel;
import com.example.galboss.item.PurgatoryPillarItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 炼狱之柱的物品渲染器。
 *
 * <p>走 {@link GeoItemRenderer}（bedrock geo 通路），所以柱子那 819 个自由角度旋转的方块
 * 原样生效 —— 这是选 GeoItem 而不是原版 elements 物品模型的唯一原因。
 *
 * <p>不额外写 scale：geo 里的尺寸就是设计尺寸（长 29.41 单位 ≈ 1.84 格）。
 * 手上朝向由 {@code models/item/purgatory_pillar.json} 的 {@code display} 决定。
 */
public class PurgatoryPillarItemRenderer extends GeoItemRenderer<PurgatoryPillarItem> {

    public PurgatoryPillarItemRenderer() {
        super(new PurgatoryPillarModel());
    }
}
