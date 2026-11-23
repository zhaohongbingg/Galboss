package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 魔女领域：以施法点为中心张开一个持续收缩的审判圈，驱赶玩家向圆心聚拢后引爆。
 */
public class WitchDomainSpell extends Spell {

    private static final double RADIUS_START = 8.0;
    private static final double RADIUS_END = 1.5;
    private static final int WINDUP = 20;
    private static final int SHRINK = 100;
    private static final int TICK_DAMAGE_INTERVAL = 10;
    private static final float OUTSIDE_DAMAGE = 2.5f;
    private static final float FINAL_DAMAGE = 10.0f;

    private static final DustParticleOptions RING = new DustParticleOptions(new Vector3f(1.0f, 0.82f, 0.28f), 1.15f);
    private static final DustParticleOptions CORE_SAFE = new DustParticleOptions(new Vector3f(1.0f, 0.94f, 0.65f), 0.95f);

    private final Vec3 center;
    private final Map<UUID, Integer> lastHit = new HashMap<>();
    private boolean finished;

    public WitchDomainSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        this.center = origin;
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new WitchDomainSpell(level, origin, caster));
    }

    private double currentRadius() {
        if (this.age <= WINDUP) {
            return RADIUS_START;
        }
        double progress = Math.min(1.0, (this.age - WINDUP) / (double) SHRINK);
        return RADIUS_START + (RADIUS_END - RADIUS_START) * progress;
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        double radius = this.currentRadius();

        // 外围收缩结界 + 中心最终安全核心环
        if (this.age % 2 == 0) {
            SpellFx.circle(server, this.center, radius, RING);
        }
        if (this.age % 4 == 0 && radius > RADIUS_END + 0.4) {
            SpellFx.circle(server, this.center, RADIUS_END, CORE_SAFE);
        }

        // 地面魔法阵随收缩半径同步缩放刷新
        if (this.age == 1 || (this.age % 24 == 0 && this.age < WINDUP + SHRINK - 10)) {
            float circleScale = (float) Math.max(0.65, 3.0 * (radius / RADIUS_START));
            PixelfxFx.play(server, this.center.add(0.0, 0.08, 0.0),
                    PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, circleScale, this.age * 1.5f);
        }

        if (this.age == 1) {
            server.playSound(null, this.center.x, this.center.y, this.center.z,
                    SoundEvents.BEACON_ACTIVATE,
                    SoundSource.HOSTILE, 1.1f, 0.7f);
        }

        // 沿收缩边界生成向圆心牵引的黄金蝶流
        if (this.age % 6 == 0 && !this.finished) {
            double baseAngle = this.age * 0.14;
            for (int i = 0; i < 6; i++) {
                double a = baseAngle + (Math.PI * 2.0 * i) / 6.0;
                double px = this.center.x + Math.cos(a) * radius;
                double pz = this.center.z + Math.sin(a) * radius;
                server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                        px, this.center.y + 0.45, pz,
                        0, -Math.cos(a) * 0.08, 0.015, -Math.sin(a) * 0.08, 1.0);
            }
        }

        if (this.age > WINDUP && this.age % TICK_DAMAGE_INTERVAL == 0 && !this.finished) {
            this.hurtOutside(server, radius);
        }

        if (!this.finished && this.age >= WINDUP + SHRINK) {
            this.finished = true;
            this.finalBurst(server);
        }
    }

    private void hurtOutside(ServerLevel server, double radius) {
        AABB box = new AABB(this.center, this.center).inflate(RADIUS_START + 2.0);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (player.position().distanceTo(this.center) <= radius) {
                continue;
            }
            Integer last = this.lastHit.get(player.getUUID());
            if (last != null && this.age - last < TICK_DAMAGE_INTERVAL - 1) {
                continue;
            }
            this.lastHit.put(player.getUUID(), this.age);
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), OUTSIDE_DAMAGE);
            server.sendParticles(ParticleTypes.WITCH,
                    player.getX(), player.getY() + 0.3, player.getZ(), 8, 0.35, 0.2, 0.35, 0.02);
        }
    }

    private void finalBurst(ServerLevel server) {
        PixelfxFx.play(server, this.center.add(0.0, 0.6, 0.0),
                PixelfxSlugs.EPITAPH_AEGIS, 0.55f, 0.0f);
        PixelfxFx.play(server, this.center.add(0.0, 0.05, 0.0),
                PixelfxSlugs.TOHKA_BELL_WAVE, 1.8f, 0.0f);

        server.sendParticles(ModParticles.BURST_STAR.get(),
                this.center.x, this.center.y + 0.8, this.center.z, 3, 0.25, 0.25, 0.25, 0.0);
        server.sendParticles(ModParticles.BURST_RING.get(),
                this.center.x, this.center.y + 0.5, this.center.z, 1, 0.0, 0.0, 0.0, 0.0);
        server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                this.center.x, this.center.y + 0.4, this.center.z, 12, 0.4, 0.3, 0.4, 0.0);

        server.playSound(null, this.center.x, this.center.y, this.center.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 1.2f, 0.75f);
        server.playSound(null, this.center.x, this.center.y, this.center.z,
                SoundEvents.AMETHYST_BLOCK_RESONATE,
                SoundSource.HOSTILE, 1.4f, 1.1f);
        SpellFx.shake(server, this.center, 36.0, 0.65f, 10);

        AABB box = new AABB(this.center, this.center).inflate(RADIUS_END + 1.5);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (player.position().distanceTo(this.center) > RADIUS_END + 1.0) {
                continue;
            }
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), FINAL_DAMAGE);
        }
    }

    @Override
    public int durationTicks() {
        return WINDUP + SHRINK + 12;
    }

    @Override
    public String id() {
        return "witch_domain";
    }
}
