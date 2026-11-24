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
import com.example.galboss.GalBoss;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
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
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class LeviathanEntity extends AbstractPurgatoryStakeEntity {

    private static final DustParticleOptions ENVY_CYAN =
            new DustParticleOptions(new Vector3f(0.18F, 0.72F, 0.78F), 1.0F);
    private static final DustParticleOptions ENVY_PURPLE =
            new DustParticleOptions(new Vector3f(0.45F, 0.18F, 0.68F), 1.0F);

    /** 追踪水弹实例（服务端 tick 推进）。 */
    private static final class WaterBolt {
        Vec3 pos;
        Vec3 vel;
        int age;
        final LivingEntity target;

        WaterBolt(Vec3 pos, Vec3 vel, LivingEntity target) {
            this.pos = pos;
            this.vel = vel;
            this.target = target;
        }
    }

    private final List<WaterBolt> waterBolts = new ArrayList<>();
    /** 窃视之眼减伤比例（0.0 ~ 0.45）。 */
    private float envyDamageReduction = 0.0F;

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
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        this.tickWaterBolts(level);

        if (this.tickCount % 20 != 0) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (!(target instanceof Player player)) {
            this.envyDamageReduction = 0.0F;
            return;
        }
        int armor = player.getArmorValue();
        int enchantLevels = EnchantmentHelper.getEnchantments(player.getMainHandItem())
                .values().stream().mapToInt(Integer::intValue).sum();
        int envyScore = armor + enchantLevels * 2;
        // 目标护甲 + 武器附魔越高，嫉妒获得的减伤（最高 45%）、吸收（上限 16）与增伤（最高力量 III）越高
        this.envyDamageReduction = Math.min(0.45F, envyScore * 0.012F);
        this.setAbsorptionAmount(Math.min(16.0F, envyScore / 3.0F));
        int strength = Math.min(3, envyScore / 8);
        if (strength > 0) {
            StakeFx.effect(this, MobEffects.DAMAGE_BOOST, 40, strength - 1);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof LeviathanEntity leviathan
                && !leviathan.level().isClientSide
                && leviathan.envyDamageReduction > 0.01F) {
            event.setAmount(event.getAmount() * (1.0F - leviathan.envyDamageReduction));
        }
    }

    private void tickWaterBolts(ServerLevel level) {
        if (this.waterBolts.isEmpty()) {
            return;
        }
        Iterator<WaterBolt> it = this.waterBolts.iterator();
        while (it.hasNext()) {
            WaterBolt bolt = it.next();
            bolt.age++;
            if (bolt.age > 50 || !bolt.target.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 aim = bolt.target.getEyePosition().subtract(bolt.pos);
            if (aim.lengthSqr() > 1.0E-4D) {
                Vec3 desired = aim.normalize().scale(0.58D);
                bolt.vel = bolt.vel.lerp(desired, 0.22D);
            }
            bolt.pos = bolt.pos.add(bolt.vel);
            level.sendParticles(ENVY_CYAN, bolt.pos.x, bolt.pos.y, bolt.pos.z, 2, 0.08, 0.08, 0.08, 0.0);
            level.sendParticles(ParticleTypes.DRIPPING_WATER, bolt.pos.x, bolt.pos.y, bolt.pos.z, 1, 0.05, 0.05, 0.05, 0.0);

            if (bolt.pos.distanceToSqr(bolt.target.getEyePosition()) <= 1.25D * 1.25D
                    || bolt.pos.distanceToSqr(bolt.target.position().add(0, 1.0, 0)) <= 1.25D * 1.25D) {
                StakeFx.hurt(level, this, bolt.target, 5.0F);
                StakeFx.sound(level, bolt.pos, SoundEvents.PLAYER_SPLASH, 0.9F, 1.3F);
                level.sendParticles(ENVY_PURPLE, bolt.pos.x, bolt.pos.y, bolt.pos.z, 8, 0.25, 0.25, 0.25, 0.02);
                it.remove();
            }
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
            // 远程玩家 → 发射 3 发追踪水弹
            Vec3 from = this.getEyePosition();
            Vec3 baseDir = target.getEyePosition().subtract(from).normalize();
            Vec3 side = new Vec3(-baseDir.z, 0.0D, baseDir.x).normalize();
            for (int i = -1; i <= 1; i++) {
                Vec3 vel = baseDir.add(side.scale(i * 0.35D)).add(0.0D, 0.18D, 0.0D).normalize().scale(0.55D);
                this.waterBolts.add(new WaterBolt(from, vel, target));
            }
            StakeFx.sound(level, from, SoundEvents.BUCKET_EMPTY, 1.2F, 1.2F);
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
