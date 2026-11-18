package com.example.galboss.entity;

import com.example.galboss.boss.BossTelegraph;
import com.example.galboss.item.ModItems;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.network.S2CSniperLockPacket;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.ritual.BodySeal;
import com.example.galboss.ritual.GoldenLand;
import com.example.galboss.ritual.SacrificeLedger;
import com.example.galboss.spell.ChessboardJudgementSpell;
import com.example.galboss.spell.GoldenRainSpell;
import com.example.galboss.spell.WitchDomainSpell;
import net.minecraft.ChatFormatting;
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
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
/**
 * 贝阿朵莉切 —— 黄金魔女 / 「黄金碑文」遭遇的主宰者。
 *
 * <p>这一版是<b>完整遭遇</b>，不是占位：所有推进逻辑都在本类，配套件各管一段：
 * <ul>
 *   <li>{@link SacrificeLedger}：只看「带 {@code galboss_epitaph_sacrifice} 标签的实体死了」，
 *       转手调 {@link #onSacrificeDied(Entity)}；谁杀的、是羊还是原版宾客一概不管。</li>
 *   <li>{@link BodySeal}：玩家身上的五处部位封印（头/胸/腹/膝/足），静态状态 + 事件钩子。</li>
 *   <li>{@link EpitaphHud}：每 0.5 秒刷 actionbar（{@link #hudLine()}）并续持续型 debuff。</li>
 *   <li>{@link GoldenLand}：第九夜把整场遭遇搬进 {@code galboss:golden_land}。</li>
 * </ul>
 *
 * <h2>推进方式：只数「倒下了几个」</h2>
 * <p>旧版按夜分组唤醒 13 只羊、第三夜还要玩家在聊天栏打她的名字，联机时极容易卡死。
 * 现在进度是一个客观整数 {@link #kills}：0→17 共 11 个坎（见 {@link #NIGHT_KILLS}），
 * 每跨过一个坎推进一夜，HUD 与 Boss 血条都直接显示「祭品 x/17」。
 *
 * <h2>她为什么打不动</h2>
 * <p>碑文没走完（{@link #kills} &lt; 17）时她对一切伤害免疫，玩家只会看到
 * {@code epitaph.galboss.immune}（「魔女尚未复苏。」）。第 16 位倒下时她「开始苏醒」——
 * 法术冷却减半 + 力量 II / 速度 II，但仍然打不动；第 17 位倒下才真正解除封印：
 * 回满血、清空全部部位封印、狂暴，并把全体参战玩家（和她自己）搬进黄金乡。
 */
public class BeatriceEntity extends Monster implements GeoEntity {

    private static final String ID = "beatrice";

    /** 碑文总祭品数：13 只黑山羊 + 4 位原版宾客。「十八人」= 17 位祭品 + 玩家自己。 */
    public static final int TOTAL_SACRIFICES = 17;
    private static final int GOAT_COUNT = 13;
    private static final int GUEST_COUNT = 4;

    /** 遭遇半径：与 BodySeal / EpitaphHud 保持一致。 */
    private static final double ARENA_RADIUS = 64.0;

    /** 第 1~10 夜各自需要的献祭数（第 10 夜是她自己倒下）。 */
    private static final int[] NIGHT_KILLS = {6, 8, 10, 11, 12, 13, 14, 15, 16, 17};

    /** 第 4~8 夜剜下的部位（与 NIGHT_KILLS 的下标一一对应）。 */
    private static final BodySeal.Seal[] NIGHT_SEAL = {
            null, null, null, BodySeal.Seal.HEAD, BodySeal.Seal.CHEST,
            BodySeal.Seal.BELLY, BodySeal.Seal.KNEE, BodySeal.Seal.FOOT, null, null
    };

