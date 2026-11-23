package com.example.galboss.entity;

import com.example.galboss.boss.BossTelegraph;
import com.example.galboss.fx.GoldenButterflyBurst;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.item.ModItems;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.network.S2CSniperLockPacket;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.ritual.BodySeal;
import com.example.galboss.ritual.GoldenLand;
import com.example.galboss.ritual.SacrificeLedger;
import com.example.galboss.spell.ChessboardFieldSpell;
import com.example.galboss.spell.ChessboardJudgementSpell;
import com.example.galboss.spell.GoldenRainSpell;
import com.example.galboss.spell.SpellFx;
import com.example.galboss.spell.WitchDomainSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 贝阿朵莉切 —— 黄金魔女 / 「黄金碑文」遭遇的主宰者。
 */
public class BeatriceEntity extends Monster implements GeoEntity {

    private static final String ID = "beatrice";

    public static final int TOTAL_SACRIFICES = 17;
    /** 岛上总人数 = 贝阿朵莉切本人 + 17 个祭品（碑文设定里的「十八人」）。 */
    public static final int TOTAL_PEOPLE = TOTAL_SACRIFICES + 1;
    private static final int GOAT_COUNT = 13;
    private static final int GUEST_COUNT = 4;

    private static final double ARENA_RADIUS = 64.0;

    private static final int[] NIGHT_KILLS = {6, 8, 10, 11, 12, 13, 14, 15, 16, 17};

    private static final BodySeal.Seal[] NIGHT_SEAL = {
            null, null, null, BodySeal.Seal.HEAD, BodySeal.Seal.CHEST,
            BodySeal.Seal.BELLY, BodySeal.Seal.KNEE, BodySeal.Seal.FOOT, null, null
    };

