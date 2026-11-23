package com.example.galboss.client;

import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 屏震：高频三轴谐波 × 二次方冲击衰减。
 */
public final class ScreenShake {

    private static final float MAX_STRENGTH = 4.2f;

    private static float strength;
    private static long startMs;
    private static int ticks;

    private ScreenShake() {
    }

    /** 收到一次震动请求；若当前仍在震动中则保留最强波并叠加少量余震冲量。 */
    public static void push(float newStrength, int newTicks) {
        long now = System.currentTimeMillis();
        long elapsed = now - startMs;
        float totalMs = ticks * 50.0f;
        boolean running = ticks > 0 && elapsed < totalMs;

        if (running) {
            float remRatio = 1.0f - (elapsed / totalMs);
            float currentEffective = strength * remRatio * remRatio;
            if (newStrength <= currentEffective) {
                return;
            }
            strength = Mth.clamp(newStrength + currentEffective * 0.25f, 0.0f, MAX_STRENGTH);
        } else {
            strength = Mth.clamp(newStrength, 0.0f, MAX_STRENGTH);
        }
        ticks = Math.max(1, newTicks);
        startMs = now;
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (ticks <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        float progress = elapsed / (ticks * 50.0f);
        if (progress >= 1.0f) {
            ticks = 0;
            strength = 0.0f;
            return;
        }
        // 二次方衰减：首帧冲击干脆利落，后半段平滑收敛不晕镜头
        float decay = (1.0f - progress) * (1.0f - progress);
        double t = elapsed / 50.0;

        float yawOffset = (float) (Math.sin(t * 37.0) * strength * decay);
        float pitchOffset = (float) (Math.sin(t * 53.0 + 1.7) * strength * 0.62 * decay);
        float rollOffset = (float) (Math.sin(t * 29.0 + 0.8) * strength * 0.36 * decay);

        event.setYaw(event.getYaw() + yawOffset);
        event.setPitch(event.getPitch() + pitchOffset);
        event.setRoll(event.getRoll() + rollOffset);
    }
}
