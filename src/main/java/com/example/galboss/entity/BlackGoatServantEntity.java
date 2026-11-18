package com.example.galboss.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
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

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 黑山羊仆从 —— 郁子「深渊召唤」召出的法术型仆从。
 *
 * <p>和 {@link FsGuardEntity} 一样是「boss 召出来的小弟」，但它是独立模型（不缩放复用 boss 资源），
 * 所以结构上更接近 {@link BeatriceEntity}：{@code Monster} + {@code GeoEntity}，
 * 近战 AI 之外再带一段失明诅咒。
 *
 * <p><b>召唤者存活检查</b>：每 20 tick 检查一次，召唤者死亡 / 消失 / 离开 64 格就自行消散。
 * 刷怪蛋或 {@code /summon} 放出来的没有召唤者，<b>不做</b>存活检查也不会自动消失（方便调试）。
 * 郁子死亡时不需要额外清理：她的山羊会在下一次检查时发现召唤者已死并 discard。
 *
 * <p><b>动画分两层</b>（同 Beatrice）：predicate 只负责 idle / walk 这两个循环状态；
 * attack / cast / hurt / death 全部走 GeckoLib 的 triggerableAnim，由服务端一次性触发、
 * 播完自动交还给 predicate。这样 0.8s 的顶撞动作不会被「swinging 只亮 6 tick」截断。
 *
 * <p><b>时序全部在 Java 侧</b>：诅咒 16 tick 后结算（对齐 cast 动画 0.6s 处的定格），
 * 顶撞伤害也延后到动画接触帧（0.4s = 8 tick）再打 —— 与 attack 动画的「接触帧在 0.4s」对齐。
 */
public class BlackGoatServantEntity extends Monster implements GeoEntity {

    /** 动画名统一前缀，和 animations/black_goat_servant.animation.json 里的键一一对应。 */
    private static final String ANIM_PREFIX = "animation.black_goat_servant.";
    private static final String CONTROLLER = "controller";

    /** 需要服务的触发式动画：这些名字必须与 animation.json 里的键后缀一致。 */
    private static final List<String> TRIGGERED_ANIMS = List.of("attack", "cast", "hurt", "death");

    // ------------------------------------------------------------------ 召唤者
    /** 每 20 tick 检查一次召唤者是否还在。 */
    private static final int LIFELINE_CHECK_INTERVAL = 20;
    /** 召唤者离得太远就消散（和 FsGuardEntity 一致）。 */
    private static final double OWNER_RADIUS = 64.0;
    private static final String TAG_SUMMONER_UUID = "SummonerUUID";

    // ------------------------------------------------------------------ 诅咒
    /** 失明诅咒冷却（8 秒）。 */
    private static final int CURSE_CD = 160;
    /** 施法前摇：契约要求 16 tick 后结算（cast 动画 0.6s 的定格帧附近）。 */
    private static final int CURSE_CAST_TICKS = 16;
    /** 施法距离（格）：超过这个距离只近战，不放诅咒。 */
    private static final double CURSE_RANGE = 14.0;
    /** 失明 / 虚弱各 3 秒。 */
    private static final int CURSE_EFFECT_TICKS = 3 * 20;
    /** 诅咒附带的魔法伤害。 */
    private static final float CURSE_DAMAGE = 3.0f;

    // ------------------------------------------------------------------ 普攻
    /**
     * 顶撞伤害结算延迟（tick）。
     *
     * <p>MeleeAttackGoal 在起手那一 tick 就会调 {@code doHurtTarget}，而动画的接触帧在 0.4s，
     * 这里把真正结算推迟 8 tick，让「看起来撞上了」和「掉血」发生在同一拍。
     */
    private static final int ATTACK_WINDUP_TICKS = 8;
    /** 延迟结算时允许的目标位移容差（格），防止目标瞬移后隔空挨打。 */
    private static final double ATTACK_RESOLVE_RANGE = 3.5;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** 召唤者实体；客户端拿不到也不需要，只有服务端的 lifeline 检查会用它。 */
    @Nullable
    private LivingEntity summoner;

    /** 召唤者 UUID：存档后实体还没加载时用它反查。null 表示「无召唤者」。 */
    @Nullable
    private UUID summonerUuid;

    private int lifelineTimer = LIFELINE_CHECK_INTERVAL;

    /** 诅咒冷却倒计时；到 0 后每 tick 尝试起手，条件不满足就继续等。 */
    private int curseCd = CURSE_CD;

    /** >0 表示正在施咒，倒计时结束结算。 */
    private int curseCastTicks = 0;

    /** >0 表示顶撞已经起手、还没结算。 */
    private int attackWindup = 0;

