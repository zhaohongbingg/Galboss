package com.example.galboss.entity;

import com.example.galboss.GalBoss;
import com.example.galboss.client.ClientPetHooks;
import com.example.galboss.item.PetGiftTable;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.network.S2CPetMessagePacket;
import com.example.galboss.pet.PetChatTable;
import com.example.galboss.pet.PetTradeTable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Boss 的「宠物版本」。
 *
 * <p>用三个 boss 的掉落物（学生会徽章 / 风纪臂章 / 战斗勋章）召唤出来，认主后跟随玩家作战，
 * 玩家可以右击赠礼提升好感度。好感度分四档，档次越高攻击加成越高。
 *
 * <p>实现上直接复用原版的 {@link TamableAnimal}（狼/猫那套），因此「认主、坐下/跟随、
 * 主人被打就还手、离太远自动传送」全部由原版 Goal 负责，本体只额外管三件事：
 * 好感度数值、变体（决定模型 / 贴图 / 动画）、赠礼交互。
 */
public class FsPetEntity extends TamableAnimal implements GeoEntity {

    // ---------------------------------------------------------------- 变体

    /** 三个 boss 各自对应一套已经存在的 geo / 贴图 / 动画资源，直接复用，不需要新资源。 */
    public enum PetVariant {
        TOHKA("reizein_tohka"),
        REI("tadasugawa_rei"),
        IKUKO("onabuta_ikuko");

        private final String entityId;

        PetVariant(String entityId) {
            this.entityId = entityId;
        }

        /** 例如 {@code reizein_tohka}，用于拼 geo / 贴图 / 动画 / 语言键。 */
        public String getEntityId() {
            return this.entityId;
        }

        /** 例如 {@code animation.reizein_tohka.}。 */
        public String getAnimPrefix() {
            return "animation." + this.entityId + ".";
        }

        /** 名牌 / 界面用的语言键，例如 {@code pet.galboss.name.reizein_tohka}。 */
        public String getNameKey() {
            return "pet.galboss.name." + this.entityId;
        }

        public static PetVariant byOrdinal(int ordinal) {
            PetVariant[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TOHKA;
        }
    }

    // ------------------------------------------------------------ 好感度分档

    /**
     * 好感度分级。数值范围 0~100，档次决定攻击加成。
     *
     * <p>阈值与 NFF: Girls 的思路一致（低档无用、高档明显变强），后续要加「交易折扣 /
     * 解锁互动」直接往这里挂字段即可。
     */
    public enum AffectionTier {
        STRANGER(0, 0.0),
        FRIENDLY(30, 2.0),
        TRUSTED(60, 4.0),
        INTIMATE(90, 6.0);

        private final int minAffection;
        private final double attackBonus;

        AffectionTier(int minAffection, double attackBonus) {
            this.minAffection = minAffection;
            this.attackBonus = attackBonus;
        }

        public int getMinAffection() {
            return this.minAffection;
        }

        public double getAttackBonus() {
            return this.attackBonus;
        }

        /** 语言键，例如 {@code pet.galboss.tier.intimate}。 */
        public String getLangKey() {
            return "pet.galboss.tier." + this.name().toLowerCase(Locale.ROOT);
        }

        public static AffectionTier of(int affection) {
            AffectionTier result = STRANGER;
            for (AffectionTier tier : values()) {
                if (affection >= tier.minAffection) {
                    result = tier;
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ 常量

    public static final int MAX_AFFECTION = 100;

    /** 连续送礼的最小间隔（tick），避免一次刷满。 */
    private static final int GIFT_COOLDOWN = 40;

    private static final String TAG_AFFECTION = "GalPetAffection";
    private static final String TAG_VARIANT = "GalPetVariant";
    private static final String TAG_GIFT_COOLDOWN = "GalPetGiftCooldown";
    private static final String TAG_CHAT_DONE = "GalPetChatDone";
    private static final String TAG_SITTING = "GalPetSitting";

    private static final UUID BONUS_ATTACK_UUID = UUID.fromString("c1000000-0000-4000-8000-000000000001");

    private static final EntityDataAccessor<Integer> DATA_AFFECTION =
            SynchedEntityData.defineId(FsPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(FsPetEntity.class, EntityDataSerializers.INT);

    /** 已聊过的话题位图，客户端界面要靠它把聊过的话题标灰。 */
    private static final EntityDataAccessor<Integer> DATA_CHAT_DONE =
            SynchedEntityData.defineId(FsPetEntity.class, EntityDataSerializers.INT);

    /**
     * 坐姿 —— 自己存一份，而不是读 {@code TamableAnimal} 的 flags。
     *
     * <p>原版那一个 byte 同时兼管「已驯服 / 坐下 / 卧姿」好几个语义，还兼着 Wolf 的坐下动画，
     * 语义不清；面板按钮、坐姿动画、名牌高度三处都要读它，自己存一份最稳。
     * {@link #setSitting} 会顺手把原版标记也同步过去，因为走位 Goal 认的是原版那个。
     */
    private static final EntityDataAccessor<Boolean> DATA_SITTING =
            SynchedEntityData.defineId(FsPetEntity.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int giftCooldown = 0;

    /** 客户端诊断用：只在"进入坐姿分支"这一瞬间打一条日志，避免每帧刷屏。 */
    private boolean sitBranchLogged = false;

    /** 已经应用到属性上的档次，用来避免每 tick 重复增删 modifier。 */
    private AffectionTier appliedTier = AffectionTier.STRANGER;

    public FsPetEntity(EntityType<? extends FsPetEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        // 0/1 优先保证「坐下」和「别淹死」，2~3 才是打架与跟人
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.3, true));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.15, 10.0f, 2.0f, false));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(
                this, Monster.class, 5, false, false, FsPetEntity::isValidPetTarget));
    }

