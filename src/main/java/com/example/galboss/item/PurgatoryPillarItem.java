package com.example.galboss.item;

import com.example.galboss.client.renderer.PurgatoryPillarItemRenderer;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * 炼狱之柱（Purgatory Pillar）—— 七位炼狱之桩手里那根柱子，同时也是能拿在手里的武器。
 *
 * <p><b>为什么是 GeoItem 而不是普通物品 + models/item 的 elements</b>：
 * 柱子的 968 个方块里有 <b>819 个带自由角度旋转</b>（螺旋是斜置薄板拼的），
 * 而原版物品模型只接受 0 / ±22.5 / ±45，超了 {@code BlockElement} 反序列化直接抛异常 ——
 * 整份模型不加载，手上只剩紫黑棋盘格。GeckoLib 的 {@code GeoItemRenderer}
 * 走 bedrock geo 通路，不受这个限制。
 *
 * <h2>战斗数值</h2>
 * <ul>
 *   <li><b>左键 = 原版近战 10 点</b>：属性修饰符 {@code +9 ATTACK_DAMAGE}（玩家基础 1 → 10）；
 *       节奏靠 {@code +1.0 ATTACK_SPEED}（基础 4.0 → 5.0，原版冷却 {@code 20/5 = 4 tick}）。</li>
 *   <li><b>右键按住 = 连续突刺</b>：进入原版「使用中」状态，每 tick 结算一次判定，
 *       冲过的敌人每 <b>4 tick</b> 吃一次 <b>10 点</b>。判定体按柱子模型实测尺寸算，
 *       并且**不推玩家**（右键不会让人自己往里走），详见 {@link PurgatoryPillarThrust}。</li>
 *   <li><b>4 tick 的伤害为什么落得下去</b>：原版 {@code LivingEntity.invulnerableTime} 受伤后置 20，
 *       会吞掉这个节奏。左键由 {@code event/PurgatoryPillarCombat} 在 {@code LivingAttackEvent} 里清零，
 *       突刺命中在 {@link PurgatoryPillarThrust} 里动手前清零 —— 只对「手持炼狱之柱的玩家近战」生效。</li>
 * </ul>
 *
 * <p>模型：{@code geo/item/purgatory_pillar.geo.json} + {@code textures/item/purgatory_pillar.png}
 * + {@code animations/item/purgatory_pillar.animation.json}。
 *
 * <p>手持渲染见 {@code client/renderer/layer/PurgatoryPillarLayer}：
 * {@code BlockAndItemGeoLayer} 会把同一个物品画到角色模型的 {@code RightHandLocator} 骨骼上，
 * 所以「怪物手里的柱子」和「玩家手里的柱子」是同一个物品、同一份几何。
 *
 * <p><b>两段动画</b>：{@code spin} 常态循环；{@code use} 右键的 0.45 秒前突突刺，
 * 按住期间每 10 tick 重放一次，读起来就是连续的突刺节奏。
 */
public class PurgatoryPillarItem extends Item implements GeoItem {

    /** 动画控制器名，与 {@link #registerControllers} 里的注册名、右键触发时用的名字必须一致。 */
    private static final String CONTROLLER = "pillar";

    /** 触发式动作名（不是动画名）：注册在 controller 的 triggerableAnim 上。 */
    private static final String ANIM_USE = "use";

    private static final RawAnimation SPIN = RawAnimation.begin()
            .thenLoop("animation.purgatory_pillar.spin");

    // ---------------------------------------------------------------- 战斗数值

    /** 玩家基础攻击力 1.0 + 9.0 = <b>10</b>（左键）。 */
    private static final double ATTACK_DAMAGE_BONUS = 9.0;

    /** 玩家基础攻速 4.0 + 1.0 = 5.0 → 原版攻击冷却 {@code 20 / 5} = <b>4 tick</b>（左键节奏）。 */
    private static final double ATTACK_SPEED_BONUS = 1.0;

    /** 按住右键可以持续多久：和上游一样给足（约 1 小时），实际由玩家松手结束。 */
    private static final int THRUST_USE_DURATION = 72000;

    /** 按住期间重放 {@code use} 动画的间隔（tick）：动作 0.45 秒，每 10 tick 重放一次就是连续突刺。 */
    private static final int ANIM_REPEAT_TICKS = 10;

