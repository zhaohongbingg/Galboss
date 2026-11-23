package com.example.galboss.entity;

import com.example.galboss.network.ModNetwork;
import com.example.galboss.stake.StakeFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
    private static final double KNEEL_RADIUS = 7.0D;

    /** 跪伏压制到期时间（0 = 无）。持续期间每 4 tick 重新压一次，玩家跳不掉。 */
    private long kneelUntil = 0L;

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
        return effect.getEffect().isBeneficial() && super.canBeAffected(effect);
    }

    /** 免疫击退。 */
    @Override
    public void knockback(double strength, double xRatio, double zRatio) {
        // 傲慢不接受被推动
    }

    @Override
    protected void stakePassiveTick() {
        if (this.tickCount % 20 != 0) {
            return;
        }
        // 周围有其它七桩 → 「不屑围攻」，自己的攻击打折（用虚弱表现）
        boolean allies = false;
        for (LivingEntity around : StakeFx.inRadius(serverLevel(), this, this.position(), 10.0D)) {
            if (around instanceof AbstractPurgatoryStakeEntity) {
                allies = true;
                break;
            }
        }
        if (allies) {
            StakeFx.effect(this, MobEffects.WEAKNESS, 40, 0);
        }
        // 跪伏持续压制：每 4 tick 把范围内的玩家重新按回地面（旧实现只写一次 y 速度，一跳就破）
        if (kneelUntil > 0L) {
            if (serverLevel().getGameTime() > kneelUntil) {
                kneelUntil = 0L;
            } else if (this.tickCount % 4 == 0) {
                for (LivingEntity hit : StakeFx.inRadius(serverLevel(), this, this.position(), KNEEL_RADIUS)) {
                    if (hit instanceof net.minecraft.world.entity.player.Player) {
                        StakeFx.pinDown(hit);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 主动 1：神罚射线

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        Vec3 from = this.getEyePosition();
        Vec3 dir = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(from).normalize();
        Vec3 end = from.add(dir.scale(BEAM_LENGTH));

        ModNetwork.sendSniperBeam(level, from, end, 8.0D, 8);
        StakeFx.line(level, from, end, net.minecraft.core.particles.ParticleTypes.END_ROD, 1);
        StakeFx.sound(level, from, SoundEvents.BEACON_POWER_SELECT, 1.2F, 1.6F);

        for (LivingEntity hit : StakeFx.inBeam(level, this, from, dir, BEAM_LENGTH, BEAM_RADIUS)) {
            StakeFx.hurt(level, this, hit, BEAM_DAMAGE);
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
