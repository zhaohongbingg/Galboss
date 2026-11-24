package com.example.galboss.stake;

import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 七桩技能共用的「积木」：范围查询、伤害、药水、位移、粒子、音效。
 *
 * <p>七位魔王的技能表（每人 1 被动 + 2 主动）都只用这里的原语拼出来，
 * 这样每个角色的实现都只有几十行、彼此不会各写一套半径/角度约定。
 */
public final class StakeFx {

    private StakeFx() {
    }

    // ---------------------------------------------------------------- 范围查询

    private static boolean isValidTarget(LivingEntity owner, LivingEntity candidate) {
        if (candidate == owner || !candidate.isAlive()) {
            return false;
        }
        return !(owner instanceof AbstractPurgatoryStakeEntity && candidate instanceof AbstractPurgatoryStakeEntity);
    }

    /** 半径内的活体（不含自己与同类七桩）。 */
    public static List<LivingEntity> inRadius(ServerLevel level, LivingEntity owner, Vec3 center, double radius) {
        return level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
                e -> isValidTarget(owner, e));
    }

    /** 半径内的玩家。 */
    public static List<Player> playersInRadius(ServerLevel level, Vec3 center, double radius) {
        return level.getEntitiesOfClass(Player.class, AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
                LivingEntity::isAlive);
    }

    /** 从 from 出发、方向 dir、长度 length 的「射线」周围 radius 内的活体。 */
    public static List<LivingEntity> inBeam(ServerLevel level, LivingEntity owner, Vec3 from, Vec3 dir,
                                            double length, double radius) {
        Vec3 end = from.add(dir.scale(length));
        AABB box = new AABB(from, end).inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box,
                e -> isValidTarget(owner, e) && distanceToSegment(e.position(), from, end) <= radius + e.getBbWidth() * 0.5D);
    }

    /** 前方扇形（半角 cos 判定）。 */
    public static List<LivingEntity> inCone(ServerLevel level, LivingEntity owner, Vec3 from, Vec3 dir,
                                            double length, double cosHalfAngle) {
        Vec3 end = from.add(dir.scale(length));
        AABB box = new AABB(from, end).inflate(length * 0.5D);
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> {
            if (!isValidTarget(owner, e)) {
                return false;
            }
            Vec3 to = e.position().subtract(from);
            double dist = to.length();
            return dist <= length && to.normalize().dot(dir) >= cosHalfAngle;
        });
    }

    /** 点到线段距离。 */
    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len = ab.length();
        if (len < 1.0E-4D) {
            return p.distanceTo(a);
        }
        double t = Math.max(0.0D, Math.min(1.0D, p.subtract(a).dot(ab) / (len * len)));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    // ---------------------------------------------------------------- 伤害 / 状态

    /** 造成伤害（默认清无敌帧，保证高频技能能落地）。 */
    public static void hurt(ServerLevel level, LivingEntity owner, LivingEntity target, float amount) {
        target.invulnerableTime = 0;
        target.hurt(level.damageSources().mobAttack(owner), amount);
        StakeDebug.log("hurt by={} target={} amt={} hp={}",
                StakeDebug.label(owner), target.getName().getString(), amount, target.getHealth());

    }

    public static void effect(LivingEntity target, MobEffect effect, int ticks, int amplifier) {
        if (target.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true, true))) {
            StakeDebug.log("fx target={} effect={} ticks={} amp={}",
                    target.getName().getString(),
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect), ticks, amplifier);
        }
    }


    // ---------------------------------------------------------------- 位移

    /** 把目标朝 to 拉（strength 是每 tick 的初速度）。 */
    public static void pull(LivingEntity target, Vec3 to, double strength) {
        Vec3 dir = to.subtract(target.position());
        if (dir.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 v = dir.normalize().scale(strength);
        target.setDeltaMovement(target.getDeltaMovement().scale(0.4D).add(v.x, 0.0D, v.z));
        target.hasImpulse = true;
        target.hurtMarked = true;
    }

    /** 从 from 往外推，并向上抬（击飞）。 */
    public static void knockAway(LivingEntity target, Vec3 from, double strength, double up) {
        Vec3 dir = target.position().subtract(from);
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 v = dir.normalize().scale(strength);
        target.setDeltaMovement(v.x, up, v.z);
        target.hasImpulse = true;
        target.hurtMarked = true;
    }

    /** 砸向地面：把纵向速度压下去（配合缓慢/挖掘疲劳 = 「跪伏」）。 */
    public static void pinDown(LivingEntity target) {
        Vec3 v = target.getDeltaMovement();
        target.setDeltaMovement(v.x, -0.9D, v.z);
        target.hasImpulse = true;
        target.hurtMarked = true;
    }

    // ---------------------------------------------------------------- 表现

    /** 两点之间的粒子线。 */
    public static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, int perBlock) {
        double len = from.distanceTo(to);
        int steps = Math.max(2, (int) (len * perBlock));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.lerp(to, i / (double) steps);
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 水平圆环。 */
    public static void ring(ServerLevel level, Vec3 center, double radius, int points, ParticleOptions particle) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2.0D * i / points;
            level.sendParticles(particle,
                    center.x + Math.cos(a) * radius, center.y, center.z + Math.sin(a) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    public static void butterfly(ServerLevel level, Vec3 at, int count, double spread) {
        level.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(), at.x, at.y, at.z, count, spread, spread, spread, 0.02D);
    }

    public static void burst(ServerLevel level, Vec3 at, int count) {
        level.sendParticles(ModParticles.BURST_STAR.get(), at.x, at.y, at.z, count, 0.4D, 0.4D, 0.4D, 0.08D);
        level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, count, 0.3D, 0.3D, 0.3D, 0.1D);
    }

    public static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, pitch);
    }
}
