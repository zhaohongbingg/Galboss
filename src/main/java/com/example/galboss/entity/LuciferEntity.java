package com.example.galboss.entity;

import com.example.galboss.network.ModNetwork;
import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 炼狱七桩 · 路西法（傲慢）—— 霸体单挑、规则压制。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>不可一世</b>：免疫负面药水与击退；周围有其它七桩时伤害打折（不屑围攻）</td></tr>
 *   <tr><td>主动 1</td><td><b>神罚射线</b>：一条高穿透直线光束，命中击飞（无视普通盾牌：伤害走 mobAttack，不走弹射物）</td></tr>
 *   <tr><td>主动 2</td><td><b>重力跪伏</b>：大范围光环压人下坠 + 缓慢 IV + 挖掘疲劳</td></tr>
 * </table>
 */
public class LuciferEntity extends AbstractPurgatoryStakeEntity {

    private static final double BEAM_LENGTH = 16.0D;
    private static final double BEAM_RADIUS = 1.1D;
    private static final float BEAM_DAMAGE = 12.0F;
    private static final int BEAM_CHARGE_TICKS = 16;
    private static final double KNEEL_RADIUS = 7.0D;
    private static final DustParticleOptions GOLD_WHITE =
            new DustParticleOptions(new Vector3f(1.0F, 0.94F, 0.72F), 1.15F);

    /** 跪伏压制到期时间（0 = 无）。持续期间每 4 tick 重新压一次，玩家跳不掉。 */
    private long kneelUntil = 0L;

    /** 神罚射线蓄力倒计时与锁定方向。 */
    private int beamCharge = 0;
    private Vec3 beamDir = null;

    /** 允许「不屑围攻」自挂虚弱绕过自身负面免疫。 */
    private boolean applyingSelfWeakness = false;

    public LuciferEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "lucifer";
    }

    // ---------------------------------------------------------------- 被动：不可一世

    /**
     * 免疫负面药水。
     *
     * <p>不能重写 {@code addEffect} —— 1.20.1 里它是 {@code final}；真正的拦截点是
     * {@code canBeAffected}（{@code addEffect} 内部第一件事就调它）。
     */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        if (this.applyingSelfWeakness && effect.getEffect() == MobEffects.WEAKNESS) {
            return super.canBeAffected(effect);
        }
        return effect.getEffect().isBeneficial() && super.canBeAffected(effect);
    }

    /** 免疫击退。 */
    @Override
    public void knockback(double strength, double xRatio, double zRatio) {
        // 傲慢不接受被推动
    }

    @Override
    protected void stakePassiveTick() {
        ServerLevel level = serverLevel();
        // 神罚射线蓄力瞄准线与发射
        if (this.beamCharge > 0 && this.beamDir != null) {
            this.beamCharge--;
            if (this.beamCharge % 2 == 0) {
                Vec3 from = this.getEyePosition();
                Vec3 end = from.add(this.beamDir.scale(BEAM_LENGTH));
                StakeFx.line(level, from, end, GOLD_WHITE, 1);
            }
            if (this.beamCharge == 0) {
                this.fireBeam(level, this.beamDir);
                this.beamDir = null;
            }
        }

        // 跪伏持续压制：每 4 tick 把范围内的玩家重新按回地面（旧实现只写一次 y 速度，一跳就破）
        if (kneelUntil > 0L) {
            if (level.getGameTime() > kneelUntil) {
                kneelUntil = 0L;
            } else if (this.tickCount % 4 == 0) {
                for (LivingEntity hit : StakeFx.inRadius(level, this, this.position(), KNEEL_RADIUS)) {
                    if (hit instanceof Player) {
                        StakeFx.pinDown(hit);
                    }
                }
            }
        }

        if (this.tickCount % 20 != 0) {
            return;
        }
        // 周围有其它七桩 → 「不屑围攻」，自己的攻击打折（用虚弱表现）
        boolean allies = !level.getEntitiesOfClass(AbstractPurgatoryStakeEntity.class,
                AABB.ofSize(this.position(), 20.0D, 20.0D, 20.0D),
                other -> other != this && other.isAlive() && other.countsAsStake()).isEmpty();
        if (allies) {
            this.applyingSelfWeakness = true;
            try {
                StakeFx.effect(this, MobEffects.WEAKNESS, 40, 0);
            } finally {
                this.applyingSelfWeakness = false;
            }
        }
    }

    // ---------------------------------------------------------------- 主动 1：神罚射线

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        Vec3 from = this.getEyePosition();
        Vec3 delta = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(from);
        if (delta.lengthSqr() < 1.0E-4D) {
            delta = this.getLookAngle();
        }
        this.beamDir = delta.normalize();
        this.beamCharge = BEAM_CHARGE_TICKS;
        StakeFx.sound(level, from, SoundEvents.BEACON_ACTIVATE, 1.0F, 1.6F);
        StakeFx.line(level, from, from.add(this.beamDir.scale(BEAM_LENGTH)), GOLD_WHITE, 1);
    }

    private void fireBeam(ServerLevel level, Vec3 dir) {
        Vec3 from = this.getEyePosition();
        Vec3 end = from.add(dir.scale(BEAM_LENGTH));

        ModNetwork.sendSniperBeam(level, from, end, 8.0D, 8);
        StakeFx.line(level, from, end, ParticleTypes.END_ROD, 1);
        StakeFx.sound(level, from, SoundEvents.BEACON_POWER_SELECT, 1.2F, 1.6F);

        for (LivingEntity hit : StakeFx.inBeam(level, this, from, dir, BEAM_LENGTH, BEAM_RADIUS)) {
            // 无视普通盾牌格挡：先破盾再以法术射线结算
            if (hit instanceof Player player && player.isBlocking()) {
                player.disableShield(true);
            }
            hit.hurt(level.damageSources().indirectMagic(this, this), BEAM_DAMAGE);
            StakeFx.knockAway(hit, from, 0.6D, 1.0D);
            StakeFx.burst(level, hit.position().add(0, 1.0, 0), 8);
        }
    }

    // ---------------------------------------------------------------- 主动 2：重力跪伏

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        Vec3 center = this.position();
        StakeFx.ring(level, center.add(0, 0.1, 0), KNEEL_RADIUS, 48, net.minecraft.core.particles.ParticleTypes.END_ROD);
        StakeFx.sound(level, center, SoundEvents.ANVIL_LAND, 1.6F, 0.7F);
        ModNetwork.sendScreenShake(level, center, KNEEL_RADIUS + 4.0D, 0.35F, 8);

        this.kneelUntil = level.getGameTime() + 160L;
        for (LivingEntity hit : StakeFx.inRadius(level, this, center, KNEEL_RADIUS)) {
            if (!(hit instanceof net.minecraft.world.entity.player.Player)) {
                continue;
            }
            StakeFx.pinDown(hit);
            StakeFx.effect(hit, MobEffects.MOVEMENT_SLOWDOWN, 160, 3); // 缓慢 IV
            StakeFx.effect(hit, MobEffects.DIG_SLOWDOWN, 160, 2);      // 挖掘疲劳 III
        }
        ModNetwork.sendRedTruthLiteral(level, center, 40.0D, "「跪伏吧。这里没有你站起来的资格。」", 50);
    }

    private ServerLevel serverLevel() {
        return (ServerLevel) this.level();
    }
}
