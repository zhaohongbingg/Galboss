package com.example.galboss.entity;

import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * 炼狱七桩 · 撒旦（暴怒）—— 高速突进、残血狂暴。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>怒火攻心</b>：按已损失生命分 6 档，每档攻速/移速 +15%，攻击附带灵魂火</td></tr>
 *   <tr><td>主动 1</td><td><b>红莲冲锋</b>：三段折线突进留下火焰轨迹，命中后立刻重置本技能冷却</td></tr>
 *   <tr><td>主动 2</td><td><b>爆裂震地</b>：生命低于 40% 才触发，砸地冲击波 + 击飞</td></tr>
 * </table>
 */
public class SatanEntity extends AbstractPurgatoryStakeEntity {

    /** 狂暴档位总数。 */
    private static final int RAGE_STEPS = 6;

    private static final float CHARGE_DAMAGE = 8.0F;
    private static final float QUAKE_DAMAGE = 14.0F;
    private static final double QUAKE_RADIUS = 6.0D;

    /** 被点过灵魂火的目标 → 到期 gameTime。水能短暂灭火，但会立刻被重新点燃。 */
    private final java.util.Map<UUID, Long> soulBurning = new java.util.HashMap<>();

    public SatanEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "satan";
    }

    @Override
    protected int skillOneCooldown() {
        return 100;
    }

    @Override
    protected int skillTwoCooldown() {
        return 200;
    }

    /** 当前狂暴档位（0–6）。 */
    private int rageStacks() {
        float lost = 1.0F - this.getHealth() / this.getMaxHealth();
        return Math.min(RAGE_STEPS, (int) (lost * RAGE_STEPS));
    }

    // ---------------------------------------------------------------- 被动：怒火攻心

    @Override
    protected void stakePassiveTick() {
        if (this.tickCount % 20 != 0) {
            return;
        }
        // 灵魂火复燃：被水浇灭的目标在到期前会被重新点燃
        if (!soulBurning.isEmpty() && this.level() instanceof ServerLevel soulLevel) {
            soulBurning.entrySet().removeIf(entry -> {
                LivingEntity living = soulLevel.getPlayerByUUID(entry.getKey());
                if (living == null || !living.isAlive()) {
                    return true;
                }
                if (soulLevel.getGameTime() > entry.getValue()) {
                    return true;
                }
                if (!living.isOnFire()) {
                    living.setSecondsOnFire(4);
                    soulLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                            living.getX(), living.getY() + 1.0, living.getZ(), 4, 0.2, 0.3, 0.2, 0.01);
                }
                return false;
            });
        }

        int stacks = rageStacks();
        if (stacks <= 0) {
            return;
        }
        // 每档 +15%：速度用 amp = stacks-1（每级 +20%，取近似），力量同理
        StakeFx.effect(this, MobEffects.MOVEMENT_SPEED, 40, Math.min(2, stacks - 1));
        if (stacks >= 3) {
            StakeFx.effect(this, MobEffects.DAMAGE_BOOST, 40, Math.min(2, (stacks - 3) / 1));
        }
    }

    @Override
    protected void onStakeHit(Entity target) {
        if (target instanceof LivingEntity living && this.level() instanceof ServerLevel level) {
            // 灵魂火：普通火会被水扑灭，所以这里另记一份"灵魂火"状态，灭火后立刻复燃（真正的扑不灭）
            living.setSecondsOnFire(4);
            soulBurning.put(living.getUUID(), level.getGameTime() + 100L);
            StakeFx.burst(level, living.position().add(0, 1.0, 0), 6);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    living.getX(), living.getY() + 1.0, living.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
        }
    }

    // ---------------------------------------------------------------- 主动 1：红莲冲锋

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        boolean landed = false;
        for (int step = 0; step < 3; step++) {
            Vec3 dir = target.position().subtract(this.position());
            if (dir.lengthSqr() < 0.04D) {
                break;
            }
            Vec3 dash = dir.normalize().scale(1.6D);
            this.setDeltaMovement(dash.x, this.getDeltaMovement().y, dash.z);
            this.hasImpulse = true;
            this.hurtMarked = true;

            // 火焰轨迹
            for (int i = 0; i < 6; i++) {
                Vec3 p = this.position().lerp(this.position().add(dash), i / 6.0D);
                level.sendParticles(ParticleTypes.FLAME, p.x, p.y + 0.6, p.z, 2, 0.2, 0.2, 0.2, 0.01);
            }
            for (LivingEntity hit : StakeFx.inRadius(level, this, this.position(), 2.2D)) {
                if (hit == target || !(hit instanceof net.minecraft.world.entity.player.Player)) {
                    continue;
                }
                StakeFx.hurt(level, this, hit, CHARGE_DAMAGE);
                landed = true;
            }
            if (StakeFx.inRadius(level, this, target.position(), 2.2D).contains(target)) {
                StakeFx.hurt(level, this, target, CHARGE_DAMAGE);
                landed = true;
            }
        }
        StakeFx.sound(level, this.position(), SoundEvents.BLAZE_SHOOT, 1.4F, 0.8F);
        if (landed) {
            // 命中 → 立刻可用下一次冲锋
            this.resetSkillOne();
        }
    }

    // ---------------------------------------------------------------- 主动 2：爆裂震地

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        if (this.rageStacks() < 3) {
            // 生命还高：这一轮不该白吃 200 tick 冷却，20 tick 后再看
            this.resetSkillTwo();
            return;
        }
        Vec3 center = this.position();
        StakeFx.ring(level, center.add(0, 0.2, 0), QUAKE_RADIUS, 56, ParticleTypes.FLAME);
        StakeFx.sound(level, center, SoundEvents.GENERIC_EXPLODE, 1.6F, 0.7F);
        com.example.galboss.network.ModNetwork.sendScreenShake(level, center, QUAKE_RADIUS + 4.0D, 0.5F, 10);

        for (LivingEntity hit : StakeFx.inRadius(level, this, center, QUAKE_RADIUS)) {
            StakeFx.hurt(level, this, hit, QUAKE_DAMAGE);
            StakeFx.knockAway(hit, center, 0.8D, 0.9D);
        }
        com.example.galboss.network.ModNetwork.sendRedTruthLiteral(level, center, 40.0D,
                "「——烧起来。全都烧起来。」", 50);
    }
}
