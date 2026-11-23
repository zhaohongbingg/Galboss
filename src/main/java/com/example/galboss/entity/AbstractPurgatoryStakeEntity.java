package com.example.galboss.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
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

import java.util.List;

/**
 * 炼狱七桩（炼狱七姊妹）的共用基类 —— 贝阿朵莉切的七名仆从，注册为精英怪。
 *
 * <p><b>素材来源</b>：外部交付的七大罪 YSM 包（<code>Characters/&lt;Id&gt;/YSM-source</code>）。
 * 七人共用同一套服装与骨架，只有发型与发色不同；geo 由
 * <code>tools/blockbench/import_purgatory_stakes.mjs</code> 从 YSM 的 bedrock geo 直接搬运，
 * 只改了 identifier，其余（含 465 个方块的逐面 UV）原样保留。贴图 2048×1024 不降采样 ——
 * geo 的 <code>texture_width/height</code> 是 UV 空间（512×256），GeckoLib 会先归一化再采样，
 * 所以保留大图就是白拿 4 倍纹理密度。
 *
 * <p><b>身高</b>：交付模型 45.33–45.54 单位，按 YSM 的 <code>height_scale = 0.7</code> 惯例在
 * 渲染器里 {@code withScale(0.7f)} 收到 ≈1.99 格，与 {@code ModEntities} 的
 * <code>.sized(0.7f, 2.0f)</code> 对齐（和贝阿朵莉切同一套数）。改大小必须两处一起改。
 *
 * <p><b>手持物</b>：炼狱之柱走「物品 + BlockAndItemGeoLayer」，挂点骨骼
 * <code>RightHandLocator</code>（YSM 源模型自带，和贝阿朵莉切的烟斗用同一个挂点），
 * 见 {@code client/renderer/layer/PurgatoryPillarLayer}。柱子本身是 GeoItem
 * （{@code item/PurgatoryPillarItem}）—— 它有 819/968 个自由角度旋转的方块，
 * 原版物品模型只接受 0/±22.5/±45，走 elements 会被削平，只能走 GeoItemRenderer。
 */
public abstract class AbstractPurgatoryStakeEntity extends Monster implements GeoEntity {

    /** 动画控制器名：与 {@code triggerAnim(...)} 的第一参数对应。 */
    protected static final String CONTROLLER = "main";

    /** 共用动画文件 {@code animations/purgatory_stake.animation.json} 里的前缀。 */
    protected static final String ANIM_PREFIX = "animation.purgatory_stake.";

    /** 一次性动作。死亡优先级最高，见 {@link #predicate}。 */
    private static final List<String> TRIGGERED_ANIMS = List.of("attack", "hurt", "death", "cast", "burst");

    /**
     * walk 动画的原生步频基准（1.0 秒循环，模型交付时就按这个烘焙）。
     *
     * <p>移动速度 0.30 明显快于玩家步行，固定 1.0 倍速会显得「腿慢慢晃、身体在平移」，
     * 所以按实际水平速度缩放播放速度（做法同黑山羊仆从）。
     */
    private static final double WALK_NOMINAL_SPEED = 0.22;