    /** 宠物只打普通怪物：不碰 boss、别的宠物和桐香召唤出来的护卫。 */
    private static boolean isValidPetTarget(LivingEntity target) {
        return !(target instanceof AbstractFSBossEntity)
                && !(target instanceof FsGuardEntity)
                && !(target instanceof FsPetEntity);
    }

    // ------------------------------------------------------------ 同步数据

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_AFFECTION, 0);
        this.entityData.define(DATA_VARIANT, PetVariant.TOHKA.ordinal());
        this.entityData.define(DATA_CHAT_DONE, 0);
        this.entityData.define(DATA_SITTING, false);
    }

    public int getChatDone() {
        return this.entityData.get(DATA_CHAT_DONE);
    }

    public void setChatDone(int mask) {
        this.entityData.set(DATA_CHAT_DONE, mask);
    }

    public boolean isSitting() {
        return this.entityData.get(DATA_SITTING);
    }

    /** 设置坐姿，并把原版标记同步过去（FollowOwnerGoal / SitWhenOrderedToSitGoal 认原版那个）。 */
    public void setSitting(boolean sitting) {
        this.entityData.set(DATA_SITTING, sitting);
        this.setOrderedToSit(sitting);
        this.setInSittingPose(sitting);
    }

    public int getAffection() {
        return this.entityData.get(DATA_AFFECTION);
    }

    public void setAffection(int value) {
        this.entityData.set(DATA_AFFECTION, Mth.clamp(value, 0, MAX_AFFECTION));
        this.refreshAffectionBonus();
    }

    public void addAffection(int delta) {
        this.setAffection(this.getAffection() + delta);
    }

    public AffectionTier getTier() {
        return AffectionTier.of(this.getAffection());
    }

    public PetVariant getVariant() {
        return PetVariant.byOrdinal(this.entityData.get(DATA_VARIANT));
    }

    public void setVariant(PetVariant variant) {
        this.entityData.set(DATA_VARIANT, variant.ordinal());
    }

