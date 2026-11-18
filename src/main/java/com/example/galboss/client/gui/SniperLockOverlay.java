package com.example.galboss.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「你被瞄准了」的屏幕提示（狙击技能的预警层）。
 *
 * <p>表现：准星四周四个角括号 + 下方一行倒计时。红字是"事实"，这个是"威胁"，
 * 所以放在准星附近、用准星语言（括号 + 数字），不去抢屏幕正中的位置。
 *
 * <p>服务端只在锁定开始时发一次 {@code S2CSniperLockPacket(ticks)}，客户端按真实时间倒计时；
 * 命中/中断时服务端再发一次 release，立刻清掉。
 */
public final class SniperLockOverlay {

    /** 锁定结束时间（毫秒时间戳）；0 = 没被锁定。 */
    private static long untilMs;
    /** 本次锁定的总时长（毫秒），用来算进度。 */
    private static long totalMs;

    /** 括号离准星中心的距离（GUI 像素）。 */
    private static final int BRACKET = 34;
    /** 括号单边长度。 */
    private static final int ARM = 12;

    private static final int RED = 0xFF2A2A;

    private SniperLockOverlay() {
    }

    /** 开始锁定：从此刻起 ticks 个游戏 tick（1 tick = 50ms）后结束。 */
    public static void lock(int ticks) {
        totalMs = Math.max(200L, ticks * 50L);
        untilMs = System.currentTimeMillis() + totalMs;
    }

    /** 解除（命中 / 技能被打断）。 */
    public static void release() {
        untilMs = 0L;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || untilMs <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long left = untilMs - now;
        if (left <= 0L) {
            untilMs = 0L;
            return;
        }

        GuiGraphics gui = event.getGuiGraphics();
        int cx = gui.guiWidth() / 2;
        int cy = gui.guiHeight() / 2;

        float progress = 1.0f - left / (float) Math.max(1L, totalMs);
        // 收拢感：括号从外向里缩，越接近命中越紧
        int gap = (int) (BRACKET + (1.0f - progress) * 14.0f);
        // 临界时闪得更快更亮
        float urgency = Mth.clamp(progress * 1.4f, 0.0f, 1.0f);
        int alpha = (int) (150 + 105 * urgency * (0.6f + 0.4f * Mth.sin(now / (90.0f - 60.0f * urgency))));
        alpha = Mth.clamp(alpha, 0, 255);
        int color = (alpha << 24) | RED;

        drawBrackets(gui, cx, cy, gap, ARM, color);

        // 倒计时文字：被瞄准 1.4s
        float seconds = left / 1000.0f;
        String text = Component.translatable("sniper.galboss.locked", String.format("%.1f", seconds)).getString();
        int w = minecraft.font.width(text);
        int y = cy + gap + 6;
        int textColor = (Math.min(alpha, 230) << 24) | RED;
        gui.drawString(minecraft.font, text, cx - w / 2, y, textColor, false);

        // 一条横向进度条：剩余时间越少越短
        int barW = 60;
        int barLeft = cx - barW / 2;
        int barY = y + minecraft.font.lineHeight + 3;
        gui.fill(barLeft, barY, barLeft + barW, barY + 2, (alpha / 3 << 24) | 0x000000);
        gui.fill(barLeft, barY, barLeft + (int) (barW * (1.0f - progress)), barY + 2, color);
    }

    /** 四角括号（瞄准镜的角标）。 */
    private static void drawBrackets(GuiGraphics gui, int cx, int cy, int gap, int arm, int color) {
        // 左上
        gui.fill(cx - gap - arm, cy - gap, cx - gap, cy - gap + 1, color);
        gui.fill(cx - gap, cy - gap - arm, cx - gap + 1, cy - gap, color);
        // 右上
        gui.fill(cx + gap, cy - gap, cx + gap + arm, cy - gap + 1, color);
        gui.fill(cx + gap - 1, cy - gap - arm, cx + gap, cy - gap, color);
        // 左下
        gui.fill(cx - gap - arm, cy + gap, cx - gap, cy + gap + 1, color);
        gui.fill(cx - gap, cy + gap, cx - gap + 1, cy + gap + arm, color);
        // 右下
        gui.fill(cx + gap, cy + gap, cx + gap + arm, cy + gap + 1, color);
        gui.fill(cx + gap - 1, cy + gap, cx + gap, cy + gap + arm, color);
    }
}