    private static final float WALK_SPEED_MIN = 0.7f;
    private static final float WALK_SPEED_MAX = 1.8f;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    protected AbstractPurgatoryStakeEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setMaxUpStep(1.0f);
    }

    /**
     * 资源键：geo / 贴图 / 注册名共用同一个 id（asmodeus、beelzebub…）。
     *
     * <p>不做成「用 getType() 反查表」是因为七姊妹后续要各自加技能，
     * 显式重写比查表更容易看出谁是谁。
     */
    public abstract String assetId();

    /** 精英怪基线属性：比原版僵尸硬得多，但远低于三位 FS 首领。 */
    public static AttributeSupplier.Builder createStakeAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 140.0)
                .add(Attributes.ATTACK_DAMAGE, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.35)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15, false));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 10.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------------ 主动技能框架

    /**
     * 七桩每人一套「1 被动 + 2 主动」，实现都在各自的子类里，这里只负责计时与调度。
     *
     * <p>为什么放在实体里而不是做成独立的 Boss 技能框架：七位是精英怪、没有阶段机，
     * 只有「按冷却轮转两个技能 + 每 tick 一个被动」，一个整数计时器就够了。
     * 真正的表现都落在 {@code stake/StakeFx} 的原语上（射线、扇形、圆环、拉拽、击飞）。
     */
    private int skillOneTimer = -1;

    private int skillTwoTimer = -1;

    /** 技能前摇：>0 表示正在蓄力，0 表示空闲。1 = 主动1，2 = 主动2。 */
    private int windup = 0;

    private int pendingSkill = 0;

    /** 前摇长度（tick）：给玩家一个能看见、能躲开的窗口。 */
    protected int windupTicks() {
        return 15;
    }

    /** 主动技能 1 冷却（tick）。 */
    protected int skillOneCooldown() {
        return 100;
    }

    /** 主动技能 2 冷却（tick）。 */
    protected int skillTwoCooldown() {
        return 240;
    }

    /** 技能的施放距离：超出就不空放（留着冷却）。 */
    protected double skillRange() {
        return 14.0D;
    }

    /** 每 tick 跑一次的核心被动。 */
    protected void stakePassiveTick() {
    }

    /** 主动技能 1（常规压制）。 */
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
    }

    /** 主动技能 2（核心爆发 / 控制）。 */
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
    }

    @Override
    public void aiStep() {
        super.aiStep();
        stakeTick();
    }

    /**
     * 是否允许跑被动与主动技能。阿斯莫德的分身会覆盖成 false ——
     * 分身是同一个实体类（AI 完整），不挡的话分身会继续放「镜花水月」再造分身，指数爆炸（实测踩过）。
     */
    protected boolean usesAbilities() {
        return true;
    }

    /** 是否算作「七桩之一」（分身影子不算，否则它们挡着黄金乡的登场判定）。 */
    public boolean countsAsStake() {
        return true;
    }

    private void stakeTick() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (!this.usesAbilities()) {
            return;
        }
        this.stakePassiveTick();

        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            this.skillOneTimer = 40;
            this.skillTwoTimer = 80;
            return;
        }
        if (this.distanceTo(target) > this.skillRange()) {
            return;
        }

        // 正在蓄力：只走前摇，不再发新技能；到点才真正结算
        if (this.windup > 0) {
            this.windup--;
            if (this.windup == 0) {
                this.releasePendingSkill(level);
            }
            return;
        }
        if (--this.skillOneTimer <= 0) {
            this.skillOneTimer = this.skillOneCooldown();
            this.beginWindup(level, 1);
        }
        if (this.windup == 0 && --this.skillTwoTimer <= 0) {
            this.skillTwoTimer = this.skillTwoCooldown();
            this.beginWindup(level, 2);
        }
    }

    /** 起手：播 cast 动画 + 地面预警环 + 蓄力音，给玩家 {@link #windupTicks()} 的反应时间。 */
    private void beginWindup(ServerLevel level, int skill) {
        this.pendingSkill = skill;
        this.windup = this.windupTicks();
        this.triggerAnim(CONTROLLER, "cast");
        com.example.galboss.stake.StakeFx.ring(level, this.position().add(0.0D, 0.1D, 0.0D),
                2.4D, 18, net.minecraft.core.particles.ParticleTypes.END_ROD);
        com.example.galboss.stake.StakeFx.sound(level, this.position(),
                net.minecraft.sounds.SoundEvents.EVOKER_PREPARE_ATTACK, 1.0F, 1.2F);
    }

    /** 前摇结束：重新取目标（可能已经跑掉），结算技能并播爆发动画。 */
    private void releasePendingSkill(ServerLevel level) {
        int skill = this.pendingSkill;
        this.pendingSkill = 0;
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        this.triggerAnim(CONTROLLER, "burst");
        com.example.galboss.stake.StakeFx.sound(level, this.position(),
                net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL, 1.1F, 1.0F);
        com.example.galboss.stake.StakeDebug.log("cast {} skill={} target={}",
                this.assetId(), skill, target.getName().getString());
        if (skill == 1) {
            this.castSkillOne(level, target);
        } else if (skill == 2) {
            this.castSkillTwo(level, target);
        }
    }

    /** 子类需要手动触发「下次技能立刻可用」时用（例如撒旦的冲锋命中重置）。 */
    protected void resetSkillOne() {
        this.skillOneTimer = 1;
    }

    /** 技能条件不满足时，20 tick 后再判断一次（不吃满整段冷却）。 */
    protected void resetSkillTwo() {
        this.skillTwoTimer = 20;
    }

    // ------------------------------------------------------------------ 受击 / 死亡

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && !this.level().isClientSide) {
            this.triggerAnim(CONTROLLER, "attack");
            this.onStakeHit(target);
        }
        return hit;
    }

    /** 命中追加效果（灵魂火、抽经验、叠被动……）。 */
    protected void onStakeHit(Entity target) {
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        // 致死那一击不要用受击动画盖掉死亡动画（die() 里已经触发 death）
        if (hurt && !this.level().isClientSide && this.isAlive()) {
            this.triggerAnim(CONTROLLER, "hurt");
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            this.triggerAnim(CONTROLLER, "death");
        }
        super.die(source);
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 过渡 4 tick：和 Beatrice / 黑山羊一致，太短起手跳帧、太长吃掉接触帧
        AnimationController<AbstractPurgatoryStakeEntity> controller =
                new AnimationController<>(this, CONTROLLER, 4, this::predicate);
        for (String name : TRIGGERED_ANIMS) {
            controller.triggerableAnim(name, RawAnimation.begin()
                    .then(ANIM_PREFIX + name, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(controller.receiveTriggeredAnimations());
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        // 死亡优先级最高：触发中的 attack / hurt 还没播完也要立刻倒地
        if (this.isDeadOrDying()) {
            if (!state.getController().isPlayingTriggeredAnimation()) {
                state.getController().setAnimation(
                        RawAnimation.begin().then(ANIM_PREFIX + "death", Animation.LoopType.PLAY_ONCE));
            }
            return PlayState.CONTINUE;
        }
        // 一次性动作在播时不要被 locomotion 抢走（官方 triggerableAnim 的用法要求）
        if (state.getController().isPlayingTriggeredAnimation()) {
            return PlayState.CONTINUE;
        }
        if (state.isMoving()) {
            state.getController().setAnimation(RawAnimation.begin().thenLoop(ANIM_PREFIX + "walk"));
            double speed = this.getDeltaMovement().horizontalDistance();
            state.setControllerSpeed((float) Math.max(WALK_SPEED_MIN,
                    Math.min(WALK_SPEED_MAX, speed / WALK_NOMINAL_SPEED)));
            return PlayState.CONTINUE;
        }
        state.getController().setAnimation(RawAnimation.begin().thenLoop(ANIM_PREFIX + "idle"));
        state.setControllerSpeed(1.0f);
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
