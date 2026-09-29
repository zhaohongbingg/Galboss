package com.example.galboss.client.gui;

import com.example.galboss.GalBoss;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;

/**
 * 三名 boss 各自的血条外观。
 *
 * 贴图按「角色注册名_颜色_特征」命名，放在 textures/bossbar 下：
 *  - {@code *_frame.png}  512x64，外框 + 空槽底 + 两端徽记
 *  - {@code *_fill.png}   182x6，血条填充，本身是循环花纹，按进度裁切
 */
public enum FsBossBarStyle {

    /** 冷泉院 桐香 —— 会长，翠绿 */
    REIZEIN_TOHKA("reizein_tohka", "green", 0x7CE0A0),

    /** 糺川 礼 —— 风纪，琥珀色箭羽纹 */
    TADASUGAWA_REI("tadasugawa_rei", "amber_chevron", 0xC9A06A),

    /** 女部田 郁子 —— 一番队，藏青斜纹 */
    ONABUTA_IKUKO("onabuta_ikuko", "azure", 0xC6DAEC);

    /** 血条名字用的语言键前缀，也是识别「这是不是我们的 boss」的依据。 */
    private static final String ENTITY_KEY_PREFIX = "entity.galboss.";

    private final String entityId;
    private final ResourceLocation frame;
    private final ResourceLocation fill;
    private final int textColor;

    FsBossBarStyle(String entityId, String trait, int textColor) {
        this.entityId = entityId;
        this.frame = tex(entityId + "_" + trait + "_frame");
        this.fill = tex(entityId + "_" + trait + "_fill");
        this.textColor = textColor;
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "textures/bossbar/" + name + ".png");
    }

    public ResourceLocation frame() {
        return this.frame;
    }

    public ResourceLocation fill() {
        return this.fill;
    }

    public int textColor() {
        return this.textColor;
    }

    /**
     * 从 boss 血条的标题反查外观。
     *
     * 客户端拿到的是 {@code ClientBossEvent}，服务端自定义的 BossEvent 子类在这里已经被重建掉了，
     * 所以只能靠同步过来的标题判断。标题是用 {@code Component.translatable("entity.galboss." + id)} 建的，
     * 翻译键原样传过来，直接拿它当身份标识最稳。
     *
     * @return 不是本模组的 boss 时返回 null
     */
    public static FsBossBarStyle forBossEvent(BossEvent event) {
        Component name = event.getName();
        if (!(name.getContents() instanceof TranslatableContents contents)) {
            return null;
        }
        String key = contents.getKey();
        if (!key.startsWith(ENTITY_KEY_PREFIX)) {
            return null;
        }
        String entityId = key.substring(ENTITY_KEY_PREFIX.length());
        for (FsBossBarStyle style : values()) {
            if (style.entityId.equals(entityId)) {
                return style;
            }
        }
        return null;
    }
}