    /** 顶撞延迟结算的目标（起手时记下，接触帧再验证一次还活着 / 还在范围内）。 */
    @Nullable
    private Entity pendingMeleeTarget;

    // ------------------------------------------------------------------ 黄金碑文的祭品
    /**
     * 这只山羊是不是「黄金碑文」的祭品。
     *
     * <p>旧版这里是「属于第几夜的祭品 + 是否已苏醒」：碑文按夜分组，没轮到的那几只休眠站桩。
     * 改成<b>按数量判定</b>之后这些都不需要了 —— 被召出来的祭品<b>全部立刻苏醒</b>，
     * 谁是祭品只看标签（{@code SacrificeLedger.TAG_SACRIFICE}），死亡时统一由账本记账。
     *
     * <p>所以这个类不再关心碑文进度，只负责「一只会顶撞、会下失明诅咒的黑山羊」。
     */

    public BlackGoatServantEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        // 默认 0.6 的抬腿高度上不去一格台阶；与 FS 系列保持一致，让寻路能正常翻越地形
        this.setMaxUpStep(1.0f);
        // 刷怪蛋 / 命令放出来的山羊没有召唤者，不能被「离玩家太远」的自然清除规则刷掉
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        // 数值按契约 §6.1：45 血 / 7 攻 / 0.30 移速 / 2 甲 / 0.25 击退抗性 / 32 追踪
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 45.0)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        // 优先级顺序按契约 §6.1：先别淹死，再打人，然后才是闲逛 / 看人 / 东张西望
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.9));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------ 召唤者与存档

    /** 设置召唤者（郁子召唤时调用），同时记下 UUID 供存档恢复。 */
    public void setSummoner(@Nullable LivingEntity summoner) {
        this.summoner = summoner;
        this.summonerUuid = summoner != null ? summoner.getUUID() : null;
    }

    // ------------------------------------------------------------ 碑文祭品接口

    @Nullable
    public UUID getSummonerUuid() {
        return this.summonerUuid;
    }

    /**
     * 取召唤者，必要时按 UUID 反查。
     *
     * <p>读档时实体还没加载，只能先存 UUID；这里在用到时懒解析一次，解析不到就是 null，
     * lifeline 检查会按「召唤者已不存在」处理。
     */
    @Nullable
    public LivingEntity getSummoner() {
        if (this.summoner == null && this.summonerUuid != null
                && this.level() instanceof ServerLevel server) {
            Entity entity = server.getEntity(this.summonerUuid);
            if (entity instanceof LivingEntity living) {
                this.summoner = living;
            }
        }
        return this.summoner;
    }

    /** 供郁子统计「场上还有几只我召唤的山羊」。 */
    public static int countSummonedBy(Level level, LivingEntity summoner, double radius) {
        int count = 0;
        for (BlackGoatServantEntity goat : level.getEntitiesOfClass(
                BlackGoatServantEntity.class, summoner.getBoundingBox().inflate(radius))) {
            if (goat.getSummoner() == summoner) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.summonerUuid != null) {
            tag.putUUID(TAG_SUMMONER_UUID, this.summonerUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 老存档 / 刷怪蛋放的山羊没有这个键：保持 null，即「无召唤者」
        this.summonerUuid = tag.hasUUID(TAG_SUMMONER_UUID) ? tag.getUUID(TAG_SUMMONER_UUID) : null;
        this.summoner = null;
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) {
            return;
        }

        // 召唤者存活检查对碑文祭品也要跑：boss 倒下后它们得自己消散，不能永远站桩
        if (--this.lifelineTimer <= 0) {
            this.lifelineTimer = LIFELINE_CHECK_INTERVAL;
            checkLifeline();
        }

        // 顶撞的延迟结算先走：即便这一 tick 开始施咒，之前起手的那一下也要落地
        tickAttackWindup();

        if (this.curseCastTicks > 0) {
            tickCurseCast();
            return;
        }

        if (this.curseCd > 0) {
            this.curseCd--;
        } else {
            tryStartCurse();
        }
    }

    /** 召唤者死亡 / 消失 / 离太远 → 自行消散。无召唤者的山羊不做检查。 */
    private void checkLifeline() {
        if (this.summonerUuid == null && this.summoner == null) {
            return;
        }
        LivingEntity owner = this.getSummoner();
        if (owner == null || !owner.isAlive() || this.distanceTo(owner) > OWNER_RADIUS) {
            this.discard();
        }
    }

    // ------------------------------------------------------------------ 诅咒

    /** 条件：有目标、距离 ≤14 格、有视线。三条都满足才起手。 */
    private void tryStartCurse() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        if (this.distanceTo(target) > CURSE_RANGE || !this.hasLineOfSight(target)) {
            return;
        }
        this.curseCd = CURSE_CD;
        this.curseCastTicks = CURSE_CAST_TICKS;
        // 施法期间站定：导航停掉，面向目标
        this.getNavigation().stop();
        this.getLookControl().setLookAt(target, 30.0f, 30.0f);
        this.triggerAnim(CONTROLLER, "cast");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    private void tickCurseCast() {
        // 施法期间不移动：MeleeAttackGoal 可能在这一 tick 里重新下过移动指令，这里再停一次
        this.getNavigation().stop();
        LivingEntity target = this.getTarget();
        if (target != null) {
            this.getLookControl().setLookAt(target, 30.0f, 30.0f);
        }
        if (--this.curseCastTicks > 0) {
            return;
        }
        resolveCurse();
    }

    /** 结算：失明 3s + 虚弱 3s + 3 点魔法伤害。 */
    private void resolveCurse() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, CURSE_EFFECT_TICKS, 0, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CURSE_EFFECT_TICKS, 0, false, true, true));
        target.hurt(this.damageSources().magic(), CURSE_DAMAGE);
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.WITCH,
                    target.getX(), target.getY() + 1.0, target.getZ(), 24, 0.4, 0.6, 0.4, 0.02);
        }
    }

    // ------------------------------------------------------------------ 顶撞

    /**
     * 起手：播 attack 动画 + 冲撞前的低吼，伤害延后 8 tick（接触帧 0.4s）再结算。
     *
     * <p>返回 true 让 MeleeAttackGoal 认为这次出手成立（它会照常进攻击冷却），
     * 真正的伤害在 {@link #tickAttackWindup()} 里走 {@code super.doHurtTarget}。
     */
    @Override
    public boolean doHurtTarget(Entity target) {
        if (this.level().isClientSide) {
            return false;
        }
        this.triggerAnim(CONTROLLER, "attack");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.GOAT_PREPARE_RAM, SoundSource.HOSTILE, 1.0f, 1.0f);
        this.pendingMeleeTarget = target;
        this.attackWindup = ATTACK_WINDUP_TICKS;
        return true;
    }

    private void tickAttackWindup() {
        if (this.attackWindup <= 0) {
            return;
        }
        if (--this.attackWindup > 0) {
            return;
        }
        Entity target = this.pendingMeleeTarget;
        this.pendingMeleeTarget = null;
        if (target == null || !target.isAlive()) {
            return;
        }
        // 接触帧那一刻目标已经跑远就空挥，不要隔空掉血
        if (this.distanceTo(target) > ATTACK_RESOLVE_RANGE) {
            return;
        }
        super.doHurtTarget(target);
    }

    // ------------------------------------------------------------------ 音效

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.GOAT_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GOAT_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GOAT_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 1.0f;
    }

    // ------------------------------------------------------------------ 受击 / 死亡

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        // 致死那一击不要再用受击动画盖掉死亡动画（die() 里已经触发 death）
        if (hurt && !this.level().isClientSide && this.isAlive()) {
            this.triggerAnim(CONTROLLER, "hurt");
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            // 碑文记账不在这里做：祭品统一由 SacrificeLedger 按标签数数，
            // 这样黑山羊和原版宾客走的是同一条判定，谁死的都一样计一个。
            this.triggerAnim(CONTROLLER, "death");
        }
        super.die(source);
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 过渡 4 tick：与 Beatrice 一致，太短起手会跳帧、太长会把接触帧吃掉
        AnimationController<BlackGoatServantEntity> controller =
                new AnimationController<>(this, CONTROLLER, 4, this::predicate);
        for (String name : TRIGGERED_ANIMS) {
            controller.triggerableAnim(name, RawAnimation.begin()
                    .then(ANIM_PREFIX + name, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(controller.receiveTriggeredAnimations());
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        // 死亡优先级最高：之前触发的 hurt / attack 还没播完也要立刻切到倒地
        if (this.isDeadOrDying()) {
            if (!state.getController().isPlayingTriggeredAnimation()) {
                state.getController().setAnimation(
                        RawAnimation.begin().then(ANIM_PREFIX + "death", Animation.LoopType.PLAY_ONCE));
            }
            return PlayState.CONTINUE;
        }
        // 守卫放第二行：一次性动作在播时不要把动画抢走（官方 triggerableAnim 用法要求）
        if (state.getController().isPlayingTriggeredAnimation()) {
            return PlayState.CONTINUE;
        }
        if (state.isMoving()) {
            state.getController().setAnimation(RawAnimation.begin().thenLoop(ANIM_PREFIX + "walk"));
            return PlayState.CONTINUE;
        }
        state.getController().setAnimation(RawAnimation.begin().thenLoop(ANIM_PREFIX + "idle"));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
