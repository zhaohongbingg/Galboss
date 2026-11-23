package com.example.galboss.entity;

import com.example.galboss.network.ModNetwork;
import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 炼狱七桩 · 利维坦（嫉妒）—— 镜像复刻、专克神装。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>窃视之眼</b>：目标护甲越高，自己越硬（伤害吸收）也越疼（力量）</td></tr>
 *   <tr><td>主动 1</td><td><b>镜像投射</b>：玩家拿近战 → 贴身三连斩；拿远程 → 三发水弹</td></tr>
 *   <tr><td>主动 2</td><td><b>幸福剥夺</b>：剥掉玩家所有正面效果，逐个换成等时长的对应负面</td></tr>
 * </table>
 */
public class LeviathanEntity extends AbstractPurgatoryStakeEntity {

    /** 正面 → 负面 的对应表（简单实现，没覆盖的正面效果直接移除、不换）。 */
    private static final java.util.Map<MobEffect, MobEffect> INVERSE = new java.util.HashMap<>();

    static {
        INVERSE.put(MobEffects.MOVEMENT_SPEED, MobEffects.MOVEMENT_SLOWDOWN);
        INVERSE.put(MobEffects.DIG_SPEED, MobEffects.DIG_SLOWDOWN);
        INVERSE.put(MobEffects.DAMAGE_BOOST, MobEffects.WEAKNESS);
        INVERSE.put(MobEffects.JUMP, MobEffects.MOVEMENT_SLOWDOWN);
        INVERSE.put(MobEffects.DAMAGE_RESISTANCE, MobEffects.WEAKNESS);
        INVERSE.put(MobEffects.REGENERATION, MobEffects.POISON);
        INVERSE.put(MobEffects.ABSORPTION, MobEffects.UNLUCK);
    }

    public LeviathanEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "leviathan";
    }

    @Override
    protected int skillOneCooldown() {
        return 80;
    }

    @Override
    protected int skillTwoCooldown() {
        return 260;
    }

    // ---------------------------------------------------------------- 被动：窃视之眼

    @Override
    protected void stakePassiveTick() {
        if (this.tickCount % 20 != 0 || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (!(target instanceof Player player)) {
            return;
        }
        int armor = player.getArmorValue();
        // 每 4 点护甲：+1 点伤害吸收（上限 12），并叠一层力量（上限 III）
        this.setAbsorptionAmount(Math.min(12.0F, armor / 4.0F));
        int strength = Math.min(2, armor / 8);
        if (strength > 0) {
            StakeFx.effect(this, MobEffects.DAMAGE_BOOST, 40, strength - 1);
        }
    }

    // ---------------------------------------------------------------- 主动 1：镜像投射

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        boolean melee = isMelee(target);
        if (melee) {
            // 近战玩家 → 贴身高频三连斩
            Vec3 behind = target.position().add(target.getLookAngle().scale(-1.2D));
            this.teleportTo(behind.x, target.getY(), behind.z);
            for (int i = 0; i < 3; i++) {
                StakeFx.hurt(level, this, target, 4.0F);
            }
            StakeFx.line(level, this.getEyePosition(), target.getEyePosition(), ParticleTypes.SWEEP_ATTACK, 1);
            StakeFx.sound(level, target.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.3F);
        } else {
            // 远程玩家 → 三发追踪水弹（用粒子 + 直接结算表现，简化掉真实弹射物）
            for (int i = 0; i < 3; i++) {
                StakeFx.line(level, this.getEyePosition(), target.getEyePosition(), ParticleTypes.DRIPPING_WATER, 2);
                StakeFx.hurt(level, this, target, 5.0F);
            }
            StakeFx.sound(level, target.position(), SoundEvents.BUCKET_EMPTY, 1.2F, 1.0F);
        }
        StakeFx.burst(level, target.position().add(0, 1.0, 0), 10);
    }

    private static boolean isMelee(LivingEntity target) {
        var stack = target.getMainHandItem();
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || target.getAttributeValue(Attributes.ATTACK_DAMAGE) > 6.0D;
    }

    // ---------------------------------------------------------------- 主动 2：幸福剥夺

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        Vec3 center = this.position();
        StakeFx.ring(level, center.add(0, 0.2, 0), 5.0, 40, ParticleTypes.WITCH);
        StakeFx.sound(level, center, SoundEvents.EVOKER_CAST_SPELL, 1.2F, 0.9F);

        List<Player> victims = StakeFx.playersInRadius(level, center, 10.0D);
        for (Player player : victims) {
            List<MobEffectInstance> positives = new ArrayList<>();
            for (MobEffectInstance inst : player.getActiveEffects()) {
                if (inst.getEffect().isBeneficial()) {
                    positives.add(inst);
                }
            }
            for (MobEffectInstance inst : positives) {
                MobEffect negative = INVERSE.get(inst.getEffect());
                player.removeEffect(inst.getEffect());
                if (negative != null) {
                    player.addEffect(new MobEffectInstance(negative, Math.max(60, inst.getDuration()),
                            Math.min(2, inst.getAmplifier()), false, true, true));
                }
            }
        }
        ModNetwork.sendRedTruthLiteral(level, center, 40.0D, "「你身上那些好东西，我收下了。」", 50);
    }
}
