package com.example.galboss.entity;

import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 炼狱七桩 · 别西卜（暴食）—— 吞噬续航、范围腐蚀。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>无底之胃</b>：每秒吃掉周围玩家的饱食度；目标饱食度低于一半时伤害 +30%</td></tr>
 *   <tr><td>主动 1</td><td><b>黑洞吞噬</b>：吸住周围实体，吞掉掉落物回血（每个 15 点，最多 3 个）</td></tr>
 *   <tr><td>主动 2</td><td><b>酸液喷吐</b>：前方扇形毒液，命中中毒 + 反胃，并留下腐蚀痕迹</td></tr>
 * </table>
 */
public class BeelzebubEntity extends AbstractPurgatoryStakeEntity {

    private static final int HUNGER_INTERVAL = 20;
    private static final double HUNGER_RADIUS = 10.0D;
    private static final float HUNGRY_DAMAGE_BONUS = 0.3F;

    private static final double VACUUM_RADIUS = 8.0D;
    private static final float HEAL_PER_ITEM = 15.0F;

    private static final double CONE_LENGTH = 7.0D;
    /** cos(60°)：半角 60° 的扇形。 */
    private static final double CONE_COS = 0.5D;

    /** 目标是否处于「饿着」状态（影响自己的输出）。 */
    private boolean hungryTarget = false;

    /** 酸液池：中心 + 到期时间（0 = 无池）。池内每 20 tick 结算一次伤害与中毒。 */
    private net.minecraft.world.phys.Vec3 poolCenter = null;
    private long poolUntil = 0L;

    public BeelzebubEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "beelzebub";
    }

    @Override
    protected int skillOneCooldown() {
        return 160;
    }

    @Override
    protected int skillTwoCooldown() {
        return 140;
    }

    // ---------------------------------------------------------------- 被动：无底之胃

    @Override
    protected void stakePassiveTick() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target instanceof Player player) {
            this.hungryTarget = player.getFoodData().getFoodLevel() < 10;
        }
        if (this.tickCount % HUNGER_INTERVAL != 0) {
            return;
        }
        for (Player player : StakeFx.playersInRadius(level, this.position(), HUNGER_RADIUS)) {
            int food = player.getFoodData().getFoodLevel();
            if (food > 0) {
                player.getFoodData().setFoodLevel(food - 1);
            }
            level.sendParticles(ParticleTypes.ITEM_SLIME,
                    player.getX(), player.getY() + 1.0, player.getZ(), 3, 0.3, 0.3, 0.3, 0.01);
        }
        if (this.hungryTarget) {
            StakeFx.effect(this, MobEffects.DAMAGE_BOOST, 40, 0);
        }

        // 酸液池：持续 8 秒的真实伤害区（旧实现只有粒子，池子不咬人）
        if (poolCenter != null) {
            if (level.getGameTime() > poolUntil) {
                poolCenter = null;
            } else {
                if (this.tickCount % 10 == 0) {
                    StakeFx.ring(level, poolCenter.add(0.0D, 0.1D, 0.0D), 3.0D, 14, ParticleTypes.SNEEZE);
                }
                if (this.tickCount % 20 == 0) {
                    for (LivingEntity inside : StakeFx.inRadius(level, this, poolCenter, 3.0D)) {
                        StakeFx.hurt(level, this, inside, 3.0F);
                        StakeFx.effect(inside, MobEffects.POISON, 60, 0);
                        corrodeArmor(inside, 2);
                    }
                }
            }
        }
    }

    /** 饿着的人吃 +30% 伤害：用攻击前的额外结算近似。 */
    @Override
    protected void onStakeHit(Entity target) {
        if (!this.hungryTarget || !(target instanceof LivingEntity living) || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        // +30% 按「本次攻击力的 30%」追加，不是按目标最大生命（旧实现写成了 3% 最大生命，名不副实）
        StakeFx.hurt(level, this, living, (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * HUNGRY_DAMAGE_BONUS);
    }

    // ---------------------------------------------------------------- 主动 1：黑洞吞噬

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        Vec3 center = this.position();
        StakeFx.ring(level, center.add(0, 0.5, 0), 3.0D, 24, ParticleTypes.PORTAL);
        StakeFx.sound(level, center, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.6F);

        for (LivingEntity around : StakeFx.inRadius(level, this, center, VACUUM_RADIUS)) {
            StakeFx.pull(around, center, 0.5D);
        }
        AABB box = AABB.ofSize(center, VACUUM_RADIUS * 2, VACUUM_RADIUS * 2, VACUUM_RADIUS * 2);
        // 吞噬掉落物、飞行弹幕与敌方召唤物（如恼鬼/黑山羊仆从）回血（最多 4 个）
        int eaten = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (eaten >= 4) {
                break;
            }
            item.discard();
            eaten++;
            this.heal(HEAL_PER_ITEM);
            StakeFx.butterfly(level, item.position(), 4, 0.3D);
        }
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, box)) {
            if (eaten >= 4) {
                break;
            }
            if (proj.getOwner() == this) {
                continue;
            }
            proj.discard();
            eaten++;
            this.heal(HEAL_PER_ITEM);
            StakeFx.butterfly(level, proj.position(), 4, 0.3D);
        }
        for (Vex vex : level.getEntitiesOfClass(Vex.class, box)) {
            if (eaten >= 4) {
                break;
            }
            vex.discard();
            eaten++;
            this.heal(HEAL_PER_ITEM * 1.5F);
            StakeFx.butterfly(level, vex.position(), 5, 0.4D);
        }
    }


    // ---------------------------------------------------------------- 主动 2：酸液喷吐

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        Vec3 from = this.getEyePosition();
        Vec3 dir = target.getEyePosition().subtract(from).normalize();
        StakeFx.sound(level, from, SoundEvents.LLAMA_SPIT, 1.4F, 0.7F);

        for (int i = 0; i < 14; i++) {
            Vec3 p = from.add(dir.scale(i * CONE_LENGTH / 14.0D));
            level.sendParticles(ParticleTypes.ITEM_SLIME, p.x, p.y, p.z, 2, 0.5, 0.4, 0.5, 0.02);
        }
        for (LivingEntity hit : StakeFx.inCone(level, this, from, dir, CONE_LENGTH, CONE_COS)) {
            StakeFx.hurt(level, this, hit, 8.0F);
            StakeFx.effect(hit, MobEffects.POISON, 160, 0);   // 中毒 8 秒
            StakeFx.effect(hit, MobEffects.CONFUSION, 160, 0);
            corrodeArmor(hit, 4);
        }
        // 酸液池本体（持续 8 秒的真实伤害区）
        this.poolCenter = target.position();
        this.poolUntil = level.getGameTime() + 160L;
        Vec3 pool = target.position();
        for (int i = 0; i < 24; i++) {
            double a = Math.random() * Math.PI * 2.0D;
            double r = Math.random() * 3.0D;
            level.sendParticles(ParticleTypes.SNEEZE,
                    pool.x + Math.cos(a) * r, pool.y + 0.1D, pool.z + Math.sin(a) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        com.example.galboss.network.ModNetwork.sendRedTruthLiteral(level, this.position(), 40.0D,
                "「饿着的人，连骨头都是甜的。」", 50);
    }

    /** 腐蚀护甲：加速损耗全身穿戴护甲的耐久度。 */
    private static void corrodeArmor(LivingEntity target, int damage) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = target.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                stack.hurtAndBreak(damage, target, entity -> entity.broadcastBreakEvent(slot));
            }
        }
    }
}
