package com.example.galboss.entity;

import com.example.galboss.effect.ModEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * ③ オナブタ 郁子 —— 狂战 / 输出。
 *
 * HP 最高、机制最简单、输出最暴力，而且越打越疯：
 *  - 被动「战斗狂」：每损失 10% 生命叠 1 层 Rage（上限 5），层数越高越强，满层还会变脆
 *  - 「一番队突击」：蓄力 0.8 秒后冲刺，命中 20 伤害 + 极强击退 + 流血 5 秒
 *  - 「乱战」：5 秒狂乱，攻速 +50% 但伤害 -25%，期间随机换目标
 *  - 「战斗狂欢」：Rage +2、力量 II 与速度 II 各 8 秒，并震荡周围 8 格玩家
 *  - P2「一番队・全力」：Rage 直接拉满、攻击 +35%、攻速 +30%，但护甲 -5
 */
public class OnabutaIkukoEntity extends AbstractFSBossEntity {

    private static final String ID = "onabuta_ikuko";

    // 被动：战斗狂
    private static final int RAGE_MAX = 5;
    private static final double RAGE_HEALTH_STEP = 0.10;

    // 一番队突击
    private static final double ASSAULT_CD = 15.0;
    private static final int ASSAULT_CHARGE_TICKS = 16;   // 0.8 秒
    private static final int ASSAULT_DASH_TICKS = 18;     // 位移上限，够覆盖到目标的距离
    private static final float ASSAULT_DAMAGE = 20.0f;
    private static final double ASSAULT_SPEED = 1.45;
    private static final int BLEEDING_DURATION = 5 * 20;

    // 乱战
    private static final double BRAWL_CD = 25.0;
    private static final int BRAWL_DURATION = 5 * 20;

    // 战斗狂欢
    private static final double CARNIVAL_CD = 40.0;
    private static final double CARNIVAL_RADIUS = 8.0;
    private static final float CARNIVAL_DAMAGE = 8.0f;

    // P2
    private static final double PHASE2_HEALTH = 0.30;
    private static final double PHASE2_ARMOR_PENALTY = -5.0;

    // 吸血：血量掉到 1/3 以下后，她自己的攻击会按伤害比例给她回血。
    // 和 P2 的 30% 阈值是两个独立的坎：1/3(33.3%) 先触发吸血，再掉到 30% 进 P2。
    private static final double LIFESTEAL_HEALTH = 1.0 / 3.0;
    private static final double LIFESTEAL_RATIO = 0.30;

    private static final UUID PHASE2_ARMOR_UUID = UUID.fromString("c2000000-0000-4000-8000-000000000001");

    private int rageLevel = 0;
    private int assaultCd = 0;
    private int brawlCd = 0;
    private int carnivalCd = 0;
    private int brawlTicks = 0;
    private boolean phase2 = false;
    private boolean lifestealAnnounced = false;

