package com.example.galboss.entity;

import com.example.galboss.fx.ButterflyPathParams;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.ritual.SacrificeLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「黄金蝶」追踪弹幕实体。
 *
 * <p>弹头本身维持领航蝶视觉核心，外围按 {@link ButterflyPathParams} 螺旋包络生成带惯性的蝶群与星屑尾迹。
 */
public class ButterflyMissileEntity extends Projectile {

    private static final float DAMAGE = 6.0f;
    private static final double TURN = 0.26;
    private static final int MAX_LIFE = 70;
    private static final double HIT_INFLATE = 0.55;

    private ButterflyPathParams pathParams = ButterflyPathParams.DEFAULT;
    private double cruiseSpeed = ButterflyPathParams.NOMINAL_SPEED;
    private int lifeTicks;

    public ButterflyMissileEntity(EntityType<? extends ButterflyMissileEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 起飞并支持附加初始扩散向量（用于齐射时先扇形散开再弧线合拢追踪）。
     */
    public void launch(LivingEntity owner, @Nullable LivingEntity target, double speedFactor, Vec3 initialSpread) {
        this.setOwner(owner);
        this.pathParams = ButterflyPathParams.load();
        Vec3 dir = target == null
                ? owner.getLookAngle()
                : target.getEyePosition().subtract(this.position()).normalize();
        this.cruiseSpeed = this.pathParams.speed() * Mth.clamp(speedFactor, 0.5, 1.6);
        Vec3 initialDir = dir.add(initialSpread).normalize();
        this.setDeltaMovement(initialDir.scale(this.cruiseSpeed));
    }

    public void launch(LivingEntity owner, @Nullable LivingEntity target, double speedFactor) {
        launch(owner, target, speedFactor, Vec3.ZERO);
    }

    public void launch(LivingEntity owner, @Nullable LivingEntity target) {
        launch(owner, target, 1.0, Vec3.ZERO);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (++this.lifeTicks > MAX_LIFE) {
            this.dissipate();
            return;
        }

        this.steer();
        Vec3 movement = this.getDeltaMovement();
        Vec3 from = this.position();
        this.setPos(from.x + movement.x, from.y + movement.y, from.z + movement.z);
        this.spawnTrail();

        if (this.level().clip(new ClipContext(
                from, this.position(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) {
            this.impact(this.position());
            return;
        }

        LivingEntity victim = this.findVictim();
        if (victim != null) {
            this.impact(victim.getEyePosition().subtract(0.0, 0.25, 0.0));
            this.hurtVictim(victim);
        }
    }

    private void steer() {
        LivingEntity target = this.resolveTarget();
        if (target == null) {
            return;
        }
        Vec3 desired = target.getEyePosition().subtract(this.position());
        if (desired.lengthSqr() < 1.0E-4) {
            return;
        }
        double speed = this.getDeltaMovement().length();
        if (speed < 1.0E-3) {
            speed = this.cruiseSpeed;
        }
        // 起飞前 8 tick 转向稍缓，让扇形齐射有明显的弧线张开感，随后平滑收束追踪
        double turnWeight = this.lifeTicks <= 8 ? TURN * 0.65 : TURN;
        Vec3 next = this.getDeltaMovement()
                .add(desired.normalize().scale(turnWeight))
                .normalize()
                .scale(speed);
        this.setDeltaMovement(next);
    }

    @Nullable
    private LivingEntity resolveTarget() {
        if (!(this.getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
            return null;
        }
        if (owner instanceof Mob mob) {
            LivingEntity current = mob.getTarget();
            if (current != null && current.isAlive()) {
                return current;
            }
        }
        List<Player> players = this.level().getEntitiesOfClass(Player.class,
                owner.getBoundingBox().inflate(40.0),
                player -> player.isAlive() && !player.isSpectator() && !player.isCreative());
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : players) {
            double dist = player.distanceToSqr(owner);
            if (dist < best) {
                best = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    @Nullable
    private LivingEntity findVictim() {
        AABB box = this.getBoundingBox().inflate(HIT_INFLATE);
        Entity owner = this.getOwner();
        List<LivingEntity> hits = this.level().getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive()
                        && !entity.isSpectator()
                        && entity != owner
                        && !entity.getTags().contains(SacrificeLedger.TAG_SACRIFICE));
        for (LivingEntity hit : hits) {
            if (hit instanceof BlackGoatServantEntity goat && goat.getSummonerUuid() != null
                    && owner != null
                    && goat.getSummonerUuid().equals(owner.getUUID())) {
                continue;
            }
            return hit;
        }
        return null;
    }

    private void hurtVictim(LivingEntity victim) {
        Entity owner = this.getOwner();
        var source = owner == null
                ? this.damageSources().magic()
                : this.damageSources().indirectMagic(this, owner);
        float before = victim.getHealth();
        victim.hurt(source, DAMAGE);
        com.example.galboss.GalBoss.LOGGER.info("[Butterfly] hit {} -> {}/{} (was {}) owner={}",
                victim.getName().getString(), victim.getHealth(), victim.getMaxHealth(), before,
                owner == null ? "null" : owner.getName().getString());
        if (victim instanceof Player player && !player.isCreative()) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false));
        }
    }

    private void impact(Vec3 at) {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ModParticles.BURST_STAR.get(),
                    at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0.0);
            server.sendParticles(ModParticles.BURST_RING.get(),
                    at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
            server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                    at.x, at.y + 0.1, at.z, 4, 0.0, 0.0, 0.0, 0.0);
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    at.x, at.y, at.z, 8, 0.25, 0.25, 0.25, 0.02);
            this.level().playSound(null, at.x, at.y, at.z,
                    SoundEvents.AMETHYST_BLOCK_CHIME,
                    SoundSource.HOSTILE, 0.9f, 1.6f);
        }
        this.discard();
    }

