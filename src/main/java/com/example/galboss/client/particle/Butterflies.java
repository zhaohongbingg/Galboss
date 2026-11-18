package com.example.galboss.client.particle;

import com.example.galboss.GalBoss;
import net.minecraft.resources.ResourceLocation;

/**
 * 蝴蝶粒子可用的精灵表（图集）清单。
 *
 * <h2>当前用的是 ER 橙蝶（2 帧拍翅）</h2>
 * 素材来自 {@code file:///D:/game/mc/er-particle-preview.html} 里内嵌的 {@code butterfly_orange_flap}
 * （{@code key=butterfly_orange_flap，idx=0/1，32×32}，4 色调色板：橙底 / 暗橙描边 / 深褐轮廓 / 亮黄高光）。
 * 由 {@code tools/particle/import_er_butterfly.py} 打成 64×32 的 2 帧图集，并同布局生成加色发光层。
 *
 * <h2>金色 8 帧图集仍保留</h2>
 * {@link #GOLD_BODY} / {@link #GOLD_GLOW} 是原先那套 4×2、64px、8 帧的镂空金蝶
 * （{@code tools/particle/make_golden_butterfly_ref.py} 生成）。想换回去只要把
 * {@link ButterflyParticle} / {@link ButterflyGlowParticle} 里引用的 sprite 改成这两个，
 * 图集布局会跟着 sprite 自动切换，不用再动常量。
 */
public final class Butterflies {

    private Butterflies() {
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, path);
    }

    // ---------------------------------------------------------------- ER 橙蝶（默认）

    /** ER 橙蝶本体：64×32，2 帧 × 32px，横向排列。 */
    public static final ButterflySprite ORANGE_BODY = new ButterflySprite(
            rl("textures/particle/butterfly_orange.png"), 2, 1, 32, 3);

    /** ER 橙蝶的加色发光层：同布局、同帧序，帧长必须和本体一致（否则辉光会错位）。 */
    public static final ButterflySprite ORANGE_GLOW = new ButterflySprite(
            rl("textures/particle/butterfly_orange_glow.png"), 2, 1, 32, 3);

    // ---------------------------------------------------------------- 旧的镂空金蝶（备用）

    /** 金色镂空蝶本体：256×128，4 列 × 2 行，8 帧 × 64px。 */
    public static final ButterflySprite GOLD_BODY = new ButterflySprite(
            rl("textures/particle/golden_butterfly.png"), 4, 2, 64, 3);

    /** 金色镂空蝶辉光层。 */
    public static final ButterflySprite GOLD_GLOW = new ButterflySprite(
            rl("textures/particle/golden_butterfly_glow.png"), 4, 2, 64, 3);
}
