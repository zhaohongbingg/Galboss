package com.example.galboss.fx;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

/**
 * 黄金蝶的飞行螺旋参数（服务端可用，不引用任何客户端类）。
 */
public record ButterflyPathParams(
        double radius,
        double pitch,
        double verticalSquash,
        double spin,
        int spawnIntervalTicks,
        double speed) {

    public static final String DEFINITION_ID = "butterfly_path";

    public static final double NOMINAL_SPEED = 0.62;

    public static final ButterflyPathParams DEFAULT =
            new ButterflyPathParams(1.30, 8.00, 0.55, -1.0, 2, NOMINAL_SPEED);

    public double omegaRadPerTick(double speed) {
        return 2.0 * Math.PI * Math.max(speed, 1.0E-3) / Math.max(this.pitch, 1.0E-2);
    }

    public double phaseAngle(double speed, int ticks) {
        return this.spin * omegaRadPerTick(speed) * ticks;
    }

    public double ticksPerTurn(double speed) {
        return 2.0 * Math.PI / Math.max(omegaRadPerTick(speed), 1.0E-6);
    }

    public double samplesPerTurn(double speed) {
        return ticksPerTurn(speed) / Math.max(this.spawnIntervalTicks, 1);
    }

    public boolean enabled() {
        return this.radius > 1.0E-4;
    }

    public static ButterflyPathParams load() {
        JsonObject json = EffectLibrary.definition(DEFINITION_ID).orElse(null);
        if (json == null) {
            return DEFAULT;
        }
        try {
            return new ButterflyPathParams(
                    clamp(GsonHelper.getAsDouble(json, "radius", DEFAULT.radius), 0.0, 8.0),
                    clamp(GsonHelper.getAsDouble(json, "pitch", DEFAULT.pitch), 0.25, 64.0),
                    clamp(GsonHelper.getAsDouble(json, "verticalSquash", DEFAULT.verticalSquash), 0.0, 1.0),
                    GsonHelper.getAsDouble(json, "spin", DEFAULT.spin) < 0.0 ? -1.0 : 1.0,
                    (int) clamp(GsonHelper.getAsInt(json, "spawnIntervalTicks", DEFAULT.spawnIntervalTicks), 1, 20),
                    clamp(GsonHelper.getAsDouble(json, "speed", DEFAULT.speed), 0.05, 4.0));
        } catch (Exception e) {
            com.example.galboss.GalBoss.LOGGER.warn(
                    "[fx] {} 参数解析失败，回落到默认值: {}", DEFINITION_ID, e.toString());
            return DEFAULT;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
