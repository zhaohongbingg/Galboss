package com.example.galboss.entity;

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

/**
 * 桐香「学生会召集」召唤出的 FS Guard。
 *
 * 复用桐香的模型与贴图（渲染时缩小），自身没有独立资源需求。
 * 一旦场上不再有桐香存活，会自动消散，避免战斗结束后残留一堆小怪。
 */
public class FsGuardEntity extends Monster implements GeoEntity {

    private static final String ANIM_PREFIX = "animation.reizein_tohka.";

    /** 每 20 tick 检查一次召唤者是否还在。 */
    private static final int LIFELINE_CHECK_INTERVAL = 20;

    private static final double OWNER_RADIUS = 64.0;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int lifelineTimer = LIFELINE_CHECK_INTERVAL;

    public FsGuardEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setMaxUpStep(1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28 * 1.15) // 比本体快 15%
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2, false));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) {
            return;
        }
        if (--this.lifelineTimer > 0) {
            return;
        }
        this.lifelineTimer = LIFELINE_CHECK_INTERVAL;

        boolean summonerAlive = false;
        for (ReizenTohkaEntity tohka : this.level().getEntitiesOfClass(
                ReizenTohkaEntity.class, this.getBoundingBox().inflate(OWNER_RADIUS))) {
            if (tohka.isAlive()) {
                summonerAlive = true;
                break;
            }
        }
        if (!summonerAlive) {
            this.discard();
        }
    }

    /** 供桐香统计「场上还有几只护卫」。 */
    public static int countNearby(Level level, LivingEntity around, double radius) {
        return level.getEntitiesOfClass(FsGuardEntity.class, around.getBoundingBox().inflate(radius)).size();
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 5, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        if (this.hurtTime > 0) {
            state.getController().setAnimation(
                    RawAnimation.begin().then(ANIM_PREFIX + "hurt", Animation.LoopType.PLAY_ONCE));
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
