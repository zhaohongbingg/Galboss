package com.example.galboss.entity;

import com.example.galboss.GalBoss;
import com.example.galboss.config.GalBossConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「FS Big Three」三名 boss 的公共骨架。
 *
 * 负责所有和具体角色无关的部分：
 *  - 战斗前的对话 / 潜行跳过，进入战斗后锁定参战人数
 *  - 按参战人数缩放最大生命（1 / 1.5 / 2 / 2.5 倍，对应 1~4 人）
 *  - Boss 血条
 *  - 三人之间的联动 Buff
 *  - 死亡顺序升级（第一人死亡 / 第二人死亡）与最后一人「最终执行」
 *  - 交战中因人数变化不可动态回调，人数在战斗开始时锁定
 *
 * 子类只负责自己的技能与阶段，通过覆写下面这几个钩子接入：
 *  {@link #tickBoss()}、{@link #getAttackMultiplier()}、{@link #getSpeedMultiplier()}、
 *  {@link #getDamageTakenMultiplier()}、{@link #getCooldownMultiplier()}、{@link #castUltimate()}。
 */
public abstract class AbstractFSBossEntity extends Monster implements GeoEntity {

    // ---------------------------------------------------------------- 角色标识
    public enum FSRole {
        /** 冷泉院 桐香 —— 控制 / 指挥 */
        TOHKA,
        /** 只須川 レイ —— 高机动 / 追击 */
        REI,
        /** オナブタ 郁子 —— 狂战 / 输出 */
        IKUKO
    }

    /** 只有同一场遭遇（这个半径内）的 boss 才会互相联动。 */
    protected static final double ALLY_RADIUS = 64.0;

    /** 判定「参战」的半径，与 FOLLOW_RANGE 保持一致。 */
    protected static final double PARTICIPANT_RADIUS = 48.0;

    protected static final int MAX_PARTICIPANTS = 4;

    /** 进入战斗前留给玩家的反应时间（3 秒）。 */
    private static final int BATTLE_START_DELAY = 60;

    // ------------------------------------------------------------ 属性 modifier
    private static final UUID HP_SCALE_UUID = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID ATTACK_LINK_UUID = UUID.fromString("b1000000-0000-4000-8000-000000000002");
    private static final UUID SPEED_LINK_UUID = UUID.fromString("b1000000-0000-4000-8000-000000000003");

    protected final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    protected ServerBossEvent bossEvent;

    /** 空手/默认状态，子类覆写以提供自己的冷却基准。 */
    protected boolean battleActive = false;
    private boolean dialogueStarted = false;
    private int battleStartDelay = 0;

    /**
     * 结构（教学楼）里自带的 boss 标记：只认「右键挑战」。
     *
     * <p>它的待机期是完全不能打的 —— 打了不掉血、也不会被激怒进入战斗，
     * 这样玩家必须主动发起挑战，而不是隔墙磨血把人磨死。由结构 NBT 里的
     * {@code FsChallengeOnUse} 打开，用刷怪蛋/命令放出来的 boss 不带这个标记，
     * 行为与以前一致（挨打就开战，方便调试）。
     */
    private boolean challengeOnUse = false;

    /** 待机 boss 被敲时的提示冷却，避免刷屏。 */
    private int challengeHintCooldown = 0;

    /** 挑战一人时，这个半径内的同伴一起进入战斗状态。 */
    private static final double CHALLENGE_LINK_RADIUS = 32.0;
    private static final int CHALLENGE_HINT_COOLDOWN = 40;

    /** 战斗开始时锁定的参战人数与生命倍率。 */
    private int participants = 1;
    private double healthMultiplier = 1.0;

    /** 这场遭遇里已经阵亡的同伴数量，用于死亡顺序升级。 */
    private int alliesLost = 0;

    private int refreshTimer = 0;

    /** 每 tick 都会刷新的联动状态，避免在属性钩子里反复做实体查询。 */
    private boolean allyTohkaAlive = false;
    private boolean allyReiAlive = false;
    private boolean allyIkukoAlive = false;
    private boolean lastStanding = false;
    private int ultimateTimer = 0;

    public AbstractFSBossEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setMaxUpStep(1.0f);
        // boss 不参与「离玩家太远就消失」的判定，否则打到一半飞出 128 格就会把整个遭遇抹掉
        this.setPersistenceRequired();
    }

    // ------------------------------------------------------------------ 子类契约

    /** 本 boss 在三人组里的身份。 */
    public abstract FSRole getRole();

    /** 注册名，例如 {@code reizein_tohka}；动画与语言键都由它推导。 */
    protected abstract String getEntityId();

    protected abstract BossEvent.BossBarColor getBossBarColor();

    /** 动画名前缀，例如 {@code animation.reizein_tohka.}。 */
    protected String getAnimPrefix() {
        return "animation." + getEntityId() + ".";
    }

    /** 语言文件前缀，例如 {@code dialogue.galboss.reizein_tohka.}。 */
    protected String getDialogueKey() {
        return "dialogue.galboss." + getEntityId() + ".";
    }

    private String getEntityLangKey() {
        return "entity.galboss." + getEntityId();
    }

    /** 每 tick 调用，子类在这里推进技能冷却与阶段。 */
    protected void tickBoss() {
    }

    /** 最终阶段（只剩自己一人）时每 20 秒释放一次的大招。 */
    protected void castUltimate() {
    }

    /** 覆盖配置的攻击倍率，子类把 P2 / Rage 之类叠进来。 */
    protected double getAttackMultiplier() {
        return 1.0;
    }

    protected double getSpeedMultiplier() {
        return 1.0;
    }

    protected double getDamageTakenMultiplier() {
        return 1.0;
    }

    /** 小于 1 表示技能冷却缩短。 */
    protected double getCooldownMultiplier() {
        return 1.0;
    }

    /** 近战出手频率倍率，「攻击速度 +20%」即 1.2。 */
    protected double getAttackSpeedMultiplier() {
        return 1.0;
    }

    /** 同伴阵亡时的回调，子类可以顺手清掉自己的召唤物或切阶段。 */
    protected void onAllyLost() {
    }

    // ------------------------------------------------------------------ 战斗流程

    @Override
    protected void registerGoals() {
        // 1~3 留给子类的技能，普通近战排在它们后面
        this.goalSelector.addGoal(4, new FsMeleeGoal());
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true, target -> this.battleActive));
    }

    /**
     * 自己实现的近战 goal。
     *
     * 原版 {@link MeleeAttackGoal} 的攻击间隔写死 20 tick，没法体现「攻击速度 +20%」，
     * 这里把冷却交给 {@link #getAttackSpeedMultiplier()} 控制。
     */
    protected class FsMeleeGoal extends Goal {

        private int attackCooldown = 0;

        protected FsMeleeGoal() {
            this.setFlags(java.util.EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = AbstractFSBossEntity.this.getTarget();
            return battleActive && target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void tick() {
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            LivingEntity target = AbstractFSBossEntity.this.getTarget();
            if (target == null) {
                return;
            }
            AbstractFSBossEntity.this.getLookControl().setLookAt(target, 30.0f, 30.0f);

            double reach = getMeleeReach();
            if (AbstractFSBossEntity.this.distanceToSqr(target) > reach * reach) {
                AbstractFSBossEntity.this.getNavigation().moveTo(target, 1.2);
                return;
            }
            AbstractFSBossEntity.this.getNavigation().stop();
            if (attackCooldown <= 0) {
                AbstractFSBossEntity.this.doHurtTarget(target);
                attackCooldown = Math.max(3, (int) Math.round(20.0 / getAttackSpeedMultiplier()));
            }
        }

        @Override
        public void stop() {
            AbstractFSBossEntity.this.getNavigation().stop();
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!this.dialogueStarted) {
            // 潜行右击直接跳过对话
            if (player.isShiftKeyDown()) {
                this.startBattle();
                return InteractionResult.SUCCESS;
            }
            String bossName = this.getName().getString();
            player.sendSystemMessage(Component.literal("§5§l" + bossName + "§r§5: "
                    + Component.translatable(getDialogueKey() + "greeting").getString()));
            player.sendSystemMessage(Component.literal("§5§l" + bossName + "§r§5: "
                    + Component.translatable(getDialogueKey() + "warning").getString()));
            player.sendSystemMessage(Component.translatable(getDialogueKey() + "skip_hint"));

            this.dialogueStarted = true;
            this.battleStartDelay = BATTLE_START_DELAY;
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.literal("§5§l" + this.getName().getString() + "§r§5: "
                + Component.translatable(getDialogueKey() + "in_battle").getString()));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.challengeHintCooldown > 0) {
            this.challengeHintCooldown--;
        }
        // 兜底：结构放出来的待机 boss 名字由语言文件拼（不在结构里硬编码），
        // 万一某条加载路径没走到，这里补一次。
        if (this.challengeOnUse && !this.hasCustomName()) {
            this.refreshChallengeName();
        }
        if (this.dialogueStarted && !this.battleActive) {
            if (--this.battleStartDelay <= 0) {
                this.startBattle();
            }
        }
    }

    /** 进入战斗：锁定人数、缩放生命、亮血条。 */
    protected void startBattle() {
        if (this.battleActive) {
            return;
        }
        this.battleActive = true;
        this.dialogueStarted = true;
        // 已经开战了，头顶那句「右键挑战」该撤掉，只留角色名（它同时是血条上显示的名字）
        this.refreshChallengeName();
        this.participants = countParticipants();
        this.healthMultiplier = 1.0 + 0.5 * (Math.min(this.participants, MAX_PARTICIPANTS) - 1);
        this.applyHealthScaling();

        this.bossEvent = new ServerBossEvent(
                Component.translatable(getEntityLangKey()),
                getBossBarColor(),
                BossEvent.BossBarOverlay.PROGRESS);
        this.bossEvent.setProgress(1.0f);

        if (!this.level().isClientSide) {
            for (Player p : this.level().players()) {
                if (p.distanceTo(this) < PARTICIPANT_RADIUS) {
                    p.sendSystemMessage(Component.literal("§c§l" + this.getName().getString() + "§r§c "
                            + Component.translatable(getDialogueKey() + "battle_start").getString()));
                    if (p instanceof ServerPlayer sp) {
                        this.bossEvent.addPlayer(sp);
                    }
                }
            }
        }

        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2.0f, 1.0f);

        // 教学楼里的三人是一组：挑战其中任何一个，旁边还在待机的同伴立刻一起进入战斗状态。
        // 先把自己的 battleActive 置上了，所以这里不会互相递归（startBattle 开头有早退）。
        if (!this.level().isClientSide) {
            for (AbstractFSBossEntity ally : nearbyBosses(CHALLENGE_LINK_RADIUS)) {
                if (ally != this && !ally.isBattleActive()) {
                    GalBoss.LOGGER.debug("[FS] {} 把 {} 一起拉进战斗",
                            this.getName().getString(), ally.getName().getString());
                    ally.startBattle();
                }
            }
        }

        GalBoss.LOGGER.debug("[FS] {} entered battle with {} participant(s), HP x{}",
                this.getName().getString(), this.participants, this.healthMultiplier);
    }

    /** 统计战斗开始时周围可参战的玩家数量。 */
    protected int countParticipants() {
        int n = 0;
        for (Player p : this.level().players()) {
            if (!p.isSpectator() && p.isAlive() && p.distanceTo(this) < PARTICIPANT_RADIUS) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    private void applyHealthScaling() {
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        maxHealth.removeModifier(HP_SCALE_UUID);
        maxHealth.addPermanentModifier(new AttributeModifier(
                HP_SCALE_UUID, "galboss:party_scale", this.healthMultiplier - 1.0,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        this.setHealth(this.getMaxHealth());
    }

    // -------------------------------------------------------------------- 存档
    //
    // 战斗状态必须落盘，否则世界一存一读（退出重进 / 单人存档重新加载）
    // battleActive 回到 false，boss 立刻变回「还没开战」的可对话状态，
    // 玩家再右击一次就会重新走 startBattle()，而那里会 setHealth(max) —— 白送满血。

    private static final String TAG_BATTLE_ACTIVE = "FsBattleActive";
    private static final String TAG_DIALOGUE_STARTED = "FsDialogueStarted";
    private static final String TAG_PARTICIPANTS = "FsParticipants";
    private static final String TAG_HEALTH_MULTIPLIER = "FsHealthMultiplier";
    private static final String TAG_ALLIES_LOST = "FsAlliesLost";
    private static final String TAG_CHALLENGE_ON_USE = "FsChallengeOnUse";

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(TAG_BATTLE_ACTIVE, this.battleActive);
        tag.putBoolean(TAG_DIALOGUE_STARTED, this.dialogueStarted);
        tag.putInt(TAG_PARTICIPANTS, this.participants);
        tag.putDouble(TAG_HEALTH_MULTIPLIER, this.healthMultiplier);
        tag.putInt(TAG_ALLIES_LOST, this.alliesLost);
        tag.putBoolean(TAG_CHALLENGE_ON_USE, this.challengeOnUse);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        this.battleActive = tag.getBoolean(TAG_BATTLE_ACTIVE);
        // 战斗中读档时即便标记缺失也要认作「已经讲过话了」，不然会重播一遍开场对白
        this.dialogueStarted = tag.getBoolean(TAG_DIALOGUE_STARTED) || this.battleActive;
        this.participants = Math.max(1, tag.getInt(TAG_PARTICIPANTS));
        this.healthMultiplier = tag.contains(TAG_HEALTH_MULTIPLIER)
                ? tag.getDouble(TAG_HEALTH_MULTIPLIER)
                : 1.0;
        this.alliesLost = tag.getInt(TAG_ALLIES_LOST);
        // 结构里自带的 boss 会带着这个标记生成，读档后也要保住
        this.challengeOnUse = tag.getBoolean(TAG_CHALLENGE_ON_USE);
        this.refreshChallengeName();

        if (this.battleActive) {
            this.reapplyHealthScaling();
            this.restoreBossBar();
        }
    }

    /** 结构自带的待机 boss：只有玩家右键挑战才会开战。 */
    public boolean isChallengeOnUse() {
        return this.challengeOnUse;
    }

    /** 设置待机标记（结构生成、调试命令或刷怪蛋都可以用）。 */
    public void setChallengeOnUse(boolean challengeOnUse) {
        this.challengeOnUse = challengeOnUse;
        this.refreshChallengeName();
    }

    /** 待机时头顶挂的名字：「右键挑战 · 角色名」，文案由语言文件拼，中英自适应。 */
    private Component getChallengeDisplayName() {
        return Component.translatable("message.galboss.challenge_label",
                Component.translatable(getEntityLangKey()));
    }

    /** 待机 → 挂「右键挑战」提示；已经开战 → 只留角色本名（血条上用的也是它）。 */
    private void refreshChallengeName() {
        if (!this.challengeOnUse) {
            return;
        }
        this.setCustomName(this.battleActive
                ? Component.translatable(getEntityLangKey())
                : this.getChallengeDisplayName());
        this.setCustomNameVisible(true);
    }

    /**
     * 读档后把生命倍率修饰符补回去，且<b>不</b>回血。
     *
     * 生命上限的属性修饰符能不能随 NBT 回来并不牢靠（原版 {@code LivingEntity} 读档时
     * 走的是 {@code addTransientModifier}），一旦丢掉，多人局的 2000 血会缩回基础值、
     * 当前血量还会被夹到新的上限，看起来就是「血条莫名满了」。
     * 这里按持久化的倍率重新挂一次（同 UUID 是替换，不会叠加），并保持原有血量比例。
     */
    private void reapplyHealthScaling() {
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        float ratio = this.getMaxHealth() > 0.0f ? this.getHealth() / this.getMaxHealth() : 1.0f;
        maxHealth.removeModifier(HP_SCALE_UUID);
        maxHealth.addPermanentModifier(new AttributeModifier(
                HP_SCALE_UUID, "galboss:party_scale", this.healthMultiplier - 1.0,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        this.setHealth(Math.max(1.0f, this.getMaxHealth() * ratio));
    }

    /**
     * 读档后把血条重新挂出来。
     *
     * 这里刻意不复用 {@link #startBattle()}：那边会重新统计参战人数、重算生命倍率，
     * 并且 {@code setHealth(getMaxHealth())}。生命上限的属性修饰符本身随 NBT 一起存下来了
     * （{@code LivingEntity} 会存 Attributes），当前血量也在 Health 里，
     * 所以只需要补上血条对象，血量原样保留。
     */
    private void restoreBossBar() {
        if (this.level().isClientSide) {
            return;
        }
        this.bossEvent = new ServerBossEvent(
                Component.translatable(getEntityLangKey()),
                getBossBarColor(),
                BossEvent.BossBarOverlay.PROGRESS);
        float max = this.getMaxHealth();
        this.bossEvent.setProgress(max > 0.0f ? this.getHealth() / max : 1.0f);

        if (this.level() instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < PARTICIPANT_RADIUS) {
                    this.bossEvent.addPlayer(player);
                }
            }
        }
        GalBoss.LOGGER.debug("[FS] {} restored mid-battle at {}/{} HP",
                this.getName().getString(), this.getHealth(), this.getMaxHealth());
    }

    // ------------------------------------------------------------------ 每 tick 维护

    @Override
    public void aiStep() {
        super.aiStep();

        if (!this.battleActive) {
            return;
        }

        if (!this.level().isClientSide && this.bossEvent != null) {
            this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        }

        // 联动 / 升级状态每 10 tick 重算一次，省掉不必要的实体查询
        if (--this.refreshTimer <= 0) {
            this.refreshTimer = 10;
            this.refreshAllyState();
            this.refreshCombatModifiers();
        }

        if (this.lastStanding) {
            if (--this.ultimateTimer <= 0) {
                this.ultimateTimer = 20 * 20;
                if (!this.level().isClientSide) {
                    this.castUltimate();
                }
            }
        }

        this.tickBoss();
    }

    private void refreshAllyState() {
        this.allyTohkaAlive = false;
        this.allyReiAlive = false;
        this.allyIkukoAlive = false;

        for (AbstractFSBossEntity ally : this.level().getEntitiesOfClass(
                AbstractFSBossEntity.class, this.getBoundingBox().inflate(ALLY_RADIUS))) {
            if (ally == this || !ally.isAlive()) {
                continue;
            }
            switch (ally.getRole()) {
                case TOHKA -> this.allyTohkaAlive = true;
                case REI -> this.allyReiAlive = true;
                case IKUKO -> this.allyIkukoAlive = true;
            }
        }

        boolean becameLastStanding = this.alliesLost >= 2 && !this.lastStanding;
        if (becameLastStanding) {
            this.lastStanding = true;
            this.ultimateTimer = 20 * 20;
            this.onLastStandEntered();
        }
    }

    private void refreshCombatModifiers() {
        setModifier(Attributes.ATTACK_DAMAGE, ATTACK_LINK_UUID, "galboss:link_attack", combinedAttackMultiplier());
        setModifier(Attributes.MOVEMENT_SPEED, SPEED_LINK_UUID, "galboss:link_speed", combinedSpeedMultiplier());

        if (this.lastStanding) {
            applyPermanentEffect(MobEffects.DAMAGE_RESISTANCE, 0);
            applyPermanentEffect(MobEffects.DAMAGE_BOOST, 1);
            applyPermanentEffect(MobEffects.MOVEMENT_SPEED, 0);
        }
    }

    private void setModifier(net.minecraft.world.entity.ai.attributes.Attribute attribute,
                             UUID uuid, String name, double multiplier) {
        AttributeInstance instance = this.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(uuid);
        if (Math.abs(multiplier - 1.0) > 1e-4) {
            instance.addTransientModifier(new AttributeModifier(
                    uuid, name, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private void applyPermanentEffect(net.minecraft.world.effect.MobEffect effect, int amplifier) {
        MobEffectInstance current = this.getEffect(effect);
        if (current == null || current.getDuration() < 200 || current.getAmplifier() != amplifier) {
            this.addEffect(new MobEffectInstance(effect, 600, amplifier, false, false, false));
        }
    }

    /** 进入最终阶段：先放爆发姿态，子类可覆写追加自己的效果。 */
    protected void onLastStandEntered() {
        playAnim("phase");
    }

    // ---------------------------------------------------------------- 联动 / 升级

    protected boolean isAllyAlive(FSRole role) {
        return switch (role) {
            case TOHKA -> this.allyTohkaAlive;
            case REI -> this.allyReiAlive;
            case IKUKO -> this.allyIkukoAlive;
        };
    }

    protected double combinedAttackMultiplier() {
        double m = getAttackMultiplier();
        if (this.alliesLost >= 2) {
            m *= 1.30;
        } else if (this.alliesLost == 1) {
            m *= 1.15;
        }
        // 郁子存活 → 礼 攻击 +15%
        if (getRole() == FSRole.REI && isAllyAlive(FSRole.IKUKO)) {
            m *= 1.15;
        }
        return m;
    }

    protected double combinedDamageTakenMultiplier() {
        double m = getDamageTakenMultiplier();
        // 桐香存活 → 礼 / 郁子 受到伤害 -10%
        if (getRole() != FSRole.TOHKA && isAllyAlive(FSRole.TOHKA)) {
            m *= 0.90;
        }
        return m;
    }

    protected double combinedSpeedMultiplier() {
        double m = getSpeedMultiplier();
        // 礼存活 → 郁子 移动速度 +10%
        if (getRole() == FSRole.IKUKO && isAllyAlive(FSRole.REI)) {
            m *= 1.10;
        }
        return m;
    }

    protected double combinedCooldownMultiplier() {
        double m = getCooldownMultiplier();
        if (this.alliesLost >= 2) {
            m *= 0.75;
        } else if (this.alliesLost == 1) {
            m *= 0.90;
        }
        return m;
    }

    /**
     * 把秒数换算成受冷却倍率影响的 tick 数。
     *
     * <p>{@code public} 是给 {@code boss} 包里那套技能对象用的：技能结束时按自己的冷
     * 却秒数上冷却，必须走同一条倍率（P2 与阵亡同伴会缩短冷却）。
     */
    public int cd(double seconds) {
        return Math.max(1, (int) Math.round(seconds * 20 * combinedCooldownMultiplier()));
    }

    public int cdTicks(int ticks) {
        return Math.max(1, (int) Math.round(ticks * combinedCooldownMultiplier()));
    }

    /** 参战人数，战斗开始后才有意义。 */
    protected int getParticipants() {
        return this.participants;
    }

    /** 场上还活着的其它 FS boss。 */
    protected List<AbstractFSBossEntity> nearbyBosses(double radius) {
        List<AbstractFSBossEntity> result = new ArrayList<>();
        for (AbstractFSBossEntity boss : this.level()
                .getEntitiesOfClass(AbstractFSBossEntity.class, this.getBoundingBox().inflate(radius))) {
            if (boss != this && boss.isAlive()) {
                result.add(boss);
            }
        }
        return result;
    }

    /**
     * 「交叉火力」：礼点名一个玩家，另外两人立刻压上去。
     *
     * 默认只换目标，各自子类覆写成自己最擅长的压制手段
     * （桐香用禁令限制走位、郁子直接贴脸突击）。
     */
    public void respondToCrossfire(Player target) {
        this.setTarget(target);
    }

    protected boolean isBattleActive() {
        return this.battleActive;
    }

    protected boolean isLastStanding() {
        return this.lastStanding;
    }

    // ---------------------------------------------------------- 整合包伤害适配

    /**
     * 这次伤害按「整合包适配」规则要乘的系数。
     *
     * <p>整合包里玩家的武器 / 法术动辄打出原版几十倍的伤害，三个 boss 会被瞬间融化。
     * 这里只按<b>来源</b>加一层减伤（{@code galboss-common.toml} 的 {@code boss_defense} 段）：
     * <ul>
     *   <li>非玩家来源（环境、野怪、别的模组的非玩家单位）：默认 ×0.10</li>
     *   <li>玩家的魔法 / 法术伤害：默认 ×0.50</li>
     *   <li>玩家的物理伤害（近战、箭、投射物）：原样</li>
     * </ul>
     * 两条规则取「更强的那一条」而不是相乘：非玩家的魔法伤害只吃 -90%。
     *
     * <p>判定顺序有意为之：先放行 {@link DamageTypeTags#BYPASSES_INVULNERABILITY}
     * （{@code /kill}、虚空），否则 boss 会变得「清不掉」。
     *
     * <p>公开成 static 是为了让召唤物之类也能复用同一套规则（目前只有三个 boss 在用）。
     */
    public static float modpackDamageMultiplier(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return 1.0f;
        }
        if (!isPlayerDamage(source)) {
            return (float) GalBossConfig.NON_PLAYER_DAMAGE_MULTIPLIER.get().doubleValue();
        }
        return isMagicDamage(source)
                ? (float) GalBossConfig.PLAYER_MAGIC_DAMAGE_MULTIPLIER.get().doubleValue()
                : 1.0f;
    }

    /**
     * 单次伤害上限（{@code damage_cap.perHit}，默认 100，{@code <=0} 关闭）。
     *
     * <p>在所有倍率<b>之后</b>封顶 —— 这是给「玩家一刀几万」准备的最后一道闸。
     * {@code /kill} 与虚空伤害照旧放行：限了它们 boss 就清不掉了。
     */
    public static float capDamagePerHit(DamageSource source, float amount) {
        int cap = GalBossConfig.DAMAGE_CAP_PER_HIT.get();
        if (cap <= 0 || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || amount <= cap) {
            return amount;
        }
        return (float) cap;
    }

    /**
     * 这次伤害算不算「玩家打的」。
     *
     * <p>直接由玩家造成、或者是玩家名下宠物 / 召唤物造成的都算（可用
     * {@code boss_defense.petsCountAsPlayer} 关掉）—— 整合包里一堆战斗女仆、
     * 狼、召唤物，把它们一概当「非玩家」会让玩家觉得自己的阵容在打空气。
     */
    public static boolean isPlayerDamage(DamageSource source) {
        net.minecraft.world.entity.Entity attacker = source.getEntity();
        if (attacker instanceof Player) {
            return true;
        }
        return GalBossConfig.PETS_COUNT_AS_PLAYER.get()
                && attacker instanceof OwnableEntity ownable
                && ownable.getOwner() instanceof Player;
    }

    /**
     * 魔法 / 法术伤害的判定。
     *
     * <p>认原版两个魔法类型，外加 {@link DamageTypeTags#WITCH_RESISTANT_TO} ——
     * 整合包作者要把自家模组的法术类型也算进来，写个数据包往这个标签里追加即可，
     * <b>不用改这里的代码</b>：
     * <pre>data/&lt;pack&gt;/tags/damage_type/witch_resistant_to.json</pre>
     */
    public static boolean isMagicDamage(DamageSource source) {
        return source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypeTags.WITCH_RESISTANT_TO);
    }

    // -------------------------------------------------------------------- 战斗

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 教学楼里待机的 boss 只认「右键挑战」：开战前打不动它（不掉血），
        // 只给动手的玩家提一句，免得被隔墙磨血或误以为打坏了。
        if (!this.battleActive && this.challengeOnUse) {
            if (this.challengeHintCooldown <= 0 && source.getEntity() instanceof ServerPlayer challenger) {
                this.challengeHintCooldown = CHALLENGE_HINT_COOLDOWN;
                challenger.displayClientMessage(
                        Component.translatable("message.galboss.challenge_hint", this.getName()), true);
            }
            return false;
        }
        if (!this.battleActive && source.getEntity() instanceof LivingEntity attacker) {
            this.startBattle();
            this.setTarget(attacker);
        }
        float scaled = amount;
        if (this.battleActive) {
            scaled = (float) (amount * combinedDamageTakenMultiplier() * modpackDamageMultiplier(source));
            scaled = capDamagePerHit(source, scaled);
        }
        return super.hurt(source, scaled);
    }

    /** 近战攻击距离（格），子类覆写。 */
    protected double getMeleeReach() {
        return 3.0;
    }

    @Override
    public void die(DamageSource source) {
        // 先让周围同伴知道少了一个人，再走正常死亡流程
        if (!this.level().isClientSide) {
            for (AbstractFSBossEntity ally : this.level().getEntitiesOfClass(
                    AbstractFSBossEntity.class, this.getBoundingBox().inflate(ALLY_RADIUS))) {
                if (ally != this && ally.isAlive()) {
                    ally.notifyAllyLost();
                }
            }
        }
        super.die(source);
    }

    /** 同伴阵亡：叠加升级层数，第二人死亡时大幅强化并回复生命。 */
    protected void notifyAllyLost() {
        this.alliesLost++;
        if (this.alliesLost >= 2) {
            this.heal(this.getMaxHealth() * 0.20f);
        }
        this.onAllyLost();
        this.refreshTimer = 0;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (this.battleActive && this.bossEvent != null) {
            this.bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (this.bossEvent != null) {
            this.bossEvent.removePlayer(player);
        }
    }

    /** 向附近参战玩家播报一条以 boss 名义发出的语言文本。 */
    protected void broadcastNearby(String langKey, String color) {
        for (Player player : nearbyPlayers(PARTICIPANT_RADIUS)) {
            player.sendSystemMessage(Component.literal(color + "§l" + this.getName().getString() + "§r" + color + " "
                    + Component.translatable(langKey).getString()));
        }
    }

    /** 取当前所有参战玩家（非旁观、存活）。 */
    protected List<Player> nearbyPlayers(double radius) {
        List<Player> result = new ArrayList<>();
        for (Player p : this.level().players()) {
            if (!p.isSpectator() && p.isAlive() && p.distanceTo(this) <= radius) {
                result.add(p);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 动画

    /** 控制器名，触发动画时要一起用（GeckoLib 按名字找控制器）。 */
    protected static final String CONTROLLER = "controller";

    /**
     * 需要一次性播放的动画。
     *
     * 这些不能靠本地字段驱动 —— 客户端跑不到服务端的 AI 逻辑，字段传不过去。
     * GeckoLib 的触发系统会在服务端发包同步给追踪中的客户端，所以一律走它。
     */
    private static final List<String> TRIGGERED_ANIMS =
            List.of("attack", "shoot", "charge", "dash", "combo", "cast", "guard", "rage", "phase");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 过渡只有 2 tick：冲刺/animation 这类要「和位移同时开始」的动作，
        // 过渡太长就等于把动作的前段耗在混合里，看起来像没动。
        AnimationController<AbstractFSBossEntity> controller =
                new AnimationController<>(this, CONTROLLER, 2, this::predicate);
        for (String name : TRIGGERED_ANIMS) {
            controller.triggerableAnim(name, RawAnimation.begin()
                    .then(getAnimPrefix() + name, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(controller.receiveTriggeredAnimations());
    }

    /**
     * 播放一段一次性动画（近战挥击或技能）。
     *
     * 动画本身的时长决定它播多久，播完自动回到 idle / walk / hurt。
     * 同一个名字在播放中重复触发不会重新开始（GeckoLib 按名字判重），
     * 所以像「连罚」这种连段要做成一整段动画，而不是每次命中触发一次。
     */
    protected void playAnim(String suffix) {
        if (this.level() != null && !this.level().isClientSide) {
            triggerAnim(CONTROLLER, suffix);
        }
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        // 正在播放的触发动画优先，这段时间不要去覆盖它
        if (state.getController().isPlayingTriggeredAnimation()) {
            return PlayState.CONTINUE;
        }
        if (this.hurtTime > 0) {
            state.getController().setAnimation(
                    RawAnimation.begin().then(getAnimPrefix() + "hurt", Animation.LoopType.PLAY_ONCE));
            return PlayState.CONTINUE;
        }
        if (state.isMoving()) {
            state.getController().setAnimation(RawAnimation.begin().thenLoop(getAnimPrefix() + "walk"));
            return PlayState.CONTINUE;
        }
        state.getController().setAnimation(RawAnimation.begin().thenLoop(getAnimPrefix() + "idle"));
        return PlayState.CONTINUE;
    }

    /** 近战命中时挥一下手；技能命中走各自技能自己的动画。 */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean result = super.doHurtTarget(target);
        if (result) {
            playAnim("attack");
        }
        return result;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
