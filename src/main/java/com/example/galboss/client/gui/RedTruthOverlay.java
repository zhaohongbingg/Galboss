package com.example.galboss.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「红字」——贝阿朵莉切作为游戏主宰的权能：屏幕上直接写出<b>绝对真实</b>的断言。
 *
 * <h2>为什么是纯 HUD 而不是世界特效</h2>
 * 红字在原著里就不是爆炸、不是光束，而是「一句话压在你脸上」：因此它不该进
 * {@code PixelfxRenderer} 那套世界空间精灵，而应该画在原版 HUD 层的正中间 ——
 * 这样它不会被方块挡住、不受雾和光照影响、也不会被别的特效的深度顺序吃掉。
 *
 * <p>做法：服务端只发一个 {@code S2CRedTruthPacket}（语言键 + 时长），客户端在
 * {@link RenderGuiEvent.Post} 里画：8 方向描边 + 血红色本体 + 淡入淡出 + 轻微闪烁。
 * 描边比阴影（{@code dropShadow}）更像文字游戏里的红字，也更耐看。
 *
 * <p><b>同时只显示一行</b>（2026-11-17 改）：原先多行向下堆叠、最多 4 行，实战里一次死几个祭品
 * 会连推好几条红字，挤在屏幕上方互相压字，尤其是同一句「魔女尚未复苏」连推三次直接糊成一团。
 * 红字在原著里就是「一句话压在你脸上」，所以现在新的一句直接替换旧的：一条、一色（血红）、居中。
 */
public final class RedTruthOverlay {

    /** 血字本体色（不含 alpha）。 */
    private static final int RED = 0xFF1414;

    /** 描边色（黑，不含 alpha）。 */
    private static final int OUTLINE = 0x000000;

    /**
     * 描边的不透明度上限。
     *
     * <p>实测教训：中文字形笔画只有 1px，放大到 2.6 倍时 8 方向描边会把红色本体吃掉，
     * 整句看起来发黑。描边只做「压住背景」的活，不需要全实心 —— 0.72 刚好。
     */
    private static final int OUTLINE_ALPHA = 184;

    /** 淡入 / 淡出时长（毫秒）。 */
    private static final long FADE_IN_MS = 200L;
    private static final long FADE_OUT_MS = 380L;

    /** 当前这一行（同时只有一行）。 */
    private static Line current;

    /** 当前这批红字归属的客户端世界：换维度 / 退出重进时清空。 */
    private static Object currentLevel;

    private RedTruthOverlay() {
    }

    /** 一行红字。age 用真实时间算，游戏暂停时也照常淡出（红字是给你看的，不该被暂停冻住）。 */
    private static final class Line {
        final Component text;
        final long bornMs = System.currentTimeMillis();
        final int ticks;

        Line(Component text, int ticks) {
            this.text = text;
            this.ticks = ticks;
        }
    }

    /**
     * 收到服务端的红字。
     *
     * @param text 语言键或字面文本
     * @param arg  字面参数（可为空串）
     * @param i18n true = text 是语言键，按客户端语言解析
     */
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
        // 单行：新的直接顶掉旧的（不再堆叠，避免一次多条叠在一起糊成一片）
        current = new Line(component, Math.max(20, ticks));
    }

    /** 退出世界时清场（由客户端 tick / 渲染入口自行判断，无需额外事件）。 */
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
            // 换世界：上一场的红字不该跟进来
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
        // 轻微闪烁：文字游戏里红字的那种「不稳定感」，频率很低，不会晃眼
        float flicker = 0.88f + 0.12f * Mth.sin(age / 90.0f);
        int alpha = Mth.clamp((int) (255.0f * fadeIn * fadeOut * flicker), 0, 255);
        if (alpha <= 3) {
            return;
        }

        // 太长的句子按屏幕宽度缩放，保证一句话一行放得下（红字不该自动换行）
        // 上限 2.2：再大中文笔画就被描边吃掉了（实机调过）
        float scale = Math.min(2.2f, (width * 0.86f) / Math.max(1, font.width(text)));
        int y = (int) (height * 0.22);
        var pose = gui.pose();
        pose.pushPose();
        pose.translate(width / 2.0f, y, 0.0f);
        pose.scale(scale, scale, 1.0f);
        int left = -font.width(text) / 2;
        int body = (alpha << 24) | RED;
        int edge = (Math.min(alpha, OUTLINE_ALPHA) << 24) | OUTLINE;
        // 只描四个对角：8 方向全描在 2 倍缩放下会把 1px 的中文笔画围成黑块，
        // 对角描边既能把字从背景里剥出来，又留得住红色本体（实机调过两轮）。
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                gui.drawString(font, text, left + dx, dy, edge, false);
            }
        }
        gui.drawString(font, text, left, 0, body, false);
        pose.popPose();
    }
}
