package com.example.galboss.fx;

import com.example.galboss.particle.ModParticles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 「金色蝴蝶攻击」命中爆发特效（4 阶段演出）。
 */
public final class GoldenButterflyBurst {

    private static final int MIN_BUTTERFLIES = 4;
    private static final int MAX_BUTTERFLIES = 6;
    private static final int MIN_DOTS = 8;
    private static final int MAX_DOTS = 12;

    private GoldenButterflyBurst() {
    }

    public static void burst(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server) || at == null) {
            return;
        }
        int n = MIN_BUTTERFLIES + server.random.nextInt(MAX_BUTTERFLIES - MIN_BUTTERFLIES + 1);

        // 1) 命中瞬间：十字星芒 + 扩散光环
        server.sendParticles(ModParticles.BURST_STAR.get(), at.x, at.y, at.z, 2, 0.04, 0.04, 0.04, 0.0);
        server.sendParticles(ModParticles.BURST_RING.get(), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);

        // 2) 螺旋飞出的带辉光橙蝶群
        server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), at.x, at.y + 0.1, at.z,
                n, 0.0, 0.0, 0.0, 0.0);

        // 3) 余晖星屑
        int dots = MIN_DOTS + server.random.nextInt(MAX_DOTS - MIN_DOTS + 1);
        server.sendParticles(ModParticles.BURST_DOT.get(), at.x, at.y, at.z,
                dots, 0.26, 0.20, 0.26, 0.02);
    }

    public static void burstAtChest(Level level, Vec3 feet) {
        burst(level, feet == null ? null : feet.add(0.0, 1.0, 0.0));
    }
}
