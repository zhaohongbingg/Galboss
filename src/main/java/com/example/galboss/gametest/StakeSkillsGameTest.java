package com.example.galboss.gametest;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import com.example.galboss.entity.AsmodeusEntity;
import com.example.galboss.entity.BeelzebubEntity;
import com.example.galboss.entity.BelphegorEntity;
import com.example.galboss.entity.LeviathanEntity;
import com.example.galboss.entity.LuciferEntity;
import com.example.galboss.entity.MammonEntity;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.entity.SatanEntity;
import com.example.galboss.stake.StakeFx;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 七大罪（炼狱七桩）技能与修复项的 Forge GameTest 自动验证。
 */
@GameTestHolder(GalBoss.MOD_ID)
@PrefixGameTestTemplate(false)
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StakeSkillsGameTest {

    private static final String TEMPLATE = "stake_arena";

    private StakeSkillsGameTest() {
    }

    @SubscribeEvent
    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(StakeSkillsGameTest.class);
    }

    /** 1. 路西法：免疫外部负面效果，但附近有其他七桩时「不屑围攻」自挂虚弱生效。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void luciferSelfWeaknessAndImmunity(GameTestHelper helper) {
        LuciferEntity lucifer = helper.spawn(ModEntities.LUCIFER.get(), new BlockPos(7, 1, 7));
        lucifer.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
        if (lucifer.hasEffect(MobEffects.POISON)) {
            helper.fail("Lucifer should be immune to external negative effects (POISON)");
            return;
        }
        if (lucifer.hasEffect(MobEffects.WEAKNESS)) {
            helper.fail("Lucifer should not have WEAKNESS when alone");
            return;
        }

        helper.spawn(ModEntities.SATAN.get(), new BlockPos(9, 1, 7));
        helper.runAfterDelay(70, () -> {
            if (!lucifer.hasEffect(MobEffects.WEAKNESS)) {
                helper.fail("Lucifer did not receive self-WEAKNESS when another stake is nearby");
                return;
            }
            helper.succeed();
        });
    }

    /** 2. 无友伤：StakeFx 排除同类七桩，且别西卜实战喷吐/酸液池不会伤到撒旦。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void noFriendlyFireBetweenStakes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BeelzebubEntity beelzebub = helper.spawn(ModEntities.BEELZEBUB.get(), new BlockPos(7, 1, 7));
        SatanEntity satan = helper.spawn(ModEntities.SATAN.get(), new BlockPos(9, 1, 7));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(10, 1, 7));

        Vec3 center = beelzebub.position();
        Vec3 dir = satan.position().subtract(center).normalize();
        if (StakeFx.inRadius(level, beelzebub, center, 8.0D).contains(satan)
                || StakeFx.inBeam(level, beelzebub, center, dir, 8.0D, 2.0D).contains(satan)
                || StakeFx.inCone(level, beelzebub, center, dir, 8.0D, 0.5D).contains(satan)) {
            helper.fail("StakeFx must exclude fellow AbstractPurgatoryStakeEntity");
            return;
        }

        beelzebub.setTarget(zombie);
        float satanHpBefore = satan.getHealth();
        helper.runAfterDelay(45, () -> {
            // 仅判定来自别西卜的友伤或中毒（排除其他环境因素）
            boolean hurtByBeelzebub = satan.getHealth() < satanHpBefore
                    && satan.getLastDamageSource() != null
                    && satan.getLastDamageSource().getEntity() == beelzebub;
            if (hurtByBeelzebub || satan.hasEffect(MobEffects.POISON)) {
                helper.fail("Satan took friendly fire from Beelzebub: hpBefore=" + satanHpBefore
                        + " hpAfter=" + satan.getHealth()
                        + " poison=" + satan.hasEffect(MobEffects.POISON));
                return;
            }
            helper.succeed();
        });
    }

    /** 3. 撒旦：50% 血不触发爆裂震地；低于 40% 血实际触发震地（霸体+伤害），且不破坏脆弱方块。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void satanQuakeThresholdAndNoBlockBreak(GameTestHelper helper) {
        BlockPos glassPos = new BlockPos(7, 1, 8);
        helper.setBlock(glassPos, Blocks.GLASS);

        SatanEntity satan = helper.spawn(ModEntities.SATAN.get(), new BlockPos(7, 1, 7));
        Player target = helper.makeMockPlayer();
        Vec3 pPos = helper.absoluteVec(new Vec3(9.0D, 1.0D, 7.0D));
        target.setPos(pPos.x, pPos.y, pPos.z);
        helper.getLevel().addFreshEntity(target);
        satan.setTarget(target);

        satan.setHealth(70.0F);
        helper.runAfterDelay(100, () -> {
            MobEffectInstance boost = satan.getEffect(MobEffects.DAMAGE_BOOST);
            if (boost != null && boost.getAmplifier() >= 1 && boost.getDuration() > 60) {
                helper.fail("Satan triggered quake at 50% HP (expected < 40% HP only)");
                return;
            }

            satan.setHealth(40.0F);
            helper.runAfterDelay(80, () -> {
                MobEffectInstance quakeBoost = satan.getEffect(MobEffects.DAMAGE_BOOST);
                if (quakeBoost == null || quakeBoost.getAmplifier() < 1) {
                    helper.fail("Satan did not trigger quake below 40% HP");
                    return;
                }
                if (helper.getBlockState(glassPos).getBlock() != Blocks.GLASS) {
                    helper.fail("Satan quake destroyed fragile GLASS block (should be disabled)");
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** 4. 玛门：挥金如土四圈金弹幕能够命中附近目标并造成多段伤害。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void mammonBarrageHitsTarget(GameTestHelper helper) {
        MammonEntity mammon = helper.spawn(ModEntities.MAMMON.get(), new BlockPos(7, 1, 7));
        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(9, 1, 7));
        target.setHealth(target.getMaxHealth());
        float hpBefore = target.getHealth();
        mammon.setTarget(target);

        helper.runAfterDelay(80, () -> {
            float lost = hpBefore - target.getHealth();
            if (lost <= 0.0F) {
                helper.fail("Mammon skills dealt no damage to nearby target");
                return;
            }
            helper.succeed();
        });
    }

    /** 5. 贝尔菲戈尔：5 格外 60% 减伤、5 格内失效，且召唤梦游傀儡后分摊 50% 伤害。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void belphegorSlothGuardAndPuppetTransfer(GameTestHelper helper) {
        BelphegorEntity belphegor = helper.spawn(ModEntities.BELPHEGOR.get(), new BlockPos(7, 1, 7));
        float hpStart = belphegor.getHealth();
        belphegor.hurt(helper.getLevel().damageSources().generic(), 20.0F);
        float farDamage = hpStart - belphegor.getHealth();

        Player player = helper.makeMockPlayer();
        Vec3 nearPos = helper.absoluteVec(new Vec3(9.0D, 1.0D, 7.0D));
        player.setPos(nearPos.x, nearPos.y, nearPos.z);
        helper.getLevel().addFreshEntity(player);

        float hpMid = belphegor.getHealth();
        belphegor.invulnerableTime = 0;
        belphegor.hurt(helper.getLevel().damageSources().generic(), 20.0F);
        float nearDamage = hpMid - belphegor.getHealth();

        if (farDamage >= nearDamage * 0.6F) {
            helper.fail("Belphegor far damage (" + farDamage + ") should be ~40% of near damage (" + nearDamage + ")");
            return;
        }

        // 让贝尔菲戈尔以真实实体为目标并等待召唤梦游傀儡
        Zombie puppetTarget = helper.spawn(EntityType.ZOMBIE, new BlockPos(9, 1, 7));
        puppetTarget.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        belphegor.setTarget(puppetTarget);
        helper.runAfterDelay(45, () -> {
            AABB box = new AABB(helper.absoluteVec(new Vec3(-5, 0, -5)), helper.absoluteVec(new Vec3(20, 8, 20)));
            List<Husk> puppets = helper.getLevel().getEntitiesOfClass(Husk.class, box);
            if (puppets.isEmpty()) {
                helper.fail("Belphegor did not summon sleepwalk puppets");
                return;
            }
            Husk puppet = puppets.get(0);
            float puppetHpBefore = puppet.getHealth();
            belphegor.invulnerableTime = 0;
            belphegor.hurt(helper.getLevel().damageSources().generic(), 10.0F);
            if (puppet.getHealth() >= puppetHpBefore) {
                helper.fail("Puppet did not receive transferred damage from Belphegor");
                return;
            }
            helper.succeed();
        });
    }

    /** 6. 阿斯莫德：镜花水月最多 2 个分身，分身被杀本体回 20 血、凶手吃反伤与反胃。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void asmodeusClonesHealAndBacklash(GameTestHelper helper) {
        AsmodeusEntity asmodeus = helper.spawn(ModEntities.ASMODEUS.get(), new BlockPos(7, 2, 7));
        Zombie killer = helper.spawn(EntityType.ZOMBIE, new BlockPos(11, 2, 7));
        killer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        asmodeus.setTarget(killer);

        helper.runAfterDelay(25, () -> {
            AABB box = new AABB(helper.absoluteVec(new Vec3(-5, -5, -5)), helper.absoluteVec(new Vec3(20, 10, 20)));
            List<AsmodeusEntity> all = helper.getLevel().getEntitiesOfClass(AsmodeusEntity.class, box);
            List<AsmodeusEntity> clones = all.stream().filter(e -> !e.countsAsStake() && e.isAlive()).toList();
            if (clones.isEmpty() || clones.size() > 2) {
                var dead = all.stream().filter(e -> !e.countsAsStake() && !e.isAlive()).toList();
                String cause = dead.isEmpty() ? "none" : String.valueOf(dead.get(0).getLastDamageSource());
                helper.fail("Expected 1..2 living Asmodeus clones, got " + clones.size()
                        + " (deadClones=" + dead.size() + ", cause=" + cause
                        + ", totalAsmodeus=" + all.size() + ")");
                return;
            }
            asmodeus.setHealth(100.0F);
            float killerHpBefore = killer.getHealth();
            clones.get(0).hurt(helper.getLevel().damageSources().mobAttack(killer), 10.0F);

            if (asmodeus.getHealth() <= 100.0F) {
                helper.fail("Asmodeus owner did not heal when clone was killed: after=" + asmodeus.getHealth());
                return;
            }
            if (killer.getHealth() >= killerHpBefore || !killer.hasEffect(MobEffects.CONFUSION)) {
                helper.fail("Clone killer did not receive mind backlash damage + CONFUSION");
                return;
            }
            helper.succeed();
        });
    }

    /** 7. 利维坦：镜像投射（远程持弓目标触发水弹追踪命中）。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void leviathanRangedWaterBolts(GameTestHelper helper) {
        LeviathanEntity leviathan = helper.spawn(ModEntities.LEVIATHAN.get(), new BlockPos(7, 1, 7));
        Player rangedTarget = helper.makeMockPlayer();
        rangedTarget.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        Vec3 pos = helper.absoluteVec(new Vec3(12.0D, 1.0D, 7.0D));
        rangedTarget.setPos(pos.x, pos.y, pos.z);
        helper.getLevel().addFreshEntity(rangedTarget);
        float hpBefore = rangedTarget.getHealth();
        leviathan.setTarget(rangedTarget);

        helper.runAfterDelay(70, () -> {
            if (rangedTarget.getHealth() >= hpBefore) {
                helper.fail("Leviathan ranged WaterBolts did not hit bow-holding target");
                return;
            }
            helper.succeed();
        });
    }
}
