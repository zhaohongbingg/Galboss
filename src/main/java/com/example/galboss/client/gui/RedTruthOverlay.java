package com.example.galboss.client.gui;

import com.example.galboss.client.ScreenShake;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「红字」——贝阿朵莉切作为游戏主宰的权能：屏幕上直接写出绝对真实的断言。
 */
public final class RedTruthOverlay {

    private static final int RED = 0xFF1414;
    private static final int CRIMSON_GLOW = 0x8C0000;
    private static final int OUTLINE = 0x000000;
    private static final int OUTLINE_ALPHA = 184;

    private static final long SLAM_MS = 160L;
    private static final long FADE_IN_MS = 180L;
    private static final long FADE_OUT_MS = 380L;

    private static Line current;
    private static Object currentLevel;

    private RedTruthOverlay() {
    }

    private static final class Line {
        final Component text;
        final long bornMs = System.currentTimeMillis();
        final int ticks;

        Line(Component text, int ticks) {
            this.text = text;
            this.ticks = ticks;
        }
    }

    public static void push(String text, String arg, boolean i18n, int ticks) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Component component;
        if (i18n) {
            component = arg == null || arg.isEmpty()
                    ? Component.translatable(text)
                    : Component.translatable(text, arg);
        } else {
            component = Component.literal(text);
        }
        current = new Line(component, Math.max(20, ticks));
        // 红字宣言触发轻微冲击震屏，强化压迫感
        ScreenShake.push(0.85f, 6);
    }

    public static void clear() {
        current = null;
        currentLevel = null;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics gui = event.getGuiGraphics();
        if (minecraft.font == null || minecraft.player == null) {
            return;
        }
        if (minecraft.level != currentLevel) {
            current = null;
            currentLevel = minecraft.level;
        }
        Line line = current;
        if (line == null || minecraft.options.hideGui) {
            return;
        }

        long now = System.currentTimeMillis();
        long age = now - line.bornMs;
        long life = line.ticks * 50L;
        if (age > life) {
            current = null;
            return;
        }

        String text = line.text.getString();
        if (text.isEmpty()) {
            return;
        }

        Font font = minecraft.font;
        int width = gui.guiWidth();
        int height = gui.guiHeight();

        float fadeIn = Mth.clamp(age / (float) FADE_IN_MS, 0.0f, 1.0f);
        float fadeOut = Mth.clamp((life - age) / (float) FADE_OUT_MS, 0.0f, 1.0f);
        float flicker = 0.90f + 0.10f * Mth.sin(age / 85.0f);
        int alpha = Mth.clamp((int) (255.0f * fadeIn * fadeOut * flicker), 0, 255);
        if (alpha <= 3) {
            return;
        }

        // 前 160ms 由 1.32 倍快速砸落回弹至标准字号
        float slamT = Mth.clamp(age / (float) SLAM_MS, 0.0f, 1.0f);
        float slamEase = 1.0f - (1.0f - slamT) * (1.0f - slamT) * (1.0f - slamT);
        float slamScale = Mth.lerp(slamEase, 1.32f, 1.0f);

        float baseScale = Math.min(2.2f, (width * 0.86f) / Math.max(1, font.width(text)));
        float scale = baseScale * slamScale;
        int centerY = (int) (height * 0.22);

        // 暗猩红横幅底衬：防止在黄金乡亮金方块背景下文字失去对比度
        int bannerHalfH = (int) Math.ceil((font.lineHeight * baseScale) * 0.72f) + 4;
        int bgAlpha = (int) (115 * fadeIn * fadeOut);
        int borderAlpha = (int) (175 * fadeIn * fadeOut);
        if (bgAlpha > 2) {
            int top = centerY - bannerHalfH;
            int bottom = centerY + bannerHalfH;
            gui.fillGradient(0, top, width, bottom, (bgAlpha << 24) | 0x180205, (bgAlpha << 24) | 0x2B0408);
            gui.fill(0, top, width, top + 1, (borderAlpha << 24) | 0xA81818);
            gui.fill(0, bottom - 1, width, bottom, (borderAlpha << 24) | 0xD4AF37);
        }

        var pose = gui.pose();
        pose.pushPose();
        pose.translate(width / 2.0f, centerY - (font.lineHeight * scale) * 0.5f, 0.0f);
        pose.scale(scale, scale, 1.0f);
        int left = -font.width(text) / 2;

        // 柔和外层血红光晕
        int glowAlpha = (int) (alpha * 0.28f);
        if (glowAlpha > 4) {
            int glow = (glowAlpha << 24) | CRIMSON_GLOW;
            gui.drawString(font, text, left - 2, 0, glow, false);
            gui.drawString(font, text, left + 2, 0, glow, false);
        }

        int body = (alpha << 24) | RED;
        int edge = (Math.min(alpha, OUTLINE_ALPHA) << 24) | OUTLINE;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                gui.drawString(font, text, left + dx, dy, edge, false);
            }
        }
        gui.drawString(font, text, left, 0, body, false);
        pose.popPose();
    }
}