    /** 宾客池：<b>刻意没有苦力怕</b>（自爆会替玩家免费推进碑文，实机验证时真的发生过）。 */
    private static final EntityType<?>[] GUEST_POOL = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK,
            EntityType.STRAY, EntityType.SPIDER, EntityType.WITCH
    };

    // 黄金蝶齐射（技能参数见内部类 ButterflyVolleySkill）
    /** 开场 / 复苏 / 换阶段后的起手延迟：让她先站住摆姿态再出手。 */
    private static final int VOLLEY_FIRST_DELAY = 6 * 20;
    /** 一次齐射的蝶数（常态；复苏后技能内部会加量）。 */
    private static final int VOLLEY_COUNT = 3;

    /** 祭品巡检间隔：场上少了就补召，保证碑文一定数得满 17。 */
    private static final int REPLENISH_INTERVAL = 2 * 20;
    private static final int REPLENISH_BATCH = 4;

    private static final String ANIM_PREFIX = "animation.beatrice.";
    private static final String CONTROLLER = "controller";
    private static final Set<String> TRIGGERED_ANIMS =
            Set.of("cast", "cast_area", "attack", "hurt", "death");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            this.getDisplayName(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    /** 碑文是否已经开场（第一次被玩家打 / 右键）。 */
    private boolean ritualActive;
    /** 已献上的祭品数（0~17）。 */
    private int kills;
    /** 当前夜（0 = 序章，1~10）。 */
    private int night;
    /** 第 17 位祭品倒下后的「真复苏」：解除免疫。 */
    private boolean awakened;

    private int replenishTimer = REPLENISH_INTERVAL;
    private int immuneHintCd;
    private boolean awakenedAnnounced;

    /** 黄金蝶齐射（自带冷却与状态机的技能）。 */
    private final ButterflyVolleySkill butterflyVolley = new ButterflyVolleySkill();

    /** 排队中的红字（见 {@link #queueRedTruth(String, int)}）。 */
    private String pendingTruthKey;
    private int pendingTruthTicks = -1;

    /** 谢斯塔黄金弓狙击术（自带冷却与状态机，规则同蝴蝶齐射）。 */
    private final SniperSkill sniper = new SniperSkill();

    /**
     * 大范围魔法（整区块级）的轮换冷却（tick）。
     *
     * <p>三招共用一条冷却、随机挑一招：{@link ChessboardJudgementSpell}（棋盘审判）、
     * {@link GoldenRainSpell}（金雨）、{@link WitchDomainSpell}（魔女领域）。
     * 复苏（P2）后减半，和蝶群齐射是同一条阶段规则。
     *
     * <p>为什么不塞进 {@code boss.BossSkill} 框架：那套的泛型上界是 {@code AbstractFSBossEntity}
     * （冷却要吃 {@code boss.cd()}），而她 {@code extends Monster} —— 理由和
     * {@link ButterflyVolleySkill} 那段注释一样。而且这几招的演出状态机已经在
     * {@code spell.Spell} 里了，boss 这边只需要决定「什么时候放」。
     */
    private int areaSpellCd = AREA_SPELL_CD;

    /** 大范围魔法的冷却基数（16 秒）。 */
    private static final int AREA_SPELL_CD = 16 * 20;

    /**
     * 和平难度下也不消失（理由见 {@code AbstractFSBossEntity#shouldDespawnInPeaceful}）。
     *
     * <p>她不是 {@code AbstractFSBossEntity} 的子类（{@code extends Monster}），
     * 所以这条要单独写一遍，否则四个人里只有她会在切和平难度时被静默抹掉。
     */
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
                .add(Attributes.MAX_HEALTH, 400.0)
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
        // 只有真复苏之后才会追人打近战 —— 碑文期间她站着看戏
        this.goalSelector.addGoal(2, new AwakenedMeleeGoal());
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 16.0f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /** 近战只在复苏后开放：碑文期间她不该追着玩家打。 */
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

    // ------------------------------------------------------------------ 主循环

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.immuneHintCd > 0) {
            this.immuneHintCd--;
        }
        // 谢斯塔狙击术放在碑文门之前：一旦锁定就必须走完「2 秒 → 光箭」，
        // 不能因为碑文没开 / 被打断就卡在锁定态（调试命令 /galboss sniper 也走这条路）。
        this.sniper.tick(this);

        if (!this.ritualActive) {
            return;
        }

        // 黄金蝶齐射（技能）：预警 → 两波齐射 → 收招，冷却在收起后起算
        this.butterflyVolley.tick(this);

        // 大范围魔法（整区块级）：三招轮换，P2 后冷却减半
        this.tickAreaSpells();

        // 排队中的红字（屏幕单行，金藏那句念完再念「真实」）
        if (this.pendingTruthTicks > 0 && --this.pendingTruthTicks == 0 && this.pendingTruthKey != null) {
            this.redTruth(this.pendingTruthKey);
            this.pendingTruthKey = null;
            this.pendingTruthTicks = -1;
        }

        // 祭品巡检：只在碑文未走完、且还有玩家在战场内时补召
        if (!this.awakened && --this.replenishTimer <= 0) {
            this.replenishTimer = REPLENISH_INTERVAL;
            this.replenishSacrifices();
        }
        this.updateBossBar();
    }

    /** 有没有可以当目标的人（她自己的目标优先，其次 40 格内最近的玩家）。 */
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

    /**
     * 第一次被玩家打 / 右键 → 开碑文。
     *
     * <p>开场是「一次性」的：重复调用直接返回，避免连击第二下就把 17 位祭品召两遍。
     */
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
        }
        this.bossEvent.setVisible(true);
        this.updateBossBar();

        // 碑文原文进聊天栏（2026-11-17 改：原先走屏幕中央红字，会和死亡触发的红字叠在一起）
        this.say("epitaph.galboss.preamble.1", ChatFormatting.DARK_RED);
        this.say("epitaph.galboss.preamble.2", ChatFormatting.DARK_RED);
        this.say("epitaph.galboss.start", ChatFormatting.LIGHT_PURPLE);
        this.summonSacrifices(GOAT_COUNT + GUEST_COUNT);
        this.say("epitaph.galboss.eighteen", ChatFormatting.GOLD);
        this.triggerAnim(CONTROLLER, "cast");
        this.playSoundAt(SoundEvents.EVOKER_PREPARE_SUMMON, 1.4f, 0.8f);
    }

    /** 召一批祭品（黑山羊 + 原版宾客），带祭品标签。 */
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
        // 亡灵白天会被晒死 → 送一顶皮革帽，免得它们自己替玩家推进碑文
        guest.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        LivingEntity target = this.findCombatTarget();
        if (target != null) {
            guest.setTarget(target);
        }
        level.addFreshEntity(guest);
    }

    /** 环形落点：她自己身边 3.0~7.5 格，避免 17 只全叠在一格里。 */
    private Vec3 ringPosition(int index) {
        double angle = (Math.PI * 2.0 * index) / Math.max(1, TOTAL_SACRIFICES)
                + this.random.nextDouble() * 0.25;
        double distance = 3.0 + (index % 3) * 2.2 + this.random.nextDouble() * 0.8;
        return new Vec3(this.getX() + Math.cos(angle) * distance,
                this.getY(),
                this.getZ() + Math.sin(angle) * distance);
    }

    /** 场上还活着的祭品数（同维度、64 格内）。 */
    private List<Entity> aliveSacrifices() {
        AABB box = this.getBoundingBox().inflate(ARENA_RADIUS);
        return this.level().getEntitiesOfClass(Entity.class, box,
                e -> e.isAlive() && e.getTags().contains(SacrificeLedger.TAG_SACRIFICE));
    }

    private int countSacrifices() {
        return this.aliveSacrifices().size();
    }

    /** 巡检：场上祭品少于「还需要倒下的数」就补召，单次最多 4 只。 */
    private void replenishSacrifices() {
        if (!this.ritualActive || this.awakened) {
            return;
        }
        int needed = TOTAL_SACRIFICES - this.kills;
        if (needed <= 0) {
            return;
        }
        // 场上没有玩家就别补：补了也是站着，只会白拉实体
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
            // 前 13 位维持「羊为主」的气质：补召时按缺口多补宾客
            if (this.random.nextFloat() < 0.72f) {
                this.summonGoat(start + i);
            } else {
                this.summonGuest(start + i);
            }
        }
    }

    // ------------------------------------------------------------------ 献祭推进

    /**
     * 一位祭品倒下（由 {@link SacrificeLedger} 转手过来）。
     *
     * <p>只认「数量」：这是碑文的唯一推进方式。
     *
     * <p>红字规则（2026-11-17 按用户要求）：
     * <ul>
     *   <li>第一个祭品倒下 → 「右代宫金藏已死。」（十八人里第一个是他）</li>
     *   <li>每次祭品倒下 → 三句「真实」里随机一句（六轩岛无人、房间无人进出、无机关暗道毒药）</li>
     *   <li>碑文原文本身仍然只进聊天栏，不占屏幕中央</li>
     * </ul>
     */
    public void onSacrificeDied(Entity dead) {
        if (this.level().isClientSide || !this.ritualActive) {
            return;
        }
        this.kills = Math.min(TOTAL_SACRIFICES, this.kills + 1);
        this.advanceNights(false);
        this.updateBossBar();

        // 红字：第一个祭品是金藏；之后每次随机念一句「真实」。
        // 屏幕同时只显示一行，所以第一个祭品要排队：先金藏，2.5 秒后再念「真实」那三句之一，
        // 否则后一句会把前一句当场顶掉，金藏那句等于没出现过。
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
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                dead.getX(), dead.getY() + 0.8, dead.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
    }

    /**
     * 按 {@link #NIGHT_KILLS} 把该推进的夜一次推完（一次死两个也不会漏）。
     *
     * @param quiet debug 用：不播报、不华丽地传送
     */
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
        // 前九夜都要念一句碑文；第十夜是她的死。碑文一律进聊天栏（红字位留给「事实」）
        if (newNight < 10) {
            if (!quiet) {
                this.say("epitaph.galboss.night." + newNight, ChatFormatting.DARK_RED);
            }
            this.say("epitaph.galboss.night_name." + newNight, ChatFormatting.GOLD);
        }

        if (newNight == 3) {
            // 第三夜「高贵之名」：剩下的祭品集体狂暴
            this.enrageSacrifices();
            if (!quiet) {
                this.say("epitaph.galboss.enrage", ChatFormatting.DARK_RED);
            }
            return;
        }
        if (newNight == 9) {
            // 第九夜第一阶段：她开始苏醒（仍然免疫）
            this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, Integer.MAX_VALUE, 1, false, false, true));
            this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 1, false, false, true));
            if (!this.awakenedAnnounced) {
                this.awakenedAnnounced = true;
                if (!quiet) {
                    this.say("epitaph.galboss.awaken", ChatFormatting.DARK_RED);
                    this.triggerAnim(CONTROLLER, "cast_area");
                    this.playSoundAt(SoundEvents.WITHER_SPAWN, 1.6f, 0.7f);
                }
            }
            // 第 17 位（NIGHT_KILLS[9]）在同一夜里触发真复苏
            if (this.kills >= TOTAL_SACRIFICES) {
                this.awaken(quiet);
            }
            return;
        }
        BodySeal.Seal seal = NIGHT_SEAL[newNight - 1];
        if (seal != null) {
            BodySeal.seal(seal);
            if (!quiet) {
                // 「剜去%1$s。」→ 玩家的「你的%1$s被剜去了。」→ 行为型封印的操作提示
                this.say("epitaph.galboss.seal.line", ChatFormatting.DARK_PURPLE, seal.displayName());
                this.say("epitaph.galboss.seal.apply", ChatFormatting.RED, seal.displayName());
                switch (seal) {
                    case CHEST -> this.say("epitaph.galboss.hint.chest", ChatFormatting.GRAY);
                    case BELLY -> this.say("epitaph.galboss.hint.belly", ChatFormatting.GRAY);
                    case KNEE -> this.say("epitaph.galboss.hint.knee", ChatFormatting.GRAY);
                    default -> {
                        // 头（失明）与足（禁足）是持续型 debuff，效果本身就能看懂，不再单独提示
                    }
                }
            }
        }
    }

    /** 第三夜：剩下的祭品一起获得力量 I + 速度 I。 */
    private void enrageSacrifices() {
        for (Entity sacrifice : this.aliveSacrifices()) {
            if (sacrifice instanceof LivingEntity living) {
                living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60 * 30, 0, false, true, true));
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60 * 30, 0, false, true, true));
            }
        }
    }

    /**
     * 真复苏（第 17 位祭品倒下）。
     *
     * <p>顺序很重要：先清封印（连带把玩家身上的失明/禁足摘掉）→ 回满血 → 上狂暴 →
     * 把参战玩家送进黄金乡 → 自己跟过去。维度没加载出来时明确报缺口，不假装传送成功。
     */
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
        if (golden == null || !GoldenLand.sendPlayers(server, participants)) {
            this.say("epitaph.galboss.goldenland_missing", ChatFormatting.YELLOW);
            return;
        }
        this.followIntoGoldenLand(golden);
    }

    /**
     * 把自己搬到黄金乡：显式建新实例 + 拷贝碑文状态，旧实例 {@code discard()}。
     *
     * <p><b>不要</b>用 {@code changeDimension}：Forge 对非玩家实体的跨维度会新建一个实例，
     * 原实例不一定被移除 —— 实机验证时同一场测试留下了 4 个残留的贝阿朵莉切，
     * 而且对它调用 {@code moveTo} 是作用在已经失效的旧实例上（她没跟过去）。
     */
    private void followIntoGoldenLand(ServerLevel golden) {
        BeatriceEntity moved = ModEntities.BEATRICE.get().create(golden);
        if (moved == null) {
            this.say("epitaph.galboss.goldenland_missing", ChatFormatting.YELLOW);
            return;
        }
        moved.moveTo(GoldenLand.SPAWN.x + 4.0, GoldenLand.SPAWN.y, GoldenLand.SPAWN.z,
                this.getYRot(), 0.0f);
        moved.setYHeadRot(this.getYHeadRot());
        moved.ritualActive = true;
        moved.kills = this.kills;
        moved.night = this.night;
        moved.awakened = this.awakened;
        moved.awakenedAnnounced = this.awakenedAnnounced;
        moved.butterflyVolley.reset(VOLLEY_FIRST_DELAY);
        moved.setHealth(moved.getMaxHealth());
        moved.bossEvent.setVisible(true);
        golden.addFreshEntity(moved);
        MinecraftServer server = golden.getServer();
        if (server != null) {
            BodySeal.bind(server, moved.getUUID());
        }
        for (ServerPlayer player : golden.players()) {
            if (player.distanceTo(moved) <= ARENA_RADIUS) {
                moved.bossEvent.addPlayer(player);
            }
        }
        this.discard();
    }

    // ------------------------------------------------------------------ 黄金蝶

    /** 齐射：在她身前 1.2 格生成，避免贴脸那一下就撞到自己（README 记的两个坑之一）。 */
    public void volley(int count) {
        if (this.level().isClientSide) {
            return;
        }
        LivingEntity target = this.findCombatTarget();
        int n = Math.max(1, Math.min(12, count));
        Vec3 look = this.getLookAngle();
        Vec3 side = new Vec3(-look.z, 0.0, look.x).normalize();
        for (int i = 0; i < n; i++) {
            ButterflyMissileEntity missile = ModEntities.BUTTERFLY_MISSILE.get().create(this.level());
            if (missile == null) {
                continue;
            }
            double offset = (i - (n - 1) / 2.0) * 0.42;
            Vec3 spawn = this.position()
                    .add(look.scale(1.2))
                    .add(side.scale(offset))
                    .add(0.0, this.getEyeHeight() - 0.25, 0.0);
            missile.moveTo(spawn.x, spawn.y, spawn.z, this.getYRot(), this.getXRot());
            missile.launch(this, target, 0.85 + this.random.nextDouble() * 0.30);
            this.level().addFreshEntity(missile);
        }
        this.triggerAnim(CONTROLLER, "cast");
        this.playSoundAt(SoundEvents.EVOKER_CAST_SPELL, 1.2f, 1.35f);
    }

    /**
     * 大范围魔法调度：冷却到了就在<b>目标脚下</b>放一招覆盖整个区块的魔法。
     *
     * <p>位置取目标而不是她自己：这几招的范围是一个区块，落在她身上等于打不到人。
     * 落在目标脚下，玩家读到的就是「这一块归我」，跑出区块才有意义。
     *
     * <p>没有目标时<b>不重置冷却</b>（置 1 让下一 tick 再试），否则她会一直卡在
     * 「刚想放就被清空冷却」的循环里。
     */
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
        this.triggerAnim(CONTROLLER, "cast");
        this.playSoundAt(SoundEvents.EVOKER_CAST_SPELL, 1.4f, 0.75f);
        this.areaSpellCd = this.awakened ? AREA_SPELL_CD / 2 : AREA_SPELL_CD;
    }

    /**
     * 技能：黄金蝶齐射（自带冷却与状态机）。
     *
     * <p>整段「技能」而不是「定时丢一发」：预警（身前聚蝶 + 地面光圈）→ 第一波齐射 →
     * 半秒后第二波 → 收招；打出去的是 {@link ButterflyMissileEntity}，飞行尾迹与命中爆炸
     * 用的是同一只 ER 橙蝶，从出手到砸中读得出是同一招。
     *
     * <p>为什么不用 {@code boss.BossSkill} 那套框架：它的泛型上界是
     * {@code AbstractFSBossEntity}（因为冷却要吃 {@code boss.cd()} 的倍率），
     * 而贝阿朵莉切 {@code extends Monster}。为一条技能去放宽整个框架的上界，改动面大于收益，
     * 所以这里用一个结构对齐的状态机：参数（预警 / 两波 / 收招 / 冷却秒数）和礼那套一样，
     * 复苏（P2）加量也在这里表达。
     */
    private static final class ButterflyVolleySkill {

        /** 冷却（tick）：复苏后减半，对应原来 VOLLEY_CD / VOLLEY_CD_AWAKENED。 */
        private static final int COOLDOWN = 12 * 20;
        /** 预警时长：1 秒，够玩家看出「她要放蝶了」。 */
        private static final int TELEGRAPH = 20;
        /** 第二波与第一波的间隔。 */
        private static final int SECOND_WAVE_DELAY = 12;
        /** 第二波之后的收招时间。 */
        private static final int RECOVERY = 30;

        /** 剩余冷却（tick）；-1 之外的负数不用。 */
        private int cooldown;
        /** 施放进度：-1 = 没在放。 */
        private int elapsed = -1;
        private int waves;

        int cooldown() {
            return Math.max(0, this.cooldown);
        }

        void setCooldown(int ticks) {
            this.cooldown = Math.max(0, ticks);
        }

        /** 收掉当前施放并把冷却重置到指定值（开场 / 复苏 / 换阶段用）。 */
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
                // 蓄力：先在身前聚一小撮橙蝶（只是预告，不造成伤害）
                if (t % 6 == 0 && boss.level() instanceof ServerLevel server) {
                    Vec3 look = boss.getLookAngle();
                    server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                            boss.getX() + look.x * 1.1,
                            boss.getY() + boss.getEyeHeight() - 0.2,
                            boss.getZ() + look.z * 1.1,
                            2, 0.35, 0.30, 0.35, 0.01);
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

    /**
     * 技能：谢斯塔黄金弓狙击术。
     *
     * <p>流程（用户指定的规则）：
     * <ol>
     *   <li>选中 40 格内的一名玩家，**锁定 2 秒**：屏幕给受害者打「被瞄准」提示 +
     *       画一条贯穿他的红外线（旁观者也能看见这道线，见 {@code SniperBeamRenderer}）；</li>
     *   <li>2 秒到点射出一支<b>光箭</b>（{@code SniperArrowEntity}）：追踪、不撞方块，
     *       命中扣「当前生命的一半」（魔法伤害，护甲挡不住）；</li>
     *   <li>命中处溅血（{@code blood_drop} / {@code blood_splash}）。</li>
     * </ol>
     *
     * <p>锁定期间每 5 tick 重发一次瞄准线（寿命 10 tick）：线会跟着目标走，
     * 而不是钉在锁定瞬间的位置上。
     */
    private static final class SniperSkill {

        /** 冷却（tick）：用户要求「每次间隔 1 分钟」。 */
        private static final int COOLDOWN = 60 * 20;
        /**
         * 只在「已献上的祭品 &lt; 10」时可用。
         *
         * <p>谢斯塔（Siesta）是碑文前半段的处刑人：祭品数过了 10（也就是进入第六夜之后）
         * 这个技能就不该再出现，留给后半段的近战/法术。复苏发生在第 17 位，
         * 与「&lt;10」互斥，所以这里也不需要再写「复苏后冷却减半」。
         */
        private static final int SACRIFICE_LIMIT = 10;
        /** 锁定时长：用户要求的 2 秒。 */
        private static final int LOCK_TICKS = 40;
        /** 射完到收招结束。 */
        private static final int RECOVERY = 14;
        /** 锁定范围（格）。 */
        private static final double RANGE = 40.0;
        /** 瞄准线刷新间隔与单次寿命。 */
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
                if (!boss.ritualActive) {
                    return;
                }
                if (!available(boss)) {
                    if (boss.tickCount % 100 == 0) {
                        com.example.galboss.GalBoss.LOGGER.info(
                                "[Sniper] skipped: sacrifices {} >= {}", boss.kills, SACRIFICE_LIMIT);
                    }
                    return;
                }
                Player target = pickTarget(boss);
                if (target == null) {
                    if (boss.tickCount % 40 == 0) {
                        com.example.galboss.GalBoss.LOGGER.info("[Sniper] no target in {} blocks (ritual={})",
                                (int) RANGE, boss.ritualActive);
                    }
                    return;
                }
                start(boss, target);
            }
            advance(boss);
        }

        /** 触发条件：碑文进行中且已献上的祭品还不到 10 个。 */
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
            com.example.galboss.GalBoss.LOGGER.info("[Sniper] lock on {} (dist {})",
                    target.getName().getString(), String.format("%.1f", boss.distanceTo(target)));
            boss.triggerAnim(CONTROLLER, "cast");
            boss.playSoundAt(SoundEvents.ARROW_SHOOT, 1.4f, 1.6f);
            // 受害者屏幕上的「被瞄准」提示
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
            // 光箭已经在路上：屏幕上的锁定提示收掉（红线也一起收）
            this.releaseLock(boss);
        }

        private void releaseLock(BeatriceEntity boss) {
            Player target = this.resolveLocked(boss);
            if (target != null) {
                ModNetwork.sendToPlayer(target, S2CSniperLockPacket.release());
            }
        }

        /**
         * 解析锁定目标。
         *
         * <p>⚠ 必须走 {@code PlayerList.getPlayer(uuid)}：{@code ServerLevel.getEntity(UUID)} 查的是
         * 实体表，<b>玩家不在里面</b>（玩家单独存在 {@code ServerLevel.players} 里），
         * 用 getEntity 会永远拿到 null —— 实测现象是「锁定了但 2 秒后什么都没发生」。
         */
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

    /**
     * 调试入口：立刻对指定玩家放一次狙击（`/galboss sniper` 用）。
     *
     * @return true 表示这次锁定真的开始了（目标在世界里、不是旁观/创造）
     */
    public boolean debugSnipe(Player target) {
        if (this.level().isClientSide || target == null || this.sniper.elapsed >= 0) {
            return false;
        }
        this.sniper.start(this, target);
        return true;
    }

    // ------------------------------------------------------------------ 受击 / 交互

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return super.hurt(source, amount);
        }
        Entity attacker = source.getEntity();
        // 第一次被玩家打 → 开场（这一下照打，之后就免疫了）
        if (!this.ritualActive && attacker instanceof Player) {
            this.startRitual();
            return super.hurt(source, amount);
        }
        if (this.ritualActive && !this.awakened) {
            // /kill 与虚空等「无视无敌」的伤害必须放行：
            // 之前无差别 return false，结果管理员连 /kill 都杀不掉她（残留了 4 个实例）
            if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                return super.hurt(source, amount);
            }
            this.immuneHint();
            return false;
        }
        return super.hurt(source, amount);
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

    /** 「魔女尚未复苏。」——同一秒内不刷屏。 */
    private void immuneHint() {
        if (this.immuneHintCd > 0) {
            return;
        }
        this.immuneHintCd = 40;
        this.say("epitaph.galboss.immune", ChatFormatting.DARK_GRAY);
        this.playSoundAt(SoundEvents.SHIELD_BLOCK, 1.0f, 0.7f);
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            this.bossEvent.removeAllPlayers();
            this.bossEvent.setVisible(false);
            // 第十夜：旅途结束（碑文进聊天栏，屏幕上只打「黄金乡」那句事实）
            if (this.ritualActive) {
                this.night = 10;
                this.say("epitaph.galboss.night.10", ChatFormatting.DARK_RED);
                this.redTruth("redtruth.galboss.victory");
                this.say("epitaph.galboss.victory", ChatFormatting.GOLD);
            }
            BodySeal.clear();
            this.dropTreasures();
            this.triggerAnim(CONTROLLER, "death");
        }
        super.die(source);
    }

    /** 四件宝物：金块 ×64 / 不死图腾 / 附魔金苹果 / 贝阿朵莉切的烟斗。 */
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

    /** 碑文期间血条显示「祭品 x/17」，复苏后改回真实血量。 */
    private void updateBossBar() {
        if (!this.ritualActive) {
            return;
        }
        this.bossEvent.setName(Component.translatable("epitaph.galboss.bar",
                this.nightName(), this.kills, TOTAL_SACRIFICES));
        if (this.awakened) {
            float ratio = this.getMaxHealth() <= 0.0f ? 0.0f : this.getHealth() / this.getMaxHealth();
            this.bossEvent.setProgress(Math.max(0.0f, Math.min(1.0f, ratio)));
        } else {
            this.bossEvent.setProgress(Math.min(1.0f, (float) this.kills / TOTAL_SACRIFICES));
        }
    }

    /** actionbar 那一行，由 {@code EpitaphHud} 每 0.5 秒拉一次。 */
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

    /** 碑文原文走聊天栏；屏幕上只留「红字：事实」。 */
    private void redTruth(String key) {
        ModNetwork.sendRedTruthKey(this.level(), this.position(), ARENA_RADIUS, key, 60);
    }

    /**
     * 排队念一句红字（屏幕单行，连续两句会互相顶掉）。
     *
     * @param key        语言键
     * @param delayTicks 多少 tick 后再念
     */
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
        // 读档恢复封印：不恢复的话重进存档「剜头/剜足」会静默消失
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

    // ------------------------------------------------------------------ 调试入口（/galboss）

    public boolean isRitualActive() {
        return this.ritualActive;
    }

    public int getRitualNight() {
        return this.night;
    }

    public int getSacrifices() {
        return this.kills;
    }

    /** {@code /galboss epitaph}：直接开碑文（不依赖玩家先打她一下）；已在碑文里则<b>重开一场</b>。 */
    public void debugStartRitual() {
        if (this.ritualActive) {
            this.resetRitual();
        }
        this.startRitual();
    }

    /**
     * 重开一场：先把上一场的祭品清干净再复位。
     *
     * <p>不清祭品会出事：旧祭品身上还带着 {@code galboss_epitaph_sacrifice} 标签，
     * 它们之后随便怎么死都会继续给新一场计数，碑文会「凭空」推进。
     */
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

    /** {@code /galboss epitaph <kills>}：把献祭数设到 0~17 并补齐对应的夜/封印。 */
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
        // quiet：调试跳关不播报、不把人丢进黄金乡（要传送用 /galboss golden）
        this.advanceNights(true);
        this.updateBossBar();
    }

    /** {@code /galboss butterflies <n>}：手动齐射一组黄金蝶。 */
    public void debugLaunchButterflies(int count) {
        this.volley(count);
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 过渡 4 tick：与黑山羊一致，太短起手会跳帧、太长会把接触帧吃掉
        AnimationController<BeatriceEntity> controller =
                new AnimationController<>(this, CONTROLLER, 4, this::predicate);
        for (String name : TRIGGERED_ANIMS) {
            controller.triggerableAnim(name, RawAnimation.begin()
                    .then(ANIM_PREFIX + name, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(controller.receiveTriggeredAnimations());
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        // 死亡优先级最高：之前触发的 cast 还没播完也要立刻切到倒地
        if (this.isDeadOrDying()) {
            if (!state.getController().isPlayingTriggeredAnimation()) {
                state.getController().setAnimation(
                        RawAnimation.begin().then(ANIM_PREFIX + "death", Animation.LoopType.PLAY_ONCE));
            }
            return PlayState.CONTINUE;
        }
        // 一次性动作在播时不要把动画抢走（官方 triggerableAnim 用法要求）
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
