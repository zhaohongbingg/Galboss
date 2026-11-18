package com.example.galboss.client;

import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 屏震：把相机的 yaw / pitch 按「高频正弦 × 线性衰减」推一下。
 *
 * <h2>为什么不是随机抖动</h2>
 * 随机抖动看起来像「镜头在乱晃」，人会晕、也读不出冲击方向；
 * 两条不同频率的正弦叠加（37 / 53）更像一次冲击波扫过眼眶，而且天然连续、不会跳帧。
 *
 * <p>只改相机角度：不参与命中判定、不影响其它渲染阶段、幅度封顶 4°。
 * 由 {@code ViewportEvent.ComputeCameraAngles} 驱动（每帧一次），所以用**毫秒**算相位与衰减，
 * 高帧率下不会比低帧率抖得更久。
 */
public final class ScreenShake {

    private static float strength;
    private static long startMs;
    private static int ticks;

    private ScreenShake() {
    }

    /** 收到一次震动请求。同一时间只保留最强的一次（叠加会晕）。 */
    public static void push(float newStrength, int newTicks) {
        long now = System.currentTimeMillis();
        boolean running = now - startMs < ticks * 50L;
        if (running && newStrength <= strength) {
            return;
        }
        strength = newStrength;
        ticks = newTicks;
        startMs = now;
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (ticks <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        float decay = 1.0f - elapsed / (ticks * 50.0f);
        if (decay <= 0.0f) {
            ticks = 0;
            strength = 0.0f;
            return;
        }
        double t = elapsed / 50.0;
        event.setYaw(event.getYaw() + (float) (Math.sin(t * 37.0) * strength * decay));
        event.setPitch(event.getPitch() + (float) (Math.sin(t * 53.0 + 1.7) * strength * 0.6 * decay));
    }
}