    private static final EntityType<?>[] GUEST_POOL = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK,
            EntityType.STRAY, EntityType.SPIDER, EntityType.WITCH
    };

    private static final int VOLLEY_FIRST_DELAY = 6 * 20;
    private static final int VOLLEY_COUNT = 3;

    private static final int REPLENISH_INTERVAL = 2 * 20;
    private static final int REPLENISH_BATCH = 4;

    private static final String ANIM_PREFIX = "animation.beatrice.";
    private static final String CONTROLLER = "controller";
    private static final Set<String> TRIGGERED_ANIMS =
            Set.of("cast", "cast_area", "attack", "hurt", "death");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            this.getDisplayName(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    private boolean ritualActive;
    private int kills;
    private int night;
    private boolean awakened;

    private int replenishTimer = REPLENISH_INTERVAL;
    private int immuneHintCd;
    private boolean awakenedAnnounced;

    private final ButterflyVolleySkill butterflyVolley = new ButterflyVolleySkill();

    private String pendingTruthKey;
    private int pendingTruthTicks = -1;

    private final SniperSkill sniper = new SniperSkill();

    private int areaSpellCd = AREA_SPELL_CD;
    private static final int AREA_SPELL_CD = 16 * 20;

    /** 场地魔法（棋盘场地）的冷却：比领域法术长得多，因为它本身要挂 30 秒。 */
    private static final int FIELD_SPELL_CD = 45 * 20;
    private int fieldSpellCd = FIELD_SPELL_CD;

    // ------------------------------------------------------------------ 近战闪避瞬移

    /** 两次闪避之间的最小间隔。真复苏后才有意义（此前本来就免疫伤害）。 */
    private static final int DODGE_COOLDOWN = 6 * 20;

    /**
     * 触发概率。设成 1.0 = 只要冷却好了就必躲。
     * 之前是 0.6，砍十刀只躲两三下，实机很容易误判成「根本没生效」。
     */
    private static final float DODGE_CHANCE = 1.0f;

    /** 瞬移落点与当前位置的距离区间（格）。 */
    private static final double DODGE_MIN_DISTANCE = 6.0;
    private static final double DODGE_MAX_DISTANCE = 10.0;

    /** 只在这个距离内才认为「近战打过来了」，超出多半是弹射物或远程。 */
    private static final double DODGE_TRIGGER_RANGE = 5.0;

    /** 落点不能离攻击者太近，否则等于原地挨打。 */
    private static final double DODGE_KEEP_AWAY = 4.0;

    /** 诊断日志预算：前几次闪避判定把结论打进日志，之后静默。 */
    private static int DODGE_DEBUG_BUDGET = 10;

    private int dodgeCd;

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    public BeatriceEntity(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
        this.xpReward = 250;
        this.bossEvent.setVisible(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 1986.0)
                .add(Attributes.ATTACK_DAMAGE, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.60)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    // ------------------------------------------------------------------ 目标 / AI

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new AwakenedMeleeGoal());
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 16.0f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    private class AwakenedMeleeGoal extends MeleeAttackGoal {

        private AwakenedMeleeGoal() {
            super(BeatriceEntity.this, 1.0, true);
            this.setFlags(EnumSet.of(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE,
                    net.minecraft.world.entity.ai.goal.Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return awakened && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return awakened && super.canContinueToUse();
        }
    }

    // ------------------------------------------------------------------ 主循环 & 客户端氛围粒子

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide && this.isAlive()) {
            // 客户端常驻黄金蝶光环：常态轻盈环绕，碑文开启与真复苏后逐渐浓郁
            int interval = this.awakened ? 4 : (this.ritualActive ? 7 : 14);
            if (this.tickCount % interval == 0) {
                double angle = this.random.nextDouble() * Math.PI * 2.0;
                double radius = 0.65 + this.random.nextDouble() * 0.75;
                double py = this.getY() + 0.25 + this.random.nextDouble() * (this.getBbHeight() * 0.85);
                this.level().addParticle(ModParticles.GOLDEN_BUTTERFLY.get(),
                        this.getX() + Math.cos(angle) * radius,
                        py,
                        this.getZ() + Math.sin(angle) * radius,
                        0.0, 0.0, 0.0);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.immuneHintCd > 0) {
            this.immuneHintCd--;
        }
        if (this.dodgeCd > 0) {
            this.dodgeCd--;
        }
        this.sniper.tick(this);

        if (!this.ritualActive) {
            return;
        }

        // 等死亡事件和祭品存活状态都更新后，再推进到黄金乡；重载后也能继续。
        if (this.kills >= TOTAL_SACRIFICES && this.tickCount % 20 == 0
                && this.level() instanceof ServerLevel level
                && !level.dimension().equals(GoldenLand.DIMENSION)
                && this.tryEnterGoldenLand(level)) {
            return;
        }

        this.butterflyVolley.tick(this);
        this.tickAreaSpells();
        this.tickFieldSpell();

        if (this.pendingTruthTicks > 0 && --this.pendingTruthTicks == 0 && this.pendingTruthKey != null) {
            this.redTruth(this.pendingTruthKey);
            this.pendingTruthKey = null;
            this.pendingTruthTicks = -1;
        }

        if (!this.awakened && --this.replenishTimer <= 0) {
            this.replenishTimer = REPLENISH_INTERVAL;
            this.replenishSacrifices();
        }
        this.updateBossBar();
    }

    private boolean hasNearbyTarget() {
        return this.findCombatTarget() != null;
    }

    @Nullable
    private LivingEntity findCombatTarget() {
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            return target;
        }
        if (this.level() instanceof ServerLevel level) {
            Player nearest = level.getNearestPlayer(this, 40.0);
            if (nearest != null && nearest.isAlive() && !nearest.isSpectator() && !nearest.isCreative()) {
                return nearest;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 开场

    public void startRitual() {
        if (this.ritualActive || this.level().isClientSide) {
            return;
        }
        this.ritualActive = true;
        this.kills = 0;
        this.night = 0;
        this.awakened = false;
        this.awakenedAnnounced = false;
        this.butterflyVolley.reset(VOLLEY_FIRST_DELAY);

        if (this.level() instanceof ServerLevel level) {
            MinecraftServer server = level.getServer();
            if (server != null) {
                BodySeal.bind(server, this.getUUID());
            }
            // 开场召唤阵与黄金蝶群绽放
            PixelfxFx.play(level, this.position().add(0.0, 0.08, 0.0),
                    PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, 2.6f, 0.0f);
            level.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                    this.getX(), this.getY() + 1.2, this.getZ(), 16, 1.4, 0.8, 1.4, 0.03);
            SpellFx.shake(level, this.position(), 36.0, 0.45f, 8);
        }
        this.bossEvent.setVisible(true);
        this.updateBossBar();

        this.say("epitaph.galboss.preamble.1", ChatFormatting.DARK_RED);
        this.say("epitaph.galboss.preamble.2", ChatFormatting.DARK_RED);
        this.say("epitaph.galboss.start", ChatFormatting.LIGHT_PURPLE);
        this.summonSacrifices(GOAT_COUNT + GUEST_COUNT);
        this.say("epitaph.galboss.eighteen", ChatFormatting.GOLD);
        this.triggerAnim(CONTROLLER, "cast");
        this.playSoundAt(SoundEvents.EVOKER_PREPARE_SUMMON, 1.4f, 0.8f);
    }

    private void summonSacrifices(int total) {
        int goats = Math.min(GOAT_COUNT, total);
        int guests = Math.max(0, total - goats);
        int index = this.countSacrifices();
        for (int i = 0; i < goats; i++) {
            this.summonGoat(index++);
        }
        for (int i = 0; i < guests; i++) {
            this.summonGuest(index++);
        }
    }

    private void summonGoat(int index) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        BlackGoatServantEntity goat = ModEntities.BLACK_GOAT_SERVANT.get().create(level);
        if (goat == null) {
            return;
        }
        Vec3 spawn = this.ringPosition(index);
        goat.moveTo(spawn.x, spawn.y, spawn.z, this.random.nextFloat() * 360.0f, 0.0f);
        goat.setSummoner(this);
        goat.setTarget(this.findCombatTarget());
        goat.addTag(SacrificeLedger.TAG_SACRIFICE);
        goat.setCustomName(Component.translatable("epitaph.galboss.goat_name"));
        goat.setCustomNameVisible(false);
        level.addFreshEntity(goat);
        GoldenButterflyBurst.burstAtChest(level, spawn);
    }

    private void summonGuest(int index) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        EntityType<?> type = GUEST_POOL[this.random.nextInt(GUEST_POOL.length)];
        Entity created = type.create(level);
        if (!(created instanceof Mob guest)) {
            return;
        }
        Vec3 spawn = this.ringPosition(index);
        guest.moveTo(spawn.x, spawn.y, spawn.z, this.random.nextFloat() * 360.0f, 0.0f);
        guest.addTag(SacrificeLedger.TAG_SACRIFICE);
        guest.setPersistenceRequired();
        guest.setCustomName(Component.translatable("epitaph.galboss.guest_name", type.getDescription()));
        guest.setCustomNameVisible(false);
        guest.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        LivingEntity target = this.findCombatTarget();
        if (target != null) {
            guest.setTarget(target);
        }
        level.addFreshEntity(guest);
        GoldenButterflyBurst.burstAtChest(level, spawn);
    }

    private Vec3 ringPosition(int index) {
        double angle = (Math.PI * 2.0 * index) / Math.max(1, TOTAL_SACRIFICES)
                + this.random.nextDouble() * 0.25;
        double distance = 3.0 + (index % 3) * 2.2 + this.random.nextDouble() * 0.8;
        return new Vec3(this.getX() + Math.cos(angle) * distance,
                this.getY(),
                this.getZ() + Math.sin(angle) * distance);
    }

    private List<Entity> aliveSacrifices() {
        AABB box = this.getBoundingBox().inflate(ARENA_RADIUS);
        return this.level().getEntitiesOfClass(Entity.class, box,
                e -> e.isAlive() && e.getTags().contains(SacrificeLedger.TAG_SACRIFICE));
    }

    private int countSacrifices() {
        return this.aliveSacrifices().size();
    }

    private void replenishSacrifices() {
        if (!this.ritualActive || this.awakened) {
            return;
        }
        int needed = TOTAL_SACRIFICES - this.kills;
        if (needed <= 0) {
            return;
        }
        if (this.level() instanceof ServerLevel level && level.getNearestPlayer(this, ARENA_RADIUS) == null) {
            return;
        }
        int missing = needed - this.countSacrifices();
        if (missing <= 0) {
            return;
        }
        int batch = Math.min(REPLENISH_BATCH, missing);
        int start = this.countSacrifices();
        for (int i = 0; i < batch; i++) {
            if (this.random.nextFloat() < 0.72f) {
                this.summonGoat(start + i);
            } else {
                this.summonGuest(start + i);
            }
        }
    }

    // ------------------------------------------------------------------ 献祭推进

    public void onSacrificeDied(Entity dead) {
        if (this.level().isClientSide || !this.ritualActive) {
            return;
        }
        this.kills = Math.min(TOTAL_SACRIFICES, this.kills + 1);
        this.advanceNights(false);
        this.updateBossBar();

        String truth = "redtruth.galboss.truth." + (1 + this.random.nextInt(3));
        if (this.kills == 1) {
            this.redTruth("redtruth.galboss.kinzo_dead");
            this.queueRedTruth(truth, 50);
        } else {
            this.redTruth(truth);
        }

        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        // 祭品化为魂火与黄金蝶飞向魔女
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                dead.getX(), dead.getY() + 0.8, dead.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
        level.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                dead.getX(), dead.getY() + 0.9, dead.getZ(), 4, 0.0, 0.0, 0.0, 0.0);
    }

    private void advanceNights(boolean quiet) {
        while (this.night < NIGHT_KILLS.length && this.kills >= NIGHT_KILLS[this.night]) {
            this.night++;
            this.onNightEntered(this.night, quiet);
        }
    }

    private void onNightEntered(int newNight, boolean quiet) {
        if (newNight <= 0 || newNight > NIGHT_KILLS.length) {
            return;
        }
        if (newNight < 10) {
            if (!quiet) {
                this.say("epitaph.galboss.night." + newNight, ChatFormatting.DARK_RED);
            }
            this.say("epitaph.galboss.night_name." + newNight, ChatFormatting.GOLD);
        }

        if (newNight == 3) {
            this.enrageSacrifices();
            if (!quiet) {
                this.say("epitaph.galboss.enrage", ChatFormatting.DARK_RED);
                if (this.level() instanceof ServerLevel server) {
                    PixelfxFx.play(server, this.position().add(0.0, 0.05, 0.0),
                            PixelfxSlugs.TOHKA_BELL_WAVE, 2.0f, 0.0f);
                }
            }
            return;
        }
        if (newNight == 9) {
            this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, Integer.MAX_VALUE, 1, false, false, true));
            this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 1, false, false, true));
            if (!this.awakenedAnnounced) {
                this.awakenedAnnounced = true;
                if (!quiet) {
                    this.say("epitaph.galboss.awaken", ChatFormatting.DARK_RED);
                    this.triggerAnim(CONTROLLER, "cast_area");
                    this.playSoundAt(SoundEvents.WITHER_SPAWN, 1.6f, 0.7f);
                    if (this.level() instanceof ServerLevel server) {
                        PixelfxFx.play(server, this.position().add(0.0, 1.2, 0.0),
                                PixelfxSlugs.EPITAPH_AEGIS, 0.55f, 0.0f);
                        server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                                this.getX(), this.getY() + 1.2, this.getZ(), 20, 1.5, 1.0, 1.5, 0.04);
                        SpellFx.shake(server, this.position(), 40.0, 0.6f, 10);
                    }
                }
            }
            return;
        }
        if (newNight == NIGHT_KILLS.length) {
            this.awaken(quiet);
            return;
        }
        BodySeal.Seal seal = NIGHT_SEAL[newNight - 1];
        if (seal != null) {
            BodySeal.seal(seal);
            if (!quiet) {
                this.say("epitaph.galboss.seal.line", ChatFormatting.DARK_PURPLE, seal.displayName());
                this.say("epitaph.galboss.seal.apply", ChatFormatting.RED, seal.displayName());
                if (this.level() instanceof ServerLevel server) {
                    GoldenButterflyBurst.burstAtChest(server, this.position());
                    for (ServerPlayer p : server.players()) {
                        if (p.distanceTo(this) <= ARENA_RADIUS && !p.isSpectator()) {
                            server.sendParticles(ParticleTypes.WITCH,
                                    p.getX(), p.getY() + 1.0, p.getZ(), 12, 0.4, 0.5, 0.4, 0.02);
                        }
                    }
                }
                switch (seal) {
                    case CHEST -> this.say("epitaph.galboss.hint.chest", ChatFormatting.GRAY);
                    case BELLY -> this.say("epitaph.galboss.hint.belly", ChatFormatting.GRAY);
                    case KNEE -> this.say("epitaph.galboss.hint.knee", ChatFormatting.GRAY);
                    default -> {
                    }
                }
            }
        }
    }

    private void enrageSacrifices() {
        for (Entity sacrifice : this.aliveSacrifices()) {
            if (sacrifice instanceof LivingEntity living) {
                living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60 * 30, 0, false, true, true));
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60 * 30, 0, false, true, true));
                if (this.level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.WITCH,
                            living.getX(), living.getY() + 0.9, living.getZ(), 10, 0.35, 0.4, 0.35, 0.02);
                }
            }
        }
    }

    private void awaken(boolean quiet) {
        if (this.awakened) {
            return;
        }
        this.awakened = true;
        BodySeal.clear();
        this.setHealth(this.getMaxHealth());
        this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, Integer.MAX_VALUE, 1, false, false, true));
        this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 1, false, false, true));
        this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 0, false, false, true));
        this.butterflyVolley.reset(VOLLEY_FIRST_DELAY);

        if (quiet) {
            return;
        }
        this.say("epitaph.galboss.revive", ChatFormatting.DARK_RED);
        this.triggerAnim(CONTROLLER, "cast_area");
        this.playSoundAt(SoundEvents.WITHER_SPAWN, 2.0f, 0.6f);

        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        // 黄金乡开启前的全屏黄金蝶爆发与震屏
        GoldenButterflyBurst.burstAtChest(level, this.position());
        PixelfxFx.play(level, this.position().add(0.0, 0.05, 0.0),
                PixelfxSlugs.TOHKA_BELL_WAVE, 2.6f, 0.0f);
        SpellFx.shake(level, this.position(), 48.0, 0.95f, 14);

    }

    private boolean tryEnterGoldenLand(ServerLevel level) {
        if (!this.isAlive() || this.countSacrifices() != 0) {
            return false;
        }
        MinecraftServer server = level.getServer();
        List<ServerPlayer> participants = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            if (player.distanceTo(this) <= ARENA_RADIUS) {
                participants.add(player);
            }
        }
        ServerLevel golden = GoldenLand.level(server);
        if (participants.isEmpty() || golden == null) {
            return false;
        }
        GoldenLand.prepare(golden);
        return this.followIntoGoldenLand(golden, participants);
    }

    private boolean followIntoGoldenLand(ServerLevel golden, List<ServerPlayer> participants) {
        BeatriceEntity moved = ModEntities.BEATRICE.get().create(golden);
        if (moved == null) {
            return false;
        }
        Vec3 beatriceDest = new Vec3(GoldenLand.SPAWN.x + 4.0, GoldenLand.SPAWN.y, GoldenLand.SPAWN.z);
        moved.moveTo(beatriceDest.x, beatriceDest.y, beatriceDest.z, this.getYRot(), 0.0f);
        moved.setYHeadRot(this.getYHeadRot());
        moved.ritualActive = true;
        moved.kills = this.kills;
        moved.night = this.night;
        moved.awakened = this.awakened;
        moved.awakenedAnnounced = this.awakenedAnnounced;
        moved.butterflyVolley.reset(VOLLEY_FIRST_DELAY);
        moved.setHealth(moved.getMaxHealth());
        moved.bossEvent.setVisible(true);
        if (!golden.addFreshEntity(moved)) {
            return false;
        }
        MinecraftServer server = golden.getServer();
        if (!GoldenLand.sendPlayers(server, participants)) {
            moved.discard();
            return false;
        }
        GoldenButterflyBurst.burstAtChest(golden, beatriceDest);
        if (server != null) {
            BodySeal.bind(server, moved.getUUID());
        }
        for (ServerPlayer player : golden.players()) {
            if (player.distanceTo(moved) <= ARENA_RADIUS) {
                moved.bossEvent.addPlayer(player);
            }
        }
        this.discard();
        return true;
    }

    // ------------------------------------------------------------------ 黄金蝶齐射（折扇展开）

    public void volley(int count) {
        if (this.level().isClientSide) {
            return;
        }
        LivingEntity target = this.findCombatTarget();
        int n = Math.max(1, Math.min(12, count));
        Vec3 look = this.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0, look.z);
        if (flatLook.lengthSqr() < 1.0E-4) {
            flatLook = new Vec3(0.0, 0.0, 1.0);
        } else {
            flatLook = flatLook.normalize();
        }
        Vec3 side = new Vec3(-flatLook.z, 0.0, flatLook.x);

        Vec3 muzzleCenter = this.position()
                .add(look.scale(1.2))
                .add(0.0, this.getEyeHeight() - 0.25, 0.0);

        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ModParticles.BURST_STAR.get(),
                    muzzleCenter.x, muzzleCenter.y, muzzleCenter.z, 1, 0.05, 0.05, 0.05, 0.0);
        }

        for (int i = 0; i < n; i++) {
            ButterflyMissileEntity missile = ModEntities.BUTTERFLY_MISSILE.get().create(this.level());
            if (missile == null) {
                continue;
            }
            double norm = n == 1 ? 0.0 : ((i / (double) (n - 1)) * 2.0 - 1.0);
            double offset = norm * (0.38 * Math.min(n - 1, 4) * 0.5);
            Vec3 spawn = muzzleCenter.add(side.scale(offset));

            // 折扇式初始扩散矢量：先向左右及微上方展开弧线，随后由 ButterflyMissileEntity 弯曲收束追踪
            double fanLateral = norm * 0.46;
            double fanVertical = 0.10 + (1.0 - Math.abs(norm)) * 0.08;
            Vec3 initialSpread = side.scale(fanLateral).add(0.0, fanVertical, 0.0);

            missile.moveTo(spawn.x, spawn.y, spawn.z, this.getYRot(), this.getXRot());
            missile.launch(this, target, 0.85 + this.random.nextDouble() * 0.30, initialSpread);
            this.level().addFreshEntity(missile);
        }
        this.triggerAnim(CONTROLLER, "cast");
        this.playSoundAt(SoundEvents.EVOKER_CAST_SPELL, 1.2f, 1.35f);
    }

    private void tickAreaSpells() {
        if (--this.areaSpellCd > 0) {
            return;
        }
        LivingEntity target = this.findCombatTarget();
        if (target == null) {
            this.areaSpellCd = 1;
            return;
        }
        Vec3 at = target.position();
        switch (this.random.nextInt(3)) {
            case 0 -> ChessboardJudgementSpell.cast(this.level(), at, this);
            case 1 -> GoldenRainSpell.cast(this.level(), at, this);
            default -> WitchDomainSpell.cast(this.level(), at, this);
        }
        this.triggerAnim(CONTROLLER, "cast_area");
        this.playSoundAt(SoundEvents.EVOKER_CAST_SPELL, 1.4f, 0.75f);
        this.areaSpellCd = this.awakened ? AREA_SPELL_CD / 2 : AREA_SPELL_CD;
    }

    /**
     * 场地魔法：以自己为圆心铺一层持续翻转的棋盘。
     *
     * <p>和 {@link #tickAreaSpells()} 分开管——场地魔法挂 30 秒，
     * 如果和 16 秒一轮的领域法术共用冷却会重叠成两层棋盘，玩家根本没法走位。
     */
    private void tickFieldSpell() {
        if (--this.fieldSpellCd > 0) {
            return;
        }
        if (this.findCombatTarget() == null) {
            this.fieldSpellCd = 1;
            return;
        }
        ChessboardFieldSpell.cast(this.level(), this.position(), this);
        this.triggerAnim(CONTROLLER, "cast_area");
        this.playSoundAt(SoundEvents.EVOKER_CAST_SPELL, 1.5f, 0.6f);
        this.fieldSpellCd = FIELD_SPELL_CD;
    }

    private static final class ButterflyVolleySkill {

        private static final int COOLDOWN = 12 * 20;
        private static final int TELEGRAPH = 20;
        private static final int SECOND_WAVE_DELAY = 12;
        private static final int RECOVERY = 30;

        private int cooldown;
        private int elapsed = -1;
        private int waves;

        int cooldown() {
            return Math.max(0, this.cooldown);
        }

        void setCooldown(int ticks) {
            this.cooldown = Math.max(0, ticks);
        }

        void reset(int cooldownTicks) {
            this.elapsed = -1;
            this.waves = 0;
            this.cooldown = Math.max(0, cooldownTicks);
        }

        void tick(BeatriceEntity boss) {
            if (this.elapsed < 0) {
                if (this.cooldown > 0) {
                    this.cooldown--;
                    return;
                }
                if (!boss.ritualActive || !boss.hasNearbyTarget()) {
                    return;
                }
                start(boss);
            }
            advance(boss);
        }

        private void start(BeatriceEntity boss) {
            this.elapsed = 0;
            this.waves = 0;
            boss.triggerAnim(CONTROLLER, "cast");
            boss.level().playSound(null, boss.getX(), boss.getY(), boss.getZ(),
                    SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.4f, 1.5f);
        }

        private void advance(BeatriceEntity boss) {
            int t = this.elapsed++;
            if (t < TELEGRAPH) {
                if (t % 4 == 0) {
                    BossTelegraph.circle(boss.level(), boss.position(), 1.2 + t * 0.12,
                            BossTelegraph.VIOLET);
                }
                // 蓄力期在身前汇聚盘旋黄金蝶与星屑，预告即将齐射
                if (t % 4 == 0 && boss.level() instanceof ServerLevel server) {
                    Vec3 look = boss.getLookAngle();
                    double angle = t * 0.55;
                    double swirlR = Mth.lerp(t / (float) TELEGRAPH, 1.2, 0.25);
                    double sx = boss.getX() + look.x * 1.15 + Math.cos(angle) * swirlR;
                    double sy = boss.getY() + boss.getEyeHeight() - 0.15 + Math.sin(angle * 1.5) * 0.25;
                    double sz = boss.getZ() + look.z * 1.15 + Math.sin(angle) * swirlR;
                    server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                            sx, sy, sz, 1, 0.08, 0.08, 0.08, 0.0);
                    server.sendParticles(ModParticles.BURST_DOT.get(),
                            sx, sy, sz, 1, 0.05, 0.05, 0.05, 0.01);
                }
                return;
            }
            if (this.waves == 0) {
                this.waves = 1;
                boss.volley(boss.awakened ? 5 : VOLLEY_COUNT);
            } else if (this.waves == 1 && t >= TELEGRAPH + SECOND_WAVE_DELAY) {
                this.waves = 2;
                boss.volley(boss.awakened ? 4 : 2);
            }
            if (t > TELEGRAPH + SECOND_WAVE_DELAY + RECOVERY) {
                this.elapsed = -1;
                this.waves = 0;
                this.cooldown = COOLDOWN;
            }
        }
    }

    private static final class SniperSkill {

        private static final int COOLDOWN = 60 * 20;
        private static final int SACRIFICE_LIMIT = 10;
        private static final int LOCK_TICKS = 40;
        private static final int RECOVERY = 14;
        private static final double RANGE = 40.0;
        private static final int BEAM_REFRESH = 5;
        private static final int BEAM_LIFE = 10;

        private int cooldown;
        private int elapsed = -1;
        @Nullable
        private UUID lockedUuid;

        void tick(BeatriceEntity boss) {
            if (this.elapsed < 0) {
                if (this.cooldown > 0) {
                    this.cooldown--;
                    return;
                }
                if (!boss.ritualActive || !available(boss)) {
                    return;
                }
                Player target = pickTarget(boss);
                if (target == null) {
                    return;
                }
                start(boss, target);
            }
            advance(boss);
        }

        private boolean available(BeatriceEntity boss) {
            return boss.ritualActive && boss.kills < SACRIFICE_LIMIT;
        }

        @Nullable
        private Player pickTarget(BeatriceEntity boss) {
            if (!(boss.level() instanceof ServerLevel level)) {
                return null;
            }
            LivingEntity current = boss.getTarget();
            if (current instanceof Player player && player.isAlive() && !player.isSpectator()
                    && !player.isCreative() && boss.distanceTo(player) <= RANGE) {
                return player;
            }
            Player nearest = level.getNearestPlayer(boss, RANGE);
            if (nearest != null && nearest.isAlive() && !nearest.isSpectator() && !nearest.isCreative()) {
                return nearest;
            }
            return null;
        }

        private void start(BeatriceEntity boss, Player target) {
            this.elapsed = 0;
            this.lockedUuid = target.getUUID();
            boss.triggerAnim(CONTROLLER, "cast");
            boss.playSoundAt(SoundEvents.ARROW_SHOOT, 1.4f, 1.6f);
            ModNetwork.sendToPlayer(target, S2CSniperLockPacket.lock(LOCK_TICKS));
            sendBeam(boss, target);
        }

        private void advance(BeatriceEntity boss) {
            int t = this.elapsed++;
            Player target = this.resolveLocked(boss);

            if (t < LOCK_TICKS) {
                if (target != null) {
                    if (t % BEAM_REFRESH == 0) {
                        sendBeam(boss, target);
                    }
                    boss.getLookControl().setLookAt(target, 60.0f, 60.0f);
                }
                return;
            }
            if (t == LOCK_TICKS) {
                if (target != null) {
                    fire(boss, target);
                } else {
                    this.releaseLock(boss);
                }
            }
            if (t > LOCK_TICKS + RECOVERY) {
                this.elapsed = -1;
                this.lockedUuid = null;
                this.cooldown = boss.awakened ? COOLDOWN / 2 : COOLDOWN;
            }
        }

        private void fire(BeatriceEntity boss, Player target) {
            SniperArrowEntity arrow = ModEntities.SNIPER_ARROW.get().create(boss.level());
            if (arrow == null) {
                this.releaseLock(boss);
                return;
            }
            arrow.launch(boss, target);
            boss.level().addFreshEntity(arrow);
            boss.playSoundAt(SoundEvents.CROSSBOW_SHOOT, 1.6f, 1.5f);
            this.releaseLock(boss);
        }

        private void releaseLock(BeatriceEntity boss) {
            Player target = this.resolveLocked(boss);
            if (target != null) {
                ModNetwork.sendToPlayer(target, S2CSniperLockPacket.release());
            }
        }

        @Nullable
        private Player resolveLocked(BeatriceEntity boss) {
            if (this.lockedUuid == null || boss.getServer() == null) {
                return null;
            }
            Player player = boss.getServer().getPlayerList().getPlayer(this.lockedUuid);
            if (player != null && player.isAlive() && !player.isSpectator()) {
                return player;
            }
            return null;
        }

        private void sendBeam(BeatriceEntity boss, Player target) {
            Vec3 from = boss.getEyePosition().subtract(0.0, 0.15, 0.0);
            Vec3 to = target.getEyePosition().subtract(0.0, 0.35, 0.0);
            ModNetwork.sendSniperBeam(boss.level(), from, to, ARENA_RADIUS, BEAM_LIFE);
        }
    }

    public boolean debugSnipe(Player target) {
        if (this.level().isClientSide || target == null || this.sniper.elapsed >= 0) {
            return false;
        }
        this.sniper.start(this, target);
        return true;
    }

    // ------------------------------------------------------------------ 受击 / 交互 / 死亡

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return super.hurt(source, amount);
        }
        Entity attacker = source.getEntity();
        if (!this.ritualActive && attacker instanceof Player) {
            this.startRitual();
            return super.hurt(source, amount);
        }
        // 只要进入战斗（仪式已开始）就启用瞬移闪避，不再等真复苏。
        // 放在免疫判定之前：躲掉的话连「免疫」提示都不用弹，画面反馈更直接。
        if (this.ritualActive && this.tryDodgeMelee(source, attacker)) {
            return false;
        }
        if (this.ritualActive && !this.awakened) {
            if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                return super.hurt(source, amount);
            }
            this.immuneHint();
            return false;
        }
        // 真复苏后闪避成功 = 这一刀彻底落空（伤害不结算）
        if (this.tryDodgeMelee(source, attacker)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------------ 近战闪避瞬移实现

    /**
     * 近战打过来时瞬移躲开。返回 true 表示已经躲掉，调用方必须丢弃这次伤害。
     *
     * <p>只对「直接接触型」攻击生效：弹射物（箭/蝶）走 {@link DamageTypeTags#IS_PROJECTILE}
     * 分支直接放行，间接伤害（如法术）也放行——只躲刀剑和近身爪击。
     */
    private boolean tryDodgeMelee(DamageSource source, @Nullable Entity attacker) {
        if (this.dodgeCd > 0) {
            this.dodgeLog("冷却中 (" + this.dodgeCd + " tick)");
            return false;
        }
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel server)) {
            this.dodgeLog("非服务端");
            return false;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            this.dodgeLog("弹射物，不躲");
            return false;
        }
        // 直接伤害：命中体就是攻击者本人（弹射物的命中体是箭，不是人）
        if (!(source.getDirectEntity() instanceof LivingEntity melee) || melee == this) {
            this.dodgeLog("直接命中体不是生物: " + source.getDirectEntity());
            return false;
        }
        if (melee.isSpectator() || !melee.isAlive()) {
            this.dodgeLog("攻击者无效");
            return false;
        }
        // 注意：这里**不能**过滤创造模式玩家。
        // 创造模式砍过来照样该躲——这是演出，不是伤害判定；
        // 而且开发时几乎都在创造模式里测，过滤掉等于永远看不到瞬移。
        double gap = this.distanceTo(melee);
        if (gap > DODGE_TRIGGER_RANGE) {
            this.dodgeLog(String.format("距离 %.2f > %.2f", gap, DODGE_TRIGGER_RANGE));
            return false;
        }
        if (this.random.nextFloat() >= DODGE_CHANCE) {
            this.dodgeLog("概率未通过");
            return false;
        }
        if (!this.teleportAwayFrom(server, melee)) {
            this.dodgeLog("找不到安全落点");
            return false;
        }
        this.dodgeCd = DODGE_COOLDOWN;
        this.dodgeLog("成功，冷却 " + DODGE_COOLDOWN);
        return true;
    }

    /**
     * 诊断用：前若干次闪避判定把结论打进日志，之后静默。
     *
     * <p>闪避依赖游戏内状态（距离、命中体类型、落点是否可站），这些在代码里看不出来，
     * 只能靠日志定位。预算用完就不再刷屏。
     */
    private void dodgeLog(String reason) {
        if (DODGE_DEBUG_BUDGET > 0) {
            DODGE_DEBUG_BUDGET--;
            com.example.galboss.GalBoss.LOGGER.info("[Beatrice] 闪避判定: {}", reason);
        }
    }

    /**
     * 找一个安全落点并瞬移过去。
     *
     * <p>先试 {@link LivingEntity#randomTeleport(double, double, double, boolean)}——它会从给定的 Y
     * 往下找第一块有碰撞的地板，失败会自己还原位置并返回 false。
     *
     * <p>但它要求「落点所在的整块碰撞箱都不挡」，在竞技场围墙边经常 12 次全失败。
     * 所以再兜一层 {@link #findSafeSpot}：手写地形扫描，只要脚下有实体方块、站位两格空着就算数。
     */
    private boolean teleportAwayFrom(ServerLevel server, LivingEntity threat) {
        Vec3 from = this.position();
        Vec3 threatPos = threat.position();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0;
            double distance = DODGE_MIN_DISTANCE
                    + this.random.nextDouble() * (DODGE_MAX_DISTANCE - DODGE_MIN_DISTANCE);
            double tx = from.x + Math.cos(angle) * distance;
            double tz = from.z + Math.sin(angle) * distance;

            // 别瞬移到攻击者脸上，否则等于没躲
            if (threatPos.distanceToSqr(tx, from.y, tz) < DODGE_KEEP_AWAY * DODGE_KEEP_AWAY) {
                continue;
            }
            if (this.randomTeleport(tx, from.y, tz, false)) {
                this.playDodgeFx(server, from, this.position());
                return true;
            }
        }

        // 兜底：随机落点全失败时，按固定角度环扫一圈找可站立的地面
        Vec3 safe = this.findSafeSpot(server, from, threatPos);
        if (safe == null) {
            return false;
        }
        this.teleportTo(safe.x, safe.y, safe.z);
        this.playDodgeFx(server, from, this.position());
        return true;
    }

    /**
     * 环形扫描找可站立的位置：脚下要有碰撞方块，站位与头顶两格必须空。
     *
     * <p>不做 {@code noCollision} 的整体碰撞检查——那正是 {@code randomTeleport} 在围墙边失败的原因。
     * 这里只要求「人能站」，对瞬移来说足够了。
     */
    @Nullable
    private Vec3 findSafeSpot(ServerLevel server, Vec3 from, Vec3 threatPos) {
        for (double radius = DODGE_MIN_DISTANCE; radius <= DODGE_MAX_DISTANCE; radius += 1.0) {
            for (int i = 0; i < 16; i++) {
                double angle = (Math.PI * 2.0 * i) / 16.0;
                double tx = from.x + Math.cos(angle) * radius;
                double tz = from.z + Math.sin(angle) * radius;
                if (threatPos.distanceToSqr(tx, from.y, tz) < DODGE_KEEP_AWAY * DODGE_KEEP_AWAY) {
                    continue;
                }
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos feet = BlockPos.containing(tx, from.y + dy, tz);
                    if (!server.hasChunkAt(feet)) {
                        continue;
                    }
                    if (!server.getBlockState(feet.below()).blocksMotion()) {
                        continue;
                    }
                    if (!server.getBlockState(feet).getCollisionShape(server, feet).isEmpty()) {
                        continue;
                    }
                    BlockPos head = feet.above();
                    if (!server.getBlockState(head).getCollisionShape(server, head).isEmpty()) {
                        continue;
                    }
                    return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                }
            }
        }
        return null;
    }

    /** 起点与落点各来一轮黄金蝶，配合低频传送音。 */
    private void playDodgeFx(ServerLevel server, Vec3 from, Vec3 to) {
        GoldenButterflyBurst.burstAtChest(server, from);
        GoldenButterflyBurst.burstAtChest(server, to);
        server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                from.x, from.y + 1.0, from.z, 10, 0.5, 0.9, 0.5, 0.03);
        server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                to.x, to.y + 1.0, to.z, 10, 0.5, 0.9, 0.5, 0.03);
        server.playSound(null, from.x, from.y, from.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9f, 1.55f);
        server.playSound(null, to.x, to.y, to.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9f, 1.55f);
        server.playSound(null, to.x, to.y, to.z,
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 0.7f, 1.9f);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!this.ritualActive) {
            this.startRitual();
            return InteractionResult.SUCCESS;
        }
        if (!this.awakened) {
            this.immuneHint();
        }
        return InteractionResult.SUCCESS;
    }

    private void immuneHint() {
        if (this.immuneHintCd > 0) {
            return;
        }
        this.immuneHintCd = 40;
        this.say("epitaph.galboss.immune", ChatFormatting.DARK_GRAY);
        this.playSoundAt(SoundEvents.SHIELD_BLOCK, 1.0f, 0.7f);
        if (this.level() instanceof ServerLevel server) {
            Vec3 chest = this.position().add(0.0, 1.15, 0.0);
            server.sendParticles(ModParticles.BURST_RING.get(), chest.x, chest.y, chest.z, 1, 0, 0, 0, 0);
            server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), chest.x, chest.y, chest.z, 4, 0, 0, 0, 0);
        }
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            this.bossEvent.removeAllPlayers();
            this.bossEvent.setVisible(false);
            if (this.ritualActive) {
                this.night = 10;
                this.say("epitaph.galboss.night.10", ChatFormatting.DARK_RED);
                this.redTruth("redtruth.galboss.victory");
                this.say("epitaph.galboss.victory", ChatFormatting.GOLD);
            }
            if (this.level() instanceof ServerLevel server) {
                // 败北化作满天黄金蝶群消散
                Vec3 chest = this.position().add(0.0, 1.1, 0.0);
                PixelfxFx.play(server, this.position().add(0.0, 0.06, 0.0),
                        PixelfxSlugs.TOHKA_BELL_WAVE, 2.4f, 0.0f);
                server.sendParticles(ModParticles.BURST_STAR.get(), chest.x, chest.y, chest.z, 3, 0.3, 0.3, 0.3, 0.0);
                server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), chest.x, chest.y, chest.z, 16, 0.5, 0.6, 0.5, 0.0);
                server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(), chest.x, chest.y, chest.z, 24, 1.4, 1.2, 1.4, 0.03);
                SpellFx.shake(server, this.position(), 40.0, 0.7f, 12);
            }
            BodySeal.clear();
            this.dropTreasures();
            this.triggerAnim(CONTROLLER, "death");
        }
        super.die(source);
    }

    private void dropTreasures() {
        this.dropTreasure(new ItemStack(Items.GOLD_BLOCK, 64));
        this.dropTreasure(new ItemStack(Items.TOTEM_OF_UNDYING));
        this.dropTreasure(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
        this.dropTreasure(new ItemStack(ModItems.BEATRICE_PIPE.get()));
    }

    private void dropTreasure(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity item = new ItemEntity(this.level(), this.getX(), this.getY() + 0.6, this.getZ(), stack);
        item.setDefaultPickUpDelay();
        double angle = this.random.nextDouble() * Math.PI * 2.0;
        item.setDeltaMovement(Math.cos(angle) * 0.16, 0.28, Math.sin(angle) * 0.16);
        this.level().addFreshEntity(item);
    }

    // ------------------------------------------------------------------ Boss 血条 / HUD

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (this.ritualActive) {
            this.bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    private void updateBossBar() {
        if (!this.ritualActive) {
            return;
        }
        // 进度条改成「岛上还剩几个人」——碑文设定的十八人，死一个少一个。
        this.bossEvent.setName(Component.translatable("epitaph.galboss.bar", this.peopleRemaining()));
        if (this.awakened) {
            // 真复苏后所有人都已死光，剩几个人已经没信息量了，切回血量条
            float ratio = this.getMaxHealth() <= 0.0f ? 0.0f : this.getHealth() / this.getMaxHealth();
            this.bossEvent.setProgress(Math.max(0.0f, Math.min(1.0f, ratio)));
        } else {
            this.bossEvent.setProgress(Math.min(1.0f, this.peopleRemaining() / (float) TOTAL_PEOPLE));
        }
    }

    /** 岛上还剩几个人：十八人 - 已献祭数（贝阿朵莉切本人也算一个）。 */
    public int peopleRemaining() {
        return Math.max(0, TOTAL_PEOPLE - this.kills);
    }

    public Component hudLine() {
        return Component.translatable("epitaph.galboss.hud",
                this.nightName(), this.kills, TOTAL_SACRIFICES, BodySeal.describe());
    }

    private Component nightName() {
        int n = Math.max(0, Math.min(10, this.night));
        return Component.translatable("epitaph.galboss.night_name." + n);
    }

    // ------------------------------------------------------------------ 播报小工具

    private void say(String key, ChatFormatting color, Object... args) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        Component message = args.length == 0
                ? Component.translatable(key)
                : Component.translatable(key, args);
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(this) <= ARENA_RADIUS) {
                player.sendSystemMessage(message.copy().withStyle(color));
            }
        }
    }

    private void redTruth(String key) {
        ModNetwork.sendRedTruthKey(this.level(), this.position(), ARENA_RADIUS, key, 60);
    }

    private void queueRedTruth(String key, int delayTicks) {
        this.pendingTruthKey = key;
        this.pendingTruthTicks = Math.max(1, delayTicks);
    }

    private void playSoundAt(net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), event, SoundSource.HOSTILE, volume, pitch);
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("EpitaphActive", this.ritualActive);
        tag.putBoolean("EpitaphAwakened", this.awakened);
        tag.putInt("EpitaphKills", this.kills);
        tag.putInt("EpitaphNight", this.night);
        tag.putInt("EpitaphVolleyCd", this.butterflyVolley.cooldown());
        ListTag seals = new ListTag();
        for (BodySeal.Seal seal : BodySeal.snapshot()) {
            seals.add(StringTag.valueOf(seal.key()));
        }
        tag.put("EpitaphSeals", seals);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.ritualActive = tag.getBoolean("EpitaphActive");
        this.awakened = tag.getBoolean("EpitaphAwakened");
        this.kills = Math.max(0, Math.min(TOTAL_SACRIFICES, tag.getInt("EpitaphKills")));
        this.night = Math.max(0, Math.min(10, tag.getInt("EpitaphNight")));
        this.butterflyVolley.reset(VOLLEY_FIRST_DELAY);
        if (tag.contains("EpitaphVolleyCd")) {
            this.butterflyVolley.setCooldown(tag.getInt("EpitaphVolleyCd"));
        }
        this.awakenedAnnounced = this.night >= 9;
        this.bossEvent.setVisible(this.ritualActive);
        if (!this.ritualActive || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        EnumSet<BodySeal.Seal> restored = EnumSet.noneOf(BodySeal.Seal.class);
        ListTag seals = tag.getList("EpitaphSeals", 8);
        for (int i = 0; i < seals.size(); i++) {
            String key = seals.getString(i);
            for (BodySeal.Seal seal : BodySeal.Seal.values()) {
                if (seal.key().equals(key)) {
                    restored.add(seal);
                }
            }
        }
        BodySeal.restore(server, this.getUUID(), restored);
        this.updateBossBar();
    }

    // ------------------------------------------------------------------ 调试入口

    public boolean isRitualActive() {
        return this.ritualActive;
    }

    public int getRitualNight() {
        return this.night;
    }

    public int getSacrifices() {
        return this.kills;
    }

    public void debugStartRitual() {
        if (this.ritualActive) {
            this.resetRitual();
        }
        this.startRitual();
    }

    private void resetRitual() {
        for (Entity sacrifice : this.aliveSacrifices()) {
            sacrifice.discard();
        }
        this.ritualActive = false;
        this.kills = 0;
        this.night = 0;
        this.awakened = false;
        this.awakenedAnnounced = false;
        this.butterflyVolley.reset(VOLLEY_FIRST_DELAY);
        this.replenishTimer = REPLENISH_INTERVAL;
        this.removeAllEffects();
        this.setHealth(this.getMaxHealth());
        BodySeal.clear();
    }

    public void debugJumpToKills(int kills) {
        if (!this.ritualActive) {
            this.startRitual();
        }
        this.kills = Math.max(0, Math.min(TOTAL_SACRIFICES, kills));
        this.night = 0;
        this.awakened = false;
        this.awakenedAnnounced = false;
        BodySeal.clear();
        if (this.level() instanceof ServerLevel level && level.getServer() != null) {
            BodySeal.bind(level.getServer(), this.getUUID());
        }
        this.advanceNights(true);
        this.updateBossBar();
    }

    public void debugLaunchButterflies(int count) {
        this.volley(count);
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<BeatriceEntity> controller =
                new AnimationController<>(this, CONTROLLER, 4, this::predicate);
        for (String name : TRIGGERED_ANIMS) {
            controller.triggerableAnim(name, RawAnimation.begin()
                    .then(ANIM_PREFIX + name, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(controller.receiveTriggeredAnimations());
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        if (this.isDeadOrDying()) {
            if (!state.getController().isPlayingTriggeredAnimation()) {
                state.getController().setAnimation(
                        RawAnimation.begin().then(ANIM_PREFIX + "death", Animation.LoopType.PLAY_ONCE));
            }
            return PlayState.CONTINUE;
        }
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
