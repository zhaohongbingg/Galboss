package com.example.galboss.boss;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桐香「纪律检查」使用的规则系统。
 *
 * 每条规则挂在具体玩家身上，持续期间监听对应行为，违反就吃 6 点伤害 + 一个短 debuff。
 * 惩罚本身有 1 秒冷却，避免一次连点被罚十几下。
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DisciplineRules {

    /** 违反规则的基础惩罚伤害。 */
    private static final float PENALTY_DAMAGE = 6.0f;

    /** 同一玩家两次惩罚之间的冷却。 */
    private static final int PENALTY_COOLDOWN = 20;

    public enum Rule {
        NO_MELEE,
        NO_RANGED,
        NO_JUMP,
        NO_SHIELD,
        NO_POTION,
        /** 禁止进食：战斗里吃一口也算违纪。 */
        NO_EAT
    }

    /**
     * 违纪回调。
     *
     * <p>规则系统只负责「抓到违纪」，至于违纪的后果（记过、处分）交给放下这条规则的
     * boss —— 和 Geburah 把罚则放在 boss/handler 里、{@code PlayerSin} 只留钩子是同一种分工。
     */
    @FunctionalInterface
    public interface ViolationHandler {
        void onViolation(ServerPlayer player, Rule rule);
    }

    private static final class Active {
        final Rule rule;
        final ViolationHandler handler;
        int remaining;
        int penaltyCooldown;

        Active(Rule rule, int remaining, ViolationHandler handler) {
            this.rule = rule;
            this.remaining = remaining;
            this.handler = handler;
        }
    }

    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private DisciplineRules() {
    }

    // ------------------------------------------------------------------ 对外接口

    /** 给玩家挂上一条规则。 */
    public static void begin(ServerPlayer player, Rule rule, int durationTicks) {
        begin(player, rule, durationTicks, null);
    }

    /** 给玩家挂上一条规则，并绑定违纪回调（谁放的规则谁领这个记过）。 */
    public static void begin(ServerPlayer player, Rule rule, int durationTicks, ViolationHandler handler) {
        ACTIVE.put(player.getUUID(), new Active(rule, durationTicks, handler));
        player.displayClientMessage(Component.translatable(
                "rule.galboss.announce",
                Component.translatable(ruleKey(rule))), false);
    }

    private static String ruleKey(Rule rule) {
        return "rule.galboss." + rule.name().toLowerCase(Locale.ROOT);
    }

    /** 这条禁令还剩多少 tick（HUD 显示用），没有则返回 0。 */
    public static int remainingTicks(Player player) {
        Active a = ACTIVE.get(player.getUUID());
        return a == null ? 0 : a.remaining;
    }

    /** 禁令的显示名，供 boss 组装提示语（「违纪：%s」之类）。 */
    public static Component displayName(Rule rule) {
        return Component.translatable(ruleKey(rule));
    }

    /** 战斗结束 / boss 死亡时清空所有规则。 */
    public static void clearAll() {
        ACTIVE.clear();
    }

    public static boolean hasActiveRule(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    public static Rule getRule(Player player) {
        Active a = ACTIVE.get(player.getUUID());
        return a == null ? null : a.rule;
    }

    // ------------------------------------------------------------------ 内部

    private static void violate(Player player, Rule expected) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        Active a = ACTIVE.get(sp.getUUID());
        if (a == null || a.rule != expected || a.penaltyCooldown > 0) {
            return;
        }
        a.penaltyCooldown = PENALTY_COOLDOWN;

        sp.hurt(sp.damageSources().magic(), PENALTY_DAMAGE);
        sp.addEffect(new MobEffectInstance(penaltyEffect(expected), penaltyDuration(expected), penaltyAmplifier(expected), false, true, true));
        sp.displayClientMessage(Component.translatable(
                "rule.galboss.violation",
                Component.translatable(ruleKey(expected))), true);

        // 抓到了就通知放任这条规则的 boss：它要记一条「记过」
        if (a.handler != null) {
            a.handler.onViolation(sp, expected);
        }
    }

    private static net.minecraft.world.effect.MobEffect penaltyEffect(Rule rule) {
        return switch (rule) {
            case NO_MELEE -> MobEffects.MOVEMENT_SLOWDOWN;
            case NO_RANGED -> MobEffects.WEAKNESS;
            case NO_JUMP -> MobEffects.MOVEMENT_SLOWDOWN;
            case NO_SHIELD -> MobEffects.WEAKNESS;
            case NO_POTION -> MobEffects.CONFUSION;
            case NO_EAT -> MobEffects.HUNGER;
        };
    }

    private static int penaltyAmplifier(Rule rule) {
        return switch (rule) {
            case NO_MELEE -> 1;   // Slowness II
            case NO_RANGED -> 0;  // Weakness I
            case NO_JUMP -> 0;
            case NO_SHIELD -> 0;
            case NO_POTION -> 0;
            case NO_EAT -> 0;
        };
    }

    private static int penaltyDuration(Rule rule) {
        return switch (rule) {
            case NO_MELEE -> 2 * 20;
            case NO_RANGED -> 3 * 20;
            case NO_JUMP -> 3 * 20;
            case NO_SHIELD -> 3 * 20;
            case NO_POTION -> 3 * 20;
            case NO_EAT -> 3 * 20;
        };
    }

    private static boolean isMeleeWeapon(Player player) {
        // 空手也算近战
        return !player.getMainHandItem().is(Items.BOW)
                && !player.getMainHandItem().is(Items.CROSSBOW)
                && !player.getMainHandItem().is(Items.TRIDENT);
    }

    private static boolean isPotion(Player player) {
        return player.getMainHandItem().is(Items.POTION)
                || player.getMainHandItem().is(Items.SPLASH_POTION)
                || player.getMainHandItem().is(Items.LINGERING_POTION);
    }

    private static boolean isFood(Player player) {
        return player.getMainHandItem().isEdible();
    }

    // ------------------------------------------------------------------ 事件

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Active>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Active> entry = it.next();
            Active a = entry.getValue();
            if (a.penaltyCooldown > 0) {
                a.penaltyCooldown--;
            }

            ServerPlayer player = event.getServer() == null
                    ? null
                    : event.getServer().getPlayerList().getPlayer(entry.getKey());

            // 常驻读数（禁令 + 记过合成一行）统一交给 ConductHud 每 0.5 秒刷一次，
            // 这里只负责规则自己的倒计时与到期提示，两边不会互相顶掉 actionbar
            if (--a.remaining > 0) {
                continue;
            }
            it.remove();
            if (player != null) {
                player.displayClientMessage(Component.translatable("rule.galboss.expired"), true);
            }
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (isMeleeWeapon(player)) {
            violate(player, Rule.NO_MELEE);
        }
    }

    @SubscribeEvent
    public static void onArrowLoose(ArrowLooseEvent event) {
        violate(event.getEntity(), Rule.NO_RANGED);
    }

    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof Player player) {
            violate(player, Rule.NO_JUMP);
        }
    }

    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent event) {
        if (event.getEntity() instanceof Player player) {
            violate(player, Rule.NO_SHIELD);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (isPotion(player)) {
            violate(player, Rule.NO_POTION);
        } else if (isFood(player)) {
            violate(player, Rule.NO_EAT);
        }
    }
}
