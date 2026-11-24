package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 法术共用的画笔与结算工具：预警、落雷、伤害、屏震。
 *
 * <p>所有特效都走 pixelfx slug（零新增美术）。v1 烘焙完成后已把 v0 占位符换成专用素材：
 * <ul>
 *   <li>雷柱 {@code epitaph_lightning_bolt}（原来是 {@code tohka_verdict_smite} 圣光柱顶替）</li>
 *   <li>地面冲击 {@code tohka_bell_wave}（本来就是专用件，保留）</li>
 *   <li>神枪 {@code epitaph_gungnir} / {@code epitaph_spear_fall} / {@code epitaph_spear_impact}</li>
 *   <li>雷锤 {@code epitaph_mjolnir}、神盾 {@code epitaph_aegis}、天幕 {@code epitaph_storm_sky}</li>
 * </ul>
 *
 * <p><b>换 slug 必须重算 scale</b>：世界尺寸 ≈ {@code defaultScale × scale × frameW}，
 * 两个素材的 defaultScale 和帧宽都不一样，直接沿用旧倍数会大一圈或小一圈。
 * 每个调用点下面都写了换算过程。
 *
 * <p>伤害约定：每种法术自己持有一个 {@code Set<UUID>} —— **同一次施法对同一目标只结算一次**，
 * 这样「多段特效」不会变成多段伤害。
 */
public final class SpellFx {

    /** 电光预警色（冷白）。 */
    public static final DustParticleOptions SPARK = new DustParticleOptions(new Vector3f(0.82f, 0.93f, 1.0f), 1.0f);

