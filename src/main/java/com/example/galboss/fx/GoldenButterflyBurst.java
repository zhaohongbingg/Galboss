package com.example.galboss.fx;

import com.example.galboss.particle.ModParticles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 「金色蝴蝶攻击」命中爆发特效（设计图 4 阶段）。
 *
 * <pre>
 *   1. 命中瞬间（0~2 tick）：星芒爆闪 + 光环扩散
 *   2. 蝴蝶飞出（2~8 tick）：3~6 只金色蝴蝶从命中点螺旋飞出
 *   3. 盘旋绽放（8~14 tick）：螺旋半径变大、缓缓上升
 *   4. 逐渐消散（14~20 tick）：蝴蝶/光点淡出
 * </pre>
 *
 * <p>实现方式：**服务端一次 sendParticles 把四类粒子都发出去**，每个粒子自己按年龄推进
 * 螺旋/淡出/扇翅（见 {@code client.particle.ButterflyBurstParticle} 等），
 * 所以不需要网络包或客户端调度器 —— 命中点由调用方（贝阿朵莉切法术结算、调试命令）给出。
 *
 * <p>参数按设计图建议值：蝴蝶 3~6 只、大小 0.16~0.22、扩散 0.6~1.0 格、生命 20 tick。
 */
public final class GoldenButterflyBurst {

    /** 蝴蝶数量下限 / 上限（设计图：3~6）。 */
    private static final int MIN_BUTTERFLIES = 3;
    private static final int MAX_BUTTERFLIES = 6;
    /** 余晖光点数量。 */
    private static final int MIN_DOTS = 8;
    private static final int MAX_DOTS = 12;

    private GoldenButterflyBurst() {
    }

    /**
     * 在 {@code at} 处播放一次命中爆发。
     *
     * <p>服务端调用；附近玩家会收到粒子包（sendParticles 自带 32 格可见性）。
     */
    public static void burst(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server) || at == null) {
            return;
        }
        int n = MIN_BUTTERFLIES + server.random.nextInt(MAX_BUTTERFLIES - MIN_BUTTERFLIES + 1);

        // 1) 命中瞬间：星芒（2 颗，大小/旋转随机）+ 光环（1 个）
        server.sendParticles(ModParticles.BURST_STAR.get(), at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0.0);
        server.sendParticles(ModParticles.BURST_RING.get(), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);

        // 2) 蝴蝶：同一位置发 n 只（每只粒子内部随机螺旋方向/半径/相位）
        server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), at.x, at.y + 0.1, at.z,
                n, 0.0, 0.0, 0.0, 0.0);

        // 3) 余晖光点：小范围随机位置 + 小随机速度，向上飘
        int dots = MIN_DOTS + server.random.nextInt(MAX_DOTS - MIN_DOTS + 1);
        server.sendParticles(ModParticles.BURST_DOT.get(), at.x, at.y, at.z,
                dots, 0.28, 0.22, 0.28, 0.02);
    }

    /** 便捷：以实体/玩家的脚部为基准，抬高到胸口位置再爆发。 */
    public static void burstAtChest(Level level, Vec3 feet) {
        burst(level, feet == null ? null : feet.add(0.0, 1.0, 0.0));
    }
}