    /**
     * 好感度档次变化时刷新攻击加成。
     *
     * <p>只在档次变化时动手，平时零开销；用 ADDITION 而不是乘法，避免和以后的别的
     * modifier 叠乘爆炸。
     */
    private void refreshAffectionBonus() {
        AffectionTier tier = this.getTier();
        if (tier == this.appliedTier) {
            return;
        }
        this.appliedTier = tier;

        AttributeInstance attack = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack == null) {
            return;
        }
        attack.removeModifier(BONUS_ATTACK_UUID);
        if (tier.getAttackBonus() > 0.0) {
            attack.addTransientModifier(new AttributeModifier(
                    BONUS_ATTACK_UUID, "galboss_affection_attack", tier.getAttackBonus(),
                    AttributeModifier.Operation.ADDITION));
        }
    }

    // ------------------------------------------------------------ 存档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_AFFECTION, this.getAffection());
        tag.putInt(TAG_VARIANT, this.getVariant().ordinal());
        tag.putInt(TAG_GIFT_COOLDOWN, this.giftCooldown);
        tag.putInt(TAG_CHAT_DONE, this.getChatDone());
        tag.putBoolean(TAG_SITTING, this.isSitting());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_VARIANT, tag.getInt(TAG_VARIANT));
        // 先写档次基准再写数值，避免 refreshAffectionBonus 拿旧档次做比较
        this.appliedTier = AffectionTier.of(tag.getInt(TAG_AFFECTION));
        this.setAffection(tag.getInt(TAG_AFFECTION));
        this.giftCooldown = tag.getInt(TAG_GIFT_COOLDOWN);
        this.setChatDone(tag.getInt(TAG_CHAT_DONE));
        // 老存档没有这个键：退回原版标记，别让已经坐下的搭档站起来
        this.setSitting(tag.contains(TAG_SITTING) ? tag.getBoolean(TAG_SITTING) : this.isOrderedToSit());
    }

    // ------------------------------------------------------------ 每 tick

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.giftCooldown > 0) {
            this.giftCooldown--;
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.isSitting()) {
            // 坐下时彻底站住；原版 SitWhenOrderedToGoal 只停一次导航，被打断后会再走起来
            this.getNavigation().stop();
            this.setDeltaMovement(0.0, this.getDeltaMovement().y, 0.0);
            if (this.getTarget() != null) {
                this.setTarget(null);
            }
        }
    }

    /** 主人打不到自己的宠物。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity livingAttacker && this.isOwnedBy(livingAttacker)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------ 右键交互
    //
    // 宠物身上只保留一个动作：**空手右键打开面板**。送礼、聊天、交易全部在面板里完成
    // （见 FsPetScreen / C2SPetActionPacket），这样只有一条结算路径，也不会误消耗手上的东西。

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (this.level().isClientSide) {
            // 只在自己的宠物上开面板；归属在客户端也是同步过的，不会给别人开出面板
            if (stack.isEmpty() && this.isTame() && this.isOwnedBy(player)) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ClientPetHooks.openPetScreen(this));
            }
            return InteractionResult.SUCCESS;
        }

        if (!this.isTame() || !this.isOwnedBy(player)) {
            this.sendMessage(player, "pet.galboss.msg.not_owner", null);
            return InteractionResult.SUCCESS;
        }

        // 空手：面板这边不需要服务端做什么
        if (stack.isEmpty()) {
            return InteractionResult.SUCCESS;
        }

        // 手上拿着东西：不直接收下，只提示去哪儿操作
        this.sendMessage(player, PetGiftTable.valueOf(this.getVariant(), stack) > 0
                ? "pet.galboss.msg.gift_in_panel"
                : "pet.galboss.msg.not_gift", null);
        return InteractionResult.SUCCESS;
    }

    /** 面板上的「赠送」按钮入口；主手不是礼物时给一条反馈。 */
    public void giftFromPanel(Player player) {
        if (!this.acceptGift(player, player.getMainHandItem())) {
            this.sendMessage(player, "pet.galboss.msg.not_gift", null);
        }
    }

    /**
     * 收下玩家手里的礼物。
     *
     * <p>右键直接送和界面上的「赠送」按钮共用这一条路径，结算只在这里发生一次。
     *
     * @return 只要"确实处理了这次赠礼"就返回 true（包括冷却中、好感已满），
     *         只有"这根本不是礼物"才返回 false，好让调用方继续走别的分支
     */
    public boolean acceptGift(Player player, ItemStack stack) {
        int value = PetGiftTable.valueOf(this.getVariant(), stack);
        if (value <= 0) {
            return false;
        }
        if (this.giftCooldown > 0) {
            this.sendMessage(player, "pet.galboss.msg.gift_cooldown", null);
            return true;
        }
        if (this.getAffection() >= MAX_AFFECTION) {
            this.sendMessage(player, "pet.galboss.msg.gift_full", null);
            return true;
        }

        int before = this.getAffection();
        this.addAffection(value);
        int gained = this.getAffection() - before;
        this.giftCooldown = GIFT_COOLDOWN;
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        this.onGiftAccepted();
        this.sendMessage(player, "pet.galboss.msg.gift_ok", "+" + gained);
        return true;
    }

    /** 坐下 / 起立，界面按钮和潜行右键共用一个入口。 */
    public void toggleSit(Player player) {
        boolean sit = !this.isSitting();
        this.setSitting(sit);
        GalBoss.LOGGER.debug("[pet] toggleSit({}) -> {} | isSitting={} 原版标记={}",
                this.getVariant().getEntityId(), sit, this.isSitting(), this.isOrderedToSit());
        this.sendMessage(player, sit ? "pet.galboss.msg.sit" : "pet.galboss.msg.stand", null);
    }

    /**
     * 聊一个话题。
     *
     * <p>首次聊某个话题才加好感度（按话题锁定位图去重），之后只回话不加分。
     */
    public void tryChat(Player player, int topicIndex) {
        List<PetChatTable.Topic> topics = PetChatTable.topicsOf(this.getVariant());
        if (topicIndex < 0 || topicIndex >= topics.size()) {
            return;
        }
        PetChatTable.Topic topic = topics.get(topicIndex);
        if (!topic.isUnlocked(this.getAffection())) {
            this.sendMessage(player, "pet.galboss.msg.chat_locked",
                    String.valueOf(topic.unlockAffection()));
            return;
        }

        this.sendMessage(player, topic.lineKey(), null);

        int mask = 1 << topicIndex;
        if ((this.getChatDone() & mask) == 0) {
            this.setChatDone(this.getChatDone() | mask);
            this.addAffection(topic.affectionGain());
        }
    }

    /** 购买一件商品：校验解锁与货币，扣钱给货。 */
    public void tryTrade(Player player, int offerIndex) {
        List<PetTradeTable.Offer> offers = PetTradeTable.offersOf(this.getVariant());
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return;
        }
        PetTradeTable.Offer offer = offers.get(offerIndex);
        if (!offer.isUnlocked(this.getAffection())) {
            this.sendMessage(player, "pet.galboss.msg.trade_locked",
                    String.valueOf(offer.unlockAffection()));
            return;
        }

        int price = PetTradeTable.priceOf(offer, this.getAffection());
        boolean creative = player.getAbilities().instabuild;
        if (!creative && countItem(player, offer.currency()) < price) {
            this.sendMessage(player, "pet.galboss.msg.trade_poor", null);
            return;
        }
        if (!creative) {
            consumeItem(player, offer.currency(), price);
        }

        ItemStack result = offer.createStack();
        if (!player.getInventory().add(result)) {
            player.drop(result, false);
        }
        this.playSound(SoundEvents.ITEM_PICKUP, 0.7f, 1.2f);
        this.sendMessage(player, "pet.galboss.msg.trade_ok", null);
    }

    private void onGiftAccepted() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART,
                    this.getX(), this.getY() + this.getBbHeight() * 0.8, this.getZ(),
                    4, 0.3, 0.3, 0.3, 0.0);
        }
        this.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    /** 宠物说的话统一走消息包：界面开着就显示在界面里，没开就落进聊天栏。 */
    private void sendMessage(Player player, String langKey, @Nullable String arg) {
        ModNetwork.sendToPlayer(player, new S2CPetMessagePacket(this.getId(), langKey, arg));
    }

    private static int countItem(Player player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void consumeItem(Player player, Item item, int amount) {
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().items);
        stacks.addAll(player.getInventory().offhand);
        int remaining = amount;
        for (ItemStack stack : stacks) {
            if (remaining <= 0) {
                break;
            }
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        player.getInventory().setChanged();
    }

    // ------------------------------------------------------------ 杂项

    /** 宠物没有繁殖，也不会自然生成。 */
    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob otherParent) {
        return null;
    }

    /**
     * 名牌高度。
     *
     * <p>宠物渲染时整体缩到 0.5 倍，而名牌的 Y 偏移恰好也在这层缩放里 —— 原版的「身高 + 0.5」
     * 到这里只剩 0.75 格，看着像贴在肩膀上。补成「身高 + 1.5」，缩放后落在头顶上方约 1.25 格，
     * 也就是比默认位置高 0.5 格。改渲染缩放时记得同步这里的数。
     *
     * <p>坐下时再压一档：坐姿动画把人整体沉了 10 像素（模型顶端 32px → 22px），
     * 头顶矮了约 0.16 格（缩放后），名牌不跟着降就会飘在半空。1.2 是让"名牌到头顶的间距"
     * 和站立时保持一致的取值。
     */
    @Override
    public float getNameTagOffsetY() {
        return this.getBbHeight() + (this.isSitting() ? 1.2f : 1.5f);
    }

    // ------------------------------------------------------------------ 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 5, this::predicate));
    }

    private <T extends GeoAnimatable> PlayState predicate(AnimationState<T> state) {
        String prefix = this.getVariant().getAnimPrefix();
        if (this.hurtTime > 0) {
            state.getController().setAnimation(
                    RawAnimation.begin().then(prefix + "hurt", Animation.LoopType.PLAY_ONCE));
            return PlayState.CONTINUE;
        }
        // 坐下：换成专门的坐姿动画（大腿前伸、脚掌贴地），否则会看到"站着滑行"
        if (this.isSitting()) {
            if (!this.sitBranchLogged) {
                this.sitBranchLogged = true;
                GalBoss.LOGGER.debug("[pet] 客户端进入 sit 分支 variant={} 动画名={}",
                        this.getVariant().getEntityId(), prefix + "sit");
            }
            state.getController().setAnimation(RawAnimation.begin().thenLoop(prefix + "sit"));
            return PlayState.CONTINUE;
        }
        this.sitBranchLogged = false;
        if (state.isMoving()) {
            state.getController().setAnimation(RawAnimation.begin().thenLoop(prefix + "walk"));
            return PlayState.CONTINUE;
        }
        state.getController().setAnimation(RawAnimation.begin().thenLoop(prefix + "idle"));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