    private final Multimap<Attribute, AttributeModifier> defaultModifiers;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public PurgatoryPillarItem(Properties properties) {
        super(properties);
        // 主手属性：写法与 1.20.1 的 SwordItem 完全一致（BASE_*_UUID 是 Item 里的 protected 常量）
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_UUID,
                "Weapon modifier", ATTACK_DAMAGE_BONUS, AttributeModifier.Operation.ADDITION));
        builder.put(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_UUID,
                "Weapon modifier", ATTACK_SPEED_BONUS, AttributeModifier.Operation.ADDITION));
        this.defaultModifiers = builder.build();

        // 注册成服务端可同步的 animatable：动画数据能同步、也能从服务端触发
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    /** 只在主手生效（和原版剑一样，副手拿不给攻击加成）。 */
    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? this.defaultModifiers : super.getDefaultAttributeModifiers(slot);
    }

    // ---------------------------------------------------------------- 右键：连续突刺

    /**
     * 右键按下：进入「使用中」状态开始连续突刺（**不推玩家**，站着也戳）。
     *
     * <p>必须返回 {@link InteractionResultHolder#consume} 并调用 {@code startUsingItem}，
     * 否则原版不会进入 using 状态，{@link #onUseTick} 也就不会每 tick 被调用。
     * 客户端也要走这一步（本地预测），伤害只在服务端结算。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);

        if (level instanceof ServerLevel serverLevel) {
            PurgatoryPillarThrust.begin(player, hand);
            this.triggerAnim(player, GeoItem.getOrAssignId(stack, serverLevel), CONTROLLER, ANIM_USE);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 0.8f, 0.8f);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** 可以一直按着（上游同款 72000），没有吃到/喝到之类的姿势。 */
    @Override
    public int getUseDuration(ItemStack stack) {
        return THRUST_USE_DURATION;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /**
     * 按住期间每 tick 一次（客户端 + 服务端都会调用）：结算突刺（不推速度）；
     * 服务端另外每 {@link #ANIM_REPEAT_TICKS} tick 重放一次前突动画。
     */
    @Override
    public void onUseTick(Level level, LivingEntity living, ItemStack stack, int remaining) {
        if (!(living instanceof Player player)) {
            return;
        }
        if (!stack.is(this)) {
            player.stopUsingItem();
            return;
        }
        PurgatoryPillarThrust.tick(player);

        int ticks = player.getTicksUsingItem();
        if (!level.isClientSide && ticks > 0 && ticks % ANIM_REPEAT_TICKS == 0) {
            this.triggerAnim(player, GeoItem.getOrAssignId(stack, (ServerLevel) level), CONTROLLER, ANIM_USE);
        }
    }

    /** 松手 / 被打断 / 换物品：结束突刺并清记账。 */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int remaining) {
        if (living instanceof Player player) {
            PurgatoryPillarThrust.end(player);
        }
    }

    // ---------------------------------------------------------------- 客户端渲染

    /**
     * 客户端专用入口（Forge 的 {@code IClientItemExtensions}）。
     *
     * <p>只有客户端会调用这个方法，专用服务器永远不会实例化 {@link PurgatoryPillarItemRenderer}，
     * 所以这里引用客户端类不会在服务端引发 NoClassDefFoundError（GeckoLib 官方示例同一写法）。
     */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private PurgatoryPillarItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    this.renderer = new PurgatoryPillarItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    // ---------------------------------------------------------------- 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<PurgatoryPillarItem> controller =
                new AnimationController<>(this, CONTROLLER, 2, state -> {
                    // 一次性动作在播时不要被常态自转抢走（官方 triggerableAnim 的用法要求）
                    if (state.getController().isPlayingTriggeredAnimation()) {
                        return PlayState.CONTINUE;
                    }
                    state.getController().setAnimation(SPIN);
                    return PlayState.CONTINUE;
                });
        controller.triggerableAnim(ANIM_USE, RawAnimation.begin()
                .then("animation.purgatory_pillar.use", Animation.LoopType.PLAY_ONCE));
        controllers.add(controller.receiveTriggeredAnimations());
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