    public OnabutaIkukoEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // 基础血量；实际按参战人数 ×1 / 1.5 / 2 / 2.5（见 AbstractFSBossEntity#applyHealthScaling）
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 600.0)
                .add(Attributes.ATTACK_DAMAGE, 15.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ARMOR, 12.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    public FSRole getRole() {
        return FSRole.IKUKO;
    }

    @Override
    protected String getEntityId() {
        return ID;
    }

    @Override
    protected BossEvent.BossBarColor getBossBarColor() {
        return BossEvent.BossBarColor.YELLOW;
    }

    @Override
    protected double getMeleeReach() {
        return 3.0;
    }

    // ------------------------------------------------------------------ 数值钩子

    @Override
    protected double getAttackMultiplier() {
        double m = switch (this.rageLevel) {
            case 0 -> 1.0;
            case 1, 2, 3 -> 1.10;
            case 4 -> 1.32;   // 1~3 层的 +10% 再叠上第 4 层的 +20%
            default -> 1.40;  // 第 5 层「狂暴」
        };
        if (phase2) {
            m *= 1.35;
        }
        if (brawlTicks > 0) {
            m *= 0.75;
        }
        return m;
    }

    @Override
    protected double getSpeedMultiplier() {
        double m = 1.0;
        if (this.rageLevel >= 3) {
            m *= 1.10;
        }
        if (this.rageLevel >= RAGE_MAX) {
            m *= 1.20;
        }
        return m;
    }

    @Override
    protected double getAttackSpeedMultiplier() {
        double m = 1.0;
        if (this.rageLevel >= 2) {
            m *= 1.10;
        }
        if (this.rageLevel >= RAGE_MAX) {
            m *= 1.25;
        }
        if (phase2) {
            m *= 1.30;
        }
        if (brawlTicks > 0) {
            m *= 1.50;
        }
        return m;
    }

    @Override
    protected double getDamageTakenMultiplier() {
        return this.rageLevel >= RAGE_MAX ? 1.15 : 1.0;
    }

    // -------------------------------------------------------------------- 吸血

    /** 吸血是否生效：血量已经落到 1/3 以下。 */
    private boolean isLifestealActive() {
        return this.getHealth() <= this.getMaxHealth() * (float) LIFESTEAL_HEALTH;
    }

    /**
     * 按这次攻击的伤害给她回血。
     *
     * 基准取她自己的攻击力，而不是「目标实际掉了多少血」——
     * 后者会被目标护甲削掉一大截，打重甲玩家时回血几乎归零，手感很怪。
     */
    private void applyLifesteal(float baseDamage) {
        if (baseDamage <= 0.0f || !isLifestealActive() || this.getHealth() >= this.getMaxHealth()) {
            return;
        }
        this.heal(baseDamage * (float) LIFESTEAL_RATIO);
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART,
                    this.getX(), this.getY() + 1.7, this.getZ(), 3, 0.35, 0.3, 0.35, 0.0);
        }
    }

    /** 第一次跌破 1/3 时播一次提示，之后不再重复。 */
    private void announceLifesteal() {
        if (lifestealAnnounced || !battleActive || !isLifestealActive()) {
            return;
        }
        lifestealAnnounced = true;
        playAnim("rage");
        broadcastNearby(getDialogueKey() + "lifesteal", "§4");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6f, 1.1f);
    }

    /** 普通近战也吃吸血。 */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            applyLifesteal((float) this.getAttributeValue(Attributes.ATTACK_DAMAGE));
        }
        return hit;
    }

    /** 在附近玩家中随机挑一个换目标。 */
    private void switchTargetRandomly() {
        List<Player> players = nearbyPlayers(PARTICIPANT_RADIUS);
        if (players.isEmpty()) {
            return;
        }
        Player picked = players.get(this.random.nextInt(players.size()));
        if (picked != this.getTarget()) {
            this.setTarget(picked);
        }
    }

    // ------------------------------------------------------------------ 主循环

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new AssaultGoal());
    }

    @Override
    protected void tickBoss() {
        if (this.level().isClientSide) {
            return;
        }
        if (assaultCd > 0) assaultCd--;
        if (brawlCd > 0) brawlCd--;
        if (carnivalCd > 0) carnivalCd--;
        if (brawlTicks > 0) {
            brawlTicks--;
            // 乱战整段都在随机换目标；每 20 tick 换一次而不是每 tick 都换，
            // 否则目标每 tick 抖动，她会原地打转不追人
            if (brawlTicks % 20 == 0) {
                switchTargetRandomly();
            }
        }

        updateRage();
        checkPhase2();
        announceLifesteal();

        if (brawlCd <= 0) {
            brawlCd = cd(BRAWL_CD);
            castBrawl();
        }
        if (carnivalCd <= 0) {
            carnivalCd = cd(CARNIVAL_CD);
            castCarnival();
        }
    }

    /** 每损失 10% 生命叠一层 Rage。 */
    private void updateRage() {
        float lostRatio = 1.0f - this.getHealth() / this.getMaxHealth();
        int level = (int) Math.floor(lostRatio / RAGE_HEALTH_STEP);
        level = Math.max(0, Math.min(RAGE_MAX, level));
        if (phase2) {
            level = RAGE_MAX;
        }
        if (level == rageLevel) {
            return;
        }
        boolean reachingMax = level >= RAGE_MAX && this.rageLevel < RAGE_MAX;
        this.rageLevel = level;
        if (level > 0) {
            broadcastNearby(getDialogueKey() + "rage", "§6");
        }
        if (reachingMax) {
            playAnim("rage");
            broadcastNearby(getDialogueKey() + "rage_max", "§4");
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6f, 1.2f);
        }
    }

    private void checkPhase2() {
        if (phase2 || this.getHealth() > this.getMaxHealth() * (float) PHASE2_HEALTH) {
            return;
        }
        phase2 = true;
        playAnim("phase");
        AttributeInstance armor = this.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(PHASE2_ARMOR_UUID);
            armor.addPermanentModifier(new AttributeModifier(
                    PHASE2_ARMOR_UUID, "galboss:phase2_armor", PHASE2_ARMOR_PENALTY,
                    AttributeModifier.Operation.ADDITION));
        }
        broadcastNearby(getDialogueKey() + "phase2", "§4");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2.0f, 0.8f);
    }

    // -------------------------------------------------------------------- 技能

    /** 乱战：攻速暴涨但伤害下降，并随机换目标。 */
    private void castBrawl() {
        brawlTicks = cdTicks(BRAWL_DURATION);
        playAnim("rage");
        switchTargetRandomly();
        broadcastNearby(getDialogueKey() + "brawl", "§e");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.4f, 0.9f);
    }

    /** 战斗狂欢：直接涨 Rage、上 buff，并震荡周围玩家。 */
    private void castCarnival() {
        this.rageLevel = Math.min(RAGE_MAX, this.rageLevel + 2);
        playAnim("cast");
        this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 8 * 20, 1, false, true, true));
        this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 8 * 20, 1, false, true, true));

        for (Player player : nearbyPlayers(CARNIVAL_RADIUS)) {
            player.hurt(this.damageSources().mobAttack(this), CARNIVAL_DAMAGE);
            Vec3 push = player.position().subtract(this.position()).normalize().scale(1.8);
            player.push(push.x, 0.4, push.z);
        }
        broadcastNearby(getDialogueKey() + "carnival", "§6");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.8f);
    }

    /** 一番队突击：蓄力后冲刺。 */
    private class AssaultGoal extends Goal {

        private int chargeTicks = -1;
        private int dashTicks;
        private Vec3 dashDir = Vec3.ZERO;
        private boolean dashing;

        private AssaultGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!battleActive || assaultCd > 0) {
                return false;
            }
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive()) {
                return false;
            }
            double distance = distanceTo(target);
            return distance > 3.0 && distance < 20.0;
        }

        @Override
        public boolean canContinueToUse() {
            return this.chargeTicks > 0 || this.dashing;
        }

        @Override
        public void start() {
            this.chargeTicks = ASSAULT_CHARGE_TICKS;
            this.dashing = false;
            getNavigation().stop();
            playAnim("charge");
            broadcastNearby(getDialogueKey() + "assault", "§6");
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null) {
                this.chargeTicks = 0;
                this.dashing = false;
                return;
            }

            if (!this.dashing) {
                getLookControl().setLookAt(target, 60.0f, 60.0f);
                getNavigation().stop();
                if (--this.chargeTicks > 0) {
                    return;
                }
                Vec3 direction = target.position().subtract(position());
                this.dashDir = new Vec3(direction.x, 0, direction.z).normalize();
                this.dashing = true;
                this.dashTicks = ASSAULT_DASH_TICKS;
                // 位移就是从这一 tick 开始的，冲刺动画也必须在这一 tick 才触发
                playAnim("dash");
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 1.4f, 1.0f);
                return;
            }

            if (--this.dashTicks <= 0) {
                this.chargeTicks = 0;
                this.dashing = false;
                return;
            }

            setDeltaMovement(this.dashDir.x * ASSAULT_SPEED, getDeltaMovement().y, this.dashDir.z * ASSAULT_SPEED);

            if (distanceTo(target) <= 2.2) {
                target.hurt(damageSources().mobAttack(OnabutaIkukoEntity.this), ASSAULT_DAMAGE);
                Vec3 push = this.dashDir.scale(3.2);
                target.push(push.x, 0.7, push.z);
                target.addEffect(new MobEffectInstance(ModEffects.BLEEDING.get(), BLEEDING_DURATION, 0, false, true, true));
                applyLifesteal(ASSAULT_DAMAGE);
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.6f, 0.7f);
                this.chargeTicks = 0;
                this.dashing = false;
            }
        }

        @Override
        public void stop() {
            this.chargeTicks = -1;
            this.dashing = false;
            assaultCd = cd(ASSAULT_CD);
        }
    }

    @Override
    public void respondToCrossfire(Player target) {
        super.respondToCrossfire(target);
        // 交叉火力里郁子负责贴身：把突击立刻转好
        assaultCd = 0;
    }

    @Override
    protected void onLastStandEntered() {
        this.rageLevel = RAGE_MAX;
    }

    @Override
    protected void castUltimate() {
        castCarnival();
    }
}
