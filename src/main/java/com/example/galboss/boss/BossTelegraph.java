package com.example.galboss.boss;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 预警粒子画笔。
 *
 * <p>Geburah 的攻击预警全部是「粒子选项对象 + 时长」，不建预警实体：不占实体 ID、
 * 纯客户端开销、迟进场的玩家也能看到正确的进度。这里把同样的做法压成几个静态方法，
 * 用原版尘粒（{@link DustParticleOptions}）调色，不需要注册任何自定义粒子类型。
 */
public final class BossTelegraph {

    /** 纪律 / 学生会主题的金。 */
    public static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(1.0f, 0.78f, 0.15f), 1.1f);

    /** 处分的猩红。 */
    public static final DustParticleOptions SCARLET = new DustParticleOptions(new Vector3f(0.85f, 0.10f, 0.12f), 1.2f);

    /** 威压的紫。 */
    public static final DustParticleOptions VIOLET = new DustParticleOptions(new Vector3f(0.62f, 0.32f, 0.95f), 1.1f);

    private BossTelegraph() {
    }

    /** 单个圆最多铺多少颗粒子：半径大时不再等比增长，否则一圈几百颗会拖客户端。 */
    private static final int MAX_CIRCLE_POINTS = 48;

    /** 在地面上画一个圆（预警圈 / 冲击波推进的每一环）。 */
    public static void circle(Level level, Vec3 center, double radius, DustParticleOptions dust) {
        if (!(level instanceof ServerLevel serverLevel) || radius <= 0.05) {
            return;
        }
        int points = Math.min(MAX_CIRCLE_POINTS, Math.max(12, (int) Math.round(radius * 8)));
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            serverLevel.sendParticles(dust,
                    center.x + Math.cos(angle) * radius,
                    center.y + 0.12,
                    center.z + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 画矩形预警框的地面轮廓（矩形重击）。 */
    public static void boxOutline(Level level, AABB box, DustParticleOptions dust) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double y = box.minY + 0.12;
        int steps = 8;
        for (int i = 0; i <= steps; i++) {
            double tx = box.minX + (box.maxX - box.minX) * i / steps;
            double tz = box.minZ + (box.maxZ - box.minZ) * i / steps;
            serverLevel.sendParticles(dust, tx, y, box.minZ, 1, 0.0, 0.0, 0.0, 0.0);
            serverLevel.sendParticles(dust, tx, y, box.maxZ, 1, 0.0, 0.0, 0.0, 0.0);
            serverLevel.sendParticles(dust, box.minX, y, tz, 1, 0.0, 0.0, 0.0, 0.0);
            serverLevel.sendParticles(dust, box.maxX, y, tz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 召唤仪式：一圈魂火 + 中心附魔闪光。 */
    public static void convocation(Level level, Vec3 center, double radius) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        circle(level, center, radius, GOLD);
        int points = 12;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            serverLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    center.x + Math.cos(angle) * radius,
                    center.y + 0.35,
                    center.z + Math.sin(angle) * radius,
                    2, 0.05, 0.05, 0.05, 0.01);
        }
        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                center.x, center.y + 0.8, center.z,
                24, radius * 0.5, 0.8, radius * 0.5, 0.25);
    }

    /** 命中 / 处分的爆点。 */
    public static void impact(Level level, Vec3 at, DustParticleOptions dust, int count) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.sendParticles(dust, at.x, at.y + 0.4, at.z, count, 0.45, 0.45, 0.45, 0.15);
        serverLevel.sendParticles(ParticleTypes.CRIT, at.x, at.y + 0.5, at.z, count / 2, 0.5, 0.3, 0.5, 0.3);
    }
}