    private void dissipate() {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    this.getX(), this.getY(), this.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
        }
        this.discard();
    }

    private void spawnTrail() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 vel = this.getDeltaMovement();
        // 弹头核心领航蝶：赋予微弱前向惯性（count=0 时 dx/dy/dz 为精确速度矢量），确保弹道主体清晰可读
        if (this.lifeTicks % 2 == 1) {
            server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                    this.getX(), this.getY(), this.getZ(),
                    0, vel.x * 0.22, vel.y * 0.22, vel.z * 0.22, 1.0);
        }

        int interval = Math.max(1, this.pathParams.spawnIntervalTicks());
        if (this.lifeTicks % interval != 0) {
            return;
        }

        // 外围螺旋伴飞蝶：沿螺旋偏移点生成，并带微弱切向与前向拖曳速度
        Vec3 off = helixOffset();
        Vec3 driftVel = vel.scale(0.18).add(off.scale(0.03));
        server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                this.getX() + off.x, this.getY() + off.y, this.getZ() + off.z,
                0, driftVel.x, driftVel.y, driftVel.z, 1.0);

        if (this.lifeTicks % 4 == 0) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    this.getX() + off.x * 1.35, this.getY() + off.y * 1.35, this.getZ() + off.z * 1.35,
                    1, 0.06, 0.06, 0.06, 0.008);
        }
    }

    private Vec3 helixOffset() {
        if (!this.pathParams.enabled()) {
            return Vec3.ZERO;
        }
        Vec3 dir = this.getDeltaMovement();
        if (dir.lengthSqr() < 1.0E-6) {
            return Vec3.ZERO;
        }
        double speed = dir.length();
        dir = dir.normalize();
        Vec3 side = dir.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0E-4) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();

        double phase = this.pathParams.phaseAngle(speed, this.lifeTicks);
        double radius = this.pathParams.radius();
        return side.scale(Math.cos(phase) * radius)
                .add(up.scale(Math.sin(phase) * radius * this.pathParams.verticalSquash()));
    }
}