    /** 金色预警色。 */
    public static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(1.0f, 0.78f, 0.15f), 1.1f);

    /** 预警圈最多铺多少颗粒子（半径大时不等比增长，否则一圈几百颗）。 */
    private static final int MAX_POINTS = 48;

    /** 线状预警每段最多铺多少颗。 */
    private static final int MAX_LINE_POINTS = 64;

    private SpellFx() {
    }

    // ------------------------------------------------------------------ 预警

    /** 地面圆（预警 / 冲击环）。 */
    public static void circle(Level level, Vec3 center, double radius, DustParticleOptions dust) {
        circle(level, center, radius, dust, MAX_POINTS);
    }

    static void circle(Level level, Vec3 center, double radius, DustParticleOptions dust, int pointLimit) {
        if (!(level instanceof ServerLevel server) || radius <= 0.05) {
            return;
        }
        int points = Math.min(pointLimit, Math.max(12, (int) Math.round(radius * 8)));
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            server.sendParticles(dust,
                    center.x + Math.cos(angle) * radius,
                    center.y + 0.12,
                    center.z + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 地面十字线（砸落类预警：告诉玩家「正中在这」）。 */
    public static void cross(Level level, Vec3 center, double radius) {
        line(level, center.add(-radius, 0, 0), center.add(radius, 0, 0), 0.5, 0.0);
        line(level, center.add(0, 0, -radius), center.add(0, 0, radius), 0.5, 0.0);
    }

    /**
     * 地面细长预警（贯穿类）：沿 from→to 画两条平行线，宽度 2 × halfWidth。
     *
     * @param halfWidth 半宽（格）；0 时画单线
     */
    public static void line(Level level, Vec3 from, Vec3 to, double step, double halfWidth) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.1) {
            return;
        }
        Vec3 dir = delta.normalize();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x).normalize();
        int points = (int) Math.min(MAX_LINE_POINTS, Math.round(length / Math.max(0.25, step)));
        for (int i = 0; i <= points; i++) {
            Vec3 at = from.add(delta.scale(i / (double) points));
            if (halfWidth > 0.01) {
                server.sendParticles(SPARK, at.x + side.x * halfWidth, at.y + 0.12, at.z + side.z * halfWidth,
                        1, 0.0, 0.0, 0.0, 0.0);
                server.sendParticles(SPARK, at.x - side.x * halfWidth, at.y + 0.12, at.z - side.z * halfWidth,
                        1, 0.0, 0.0, 0.0, 0.0);
            } else {
                server.sendParticles(SPARK, at.x, at.y + 0.12, at.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * 地面方形边框（区块边界 / 棋盘格）。
     *
     * <p>大范围法术的「范围」必须一眼看出来，圆环不够用 —— 区块和棋盘都是方的。
     *
     * @param half 半边长（格）
     */
    public static void square(Level level, Vec3 center, double half, DustParticleOptions dust) {
        if (!(level instanceof ServerLevel server) || half <= 0.05) {
            return;
        }
        int points = Math.min(MAX_LINE_POINTS, Math.max(12, (int) Math.round(half * 6)));
        for (int i = 0; i <= points; i++) {
            double t = -half + 2.0 * half * i / points;
            server.sendParticles(dust, center.x + t, center.y + 0.12, center.z - half, 1, 0, 0, 0, 0);
            server.sendParticles(dust, center.x + t, center.y + 0.12, center.z + half, 1, 0, 0, 0, 0);
            server.sendParticles(dust, center.x - half, center.y + 0.12, center.z + t, 1, 0, 0, 0, 0);
            server.sendParticles(dust, center.x + half, center.y + 0.12, center.z + t, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ 演出

    /**
     * 标准落雷演出：竖直雷闪 + 地面冲击环 + 雷声 + 电火花 + 屏震。
     *
     * @param scale 雷闪缩放（1.0 = 元数据默认）
     */
    public static void boltVisual(Level level, Vec3 at, float scale) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // 雷柱：epitaph_lightning_bolt（defaultScale 0.025 / frameW 231，高 262）
        // 旧的 tohka_verdict_smite 是 0.02 / 180；同尺寸换算 1.6 × (0.02×180)/(0.025×231) ≈ 1.0
        PixelfxFx.play(server, at.add(0.0, 0.6, 0.0), PixelfxSlugs.EPITAPH_LIGHTNING_BOLT, scale, 0.0f);
        PixelfxFx.play(server, at.add(0.0, 0.05, 0.0), PixelfxSlugs.TOHKA_BELL_WAVE, scale * 0.8f, 0.0f);
        server.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.7, at.z, 18, 0.35, 0.8, 0.35, 0.08);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.HOSTILE, 0.7f, 1.0f);
        server.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
                SoundSource.HOSTILE, 0.9f, 1.1f);
        shake(server, at, 48.0, 0.35f, 6);
    }

    /** 屏震（服务端 → 客户端）。 */    public static void shake(Level level, Vec3 origin, double radius, float strength, int ticks) {
        ModNetwork.sendScreenShake(level, origin, radius, strength, ticks);
    }

    /** 半径内的玩家（排除旁观 / 创造）。 */
    public static List<Player> players(Level level, Vec3 center, double radius) {
        List<Player> out = new ArrayList<>();
        AABB box = new AABB(center, center).inflate(radius);
        for (Player player : level.getEntitiesOfClass(Player.class, box)) {
            if (isValidPlayerTarget(player) && player.position().distanceTo(center) <= radius) {
                out.add(player);
            }
        }
        return out;
    }

    /** 是否为可被法术命中的玩家（存活、非旁观、非创造）。 */
    public static boolean isValidPlayerTarget(Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    /** 构造统一的法术伤害源。 */
    public static DamageSource magicSource(Level level, @Nullable LivingEntity source) {
        return source == null
                ? level.damageSources().magic()
                : level.damageSources().indirectMagic(source, source);
    }

    /**
     * 将坐标贴合到最近的实心方块顶面，避免高低差地形上特效浮空或埋进地里。
     * 找不到地面时返回原坐标。
     */
    public static Vec3 snapToGround(Level level, Vec3 pos, int maxUp, int maxDown) {
        int bx = (int) Math.floor(pos.x);
        int bz = (int) Math.floor(pos.z);
        int startY = (int) Math.floor(pos.y) + maxUp;
        int endY = (int) Math.floor(pos.y) - maxDown;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = startY; y >= endY; y--) {
            cursor.set(bx, y, bz);
            if (!level.hasChunkAt(cursor)) {
                return pos;
            }
            BlockState state = level.getBlockState(cursor);
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (!shape.isEmpty()) {
                double topY = y + shape.max(net.minecraft.core.Direction.Axis.Y);
                return new Vec3(pos.x, topY, pos.z);
            }
        }
        return pos;
    }

    // ------------------------------------------------------------------ 结算

    /**
     * 以 {@code center} 为球心的魔法伤害。
     *
     * @param once     同一次施法的「已命中」集合（同目标只吃一次）
     * @param slowTicks 附加缓慢时长（0 = 不附加）
     * @return 实际命中人数
     */
    public static int magic(Level level, Vec3 center, double radius, float amount,
                            @Nullable LivingEntity source, Set<UUID> once,
                            int slowTicks, int slowAmp, double knockback) {
        AABB box = new AABB(center, center).inflate(radius + 1.0);
        return apply(level, box, center, radius, amount, source, once, slowTicks, slowAmp, knockback);
    }

    /** 盒状魔法伤害（贯穿类）。 */
    public static int magicBox(Level level, AABB box, float amount, @Nullable LivingEntity source, Set<UUID> once,
                               int slowTicks, int slowAmp, double knockback) {
        return apply(level, box, box.getCenter(), Double.MAX_VALUE, amount, source, once,
                slowTicks, slowAmp, knockback);
    }

    /**
     * 水平线段（胶囊体）魔法伤害，解决斜向发射时 {@link AABB} 判定框膨胀的问题。
     */
    public static int magicSegment(Level level, Vec3 start, Vec3 end, double halfWidth,
                                   double minY, double maxY, float amount,
                                   @Nullable LivingEntity source, Set<UUID> once,
                                   int slowTicks, int slowAmp, double knockback) {
        double minX = Math.min(start.x, end.x) - halfWidth - 1.0;
        double maxX = Math.max(start.x, end.x) + halfWidth + 1.0;
        double minZ = Math.min(start.z, end.z) - halfWidth - 1.0;
        double maxZ = Math.max(start.z, end.z) + halfWidth + 1.0;
        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        double segDx = end.x - start.x;
        double segDz = end.z - start.z;
        double segLenSq = segDx * segDx + segDz * segDz;
        DamageSource damageSource = magicSource(level, source);
        int hits = 0;

        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!isValidTarget(target, source)) {
                continue;
            }
            double maxDist = halfWidth + target.getBbWidth() * 0.5;
            double distSq = distSqPointToSegmentXZ(target.getX(), target.getZ(),
                    start.x, start.z, segDx, segDz, segLenSq);
            if (distSq > maxDist * maxDist) {
                continue;
            }
            if (!once.add(target.getUUID())) {
                continue;
            }
            applyHitEffects(target, start, damageSource, amount, slowTicks, slowAmp, knockback);
            hits++;
        }
        return hits;
    }

    /** 周期性球形玩家魔法伤害（带单目标冷却去重）。 */
    public static int magicPeriodicPlayers(Level level, Vec3 center, double radius, float amount,
                                           @Nullable LivingEntity source, Map<UUID, Integer> lastHit,
                                           int age, int cooldownTicks) {
        AABB box = new AABB(center, center).inflate(radius + 1.0);
        DamageSource damageSource = magicSource(level, source);
        int hits = 0;
        for (Player player : level.getEntitiesOfClass(Player.class, box)) {
            if (!isValidPlayerTarget(player) || player.position().distanceTo(center) > radius) {
                continue;
            }
            Integer last = lastHit.get(player.getUUID());
            if (last != null && age - last < cooldownTicks) {
                continue;
            }
            lastHit.put(player.getUUID(), age);
            player.hurt(damageSource, amount);
            hits++;
        }
        return hits;
    }

    private static double distSqPointToSegmentXZ(double px, double pz,
                                                 double ax, double az,
                                                 double abx, double abz, double abLenSq) {
        if (abLenSq < 1.0E-6) {
            double dx = px - ax;
            double dz = pz - az;
            return dx * dx + dz * dz;
        }
        double t = ((px - ax) * abx + (pz - az) * abz) / abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + abx * t;
        double cz = az + abz * t;
        double dx = px - cx;
        double dz = pz - cz;
        return dx * dx + dz * dz;
    }

    private static boolean isValidTarget(LivingEntity target, @Nullable LivingEntity source) {
        if (!target.isAlive() || target.isSpectator() || target == source) {
            return false;
        }
        return !(target instanceof Player player && player.isCreative());
    }

    private static void applyHitEffects(LivingEntity target, Vec3 center, DamageSource damageSource,
                                        float amount, int slowTicks, int slowAmp, double knockback) {
        target.hurt(damageSource, amount);
        if (slowTicks > 0) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, slowTicks, slowAmp,
                    false, false, true));
        }
        if (knockback > 0.01) {
            Vec3 away = target.position().subtract(center);
            if (away.lengthSqr() < 1.0E-4) {
                away = new Vec3(0.0, 0.0, 1.0);
            }
            Vec3 dir = away.normalize();
            target.knockback(knockback, -dir.x, -dir.z);
        }
    }

    private static int apply(Level level, AABB box, Vec3 center, double radius, float amount,
                             @Nullable LivingEntity source, Set<UUID> once,
                             int slowTicks, int slowAmp, double knockback) {
        int hits = 0;
        DamageSource damageSource = magicSource(level, source);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!isValidTarget(target, source)) {
                continue;
            }
            if (radius != Double.MAX_VALUE && target.position().distanceTo(center) > radius + 1.0) {
                continue;
            }
            if (!once.add(target.getUUID())) {
                continue;
            }
            applyHitEffects(target, center, damageSource, amount, slowTicks, slowAmp, knockback);
            hits++;
        }
        return hits;
    }
}
