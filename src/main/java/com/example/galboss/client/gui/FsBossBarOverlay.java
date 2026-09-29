package com.example.galboss.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 三名 boss 的自定义血条。
 *
 * 走 Forge 的 {@link CustomizeGuiOverlayEvent.BossEventProgress}：画完自己的贴图后取消事件，
 * 原版血条就不会再叠一层。只处理标题以 {@code entity.galboss.} 开头的 boss，其它模组的血条不受影响。
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FsBossBarOverlay {

    // 外框贴图原始尺寸
    private static final int FRAME_TEX_W = 512;
    private static final int FRAME_TEX_H = 64;

    // 显示尺寸：按 182/512 缩放，正好让 182x6 的 fill 与内槽对齐
    private static final int BAR_W = 182;
    private static final int BAR_H = 23;

    // 外框贴图里内槽的实际位置（实测：x 34..475, y 24..43），同样缩放后的显示坐标
    private static final int SLOT_X = 12;
    private static final int SLOT_Y = 9;
    private static final int SLOT_W = 157;
    private static final int SLOT_H = 7;

    // 填充贴图原始尺寸（本身是循环花纹，按进度从左往右裁）
    private static final int FILL_TEX_W = 182;
    private static final int FILL_TEX_H = 6;

    private static final int NAME_OFFSET_Y = -9;
    private static final int STACK_SPACING = BAR_H + 8;

    private FsBossBarOverlay() {
    }

    @SubscribeEvent
    public static void onBossBarProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        FsBossBarStyle style = FsBossBarStyle.forBossEvent(event.getBossEvent());
        if (style == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = event.getX();
        int y = event.getY();

        // 注意：这里必须用带 uOffset/vOffset 的重载。
        // 9 参版本 blit(tex, x, y, u, v, w, h, texW, texH) 是不缩放的，
        // 它只会把源图 (u,v,w,h) 那一块按原尺寸贴出来。

        // 1. 外框：整张 512x64 缩放到 182x23（自带空槽底色与两端徽记）
        graphics.blit(style.frame(), x, y, BAR_W, BAR_H,
                0.0f, 0.0f, FRAME_TEX_W, FRAME_TEX_H, FRAME_TEX_W, FRAME_TEX_H);

        // 2. 填充：宽度随进度增长，源区域按同样比例裁切，所以花纹不会被压扁
        int filled = (int) (event.getBossEvent().getProgress() * SLOT_W);
        if (filled > 0) {
            graphics.blit(style.fill(), x + SLOT_X, y + SLOT_Y, filled, SLOT_H,
                    0.0f, 0.0f, filled, FILL_TEX_H, FILL_TEX_W, FILL_TEX_H);
        }

        // 3. 名字：居中在外框上方，用各角色的主题色
        Component name = event.getBossEvent().getName().copy()
                .withStyle(Style.EMPTY.withColor(style.textColor()));
        Font font = Minecraft.getInstance().font;
        int textX = x + BAR_W / 2 - font.width(name) / 2;
        graphics.drawString(font, name, textX, y + NAME_OFFSET_Y, style.textColor());

        // 4. 让后续血条往下排，并取消原版绘制
        event.setIncrement(STACK_SPACING);
        event.setCanceled(true);
    }
}
