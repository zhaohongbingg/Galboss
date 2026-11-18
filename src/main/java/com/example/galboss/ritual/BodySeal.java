package com.example.galboss.ritual;

import com.example.galboss.GalBoss;
import com.example.galboss.effect.ModEffects;
import com.example.galboss.entity.BeatriceEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「黄金碑文」里的部位封印 —— 第 11~15 位祭品倒下时，剜下的部位落在<b>玩家</b>身上。
 *
 * <p>这是整套碑文唯一的硬控来源，所以做成和 {@code DisciplineRules} 同一种形态：
 * 状态是<b>静态</b>的（谁都能查），钩子只负责「抓到对应行为」，真正的效果由
 * {@link EpitaphHud} 按 tick 刷新（失明 / 禁足），事件类钩子是瞬时的（禁盾 / 禁食 / 禁跳）。
 *
 * <p>封印只对「碑文遭遇半径 64 格内」的玩家生效 —— 跑出战场就自动脱离，
 * 这也是玩家中途死回主世界后不会一直瞎着的原因。
 *
 * <h2>负面 buff 怎么下（这一段就是答案）</h2>
 *
 * <p>五个部位里有三个<b>不能用原版效果表达</b>，所以分成两条路：
 * <ul>
 *   <li>能用效果的（头 → {@code DARKNESS}、足 → 本模组 {@code CONFINEMENT}）：
 *       由 {@link EpitaphHud} 每 10 tick 重新 {@code addEffect}，时长 60 tick ——
 *       效果会自然过期，续期就是「持续型 debuff」的正确写法，比永久效果安全得多
 *       （玩家掉线 / 跑出战场 / 战斗结束时不会留下永久失明）。</li>
 *   <li>原版没效果的（胸 禁盾、腹 禁食、膝 禁跳）：<b>拦事件</b>。
 *       {@link ShieldBlockEvent} 取消 = 这一下没挡住；{@code LivingEntityUseItemEvent.Start}
 *       取消 = 吃不到嘴里；{@code LivingEvent.LivingJumpEvent} 不可取消，那就把垂直速度按回去。
 *       三个钩子都先过 {@link #appliesTo} 再动手，避免误伤战场外的玩家。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BodySeal {

    /** 部位。顺序 = 碑文里的顺序：头 / 胸 / 腹 / 膝 / 足。 */
    public enum Seal {
        HEAD("head"),
        CHEST("chest"),
        BELLY("belly"),
        KNEE("knee"),
        FOOT("foot");

        private final String key;

        Seal(String key) {
            this.key = key;
        }

        /** 语言键后缀，例如 {@code epitaph.galboss.seal.head}。 */
        public Component displayName() {
            return Component.translatable("epitaph.galboss.seal." + this.key);
        }

        /** 部位英文键（{@code head} / {@code chest} …），红字语言键也是照它拼的。 */
        public String key() {
            return this.key;
        }
    }

    /** 封印生效的遭遇半径（格）。 */
    private static final double AURA_RADIUS = 64.0;

    /** 同一条提示的冷却（tick），避免连点跳跃刷屏。 */
    private static final long HINT_COOLDOWN = 40L;

    private static final EnumSet<Seal> ACTIVE = EnumSet.noneOf(Seal.class);
    private static final Map<UUID, Long> LAST_HINT = new ConcurrentHashMap<>();

    @Nullable
    private static UUID bossId;
    @Nullable
    private static MinecraftServer server;

    private BodySeal() {
    }

    // ------------------------------------------------------------------ 状态

    /** 绑定到这一场遭遇的贝阿朵莉切（换一场遭遇时旧状态会被清掉）。 */
    public static void bind(MinecraftServer server, UUID bossId) {
        if (!ACTIVE.isEmpty()) {
            clear();
        }
        BodySeal.server = server;
        BodySeal.bossId = bossId;
    }

    /** 读档恢复：把存进 boss NBT 的封印重新挂上，并重新绑定召唤者。 */
    public static void restore(MinecraftServer server, UUID bossId, Collection<Seal> seals) {
        clear();
        BodySeal.server = server;
        BodySeal.bossId = bossId;
        ACTIVE.addAll(seals);
    }

    public static void seal(Seal seal) {
        ACTIVE.add(seal);
    }

    public static boolean has(Seal seal) {
        return ACTIVE.contains(seal);
    }

    public static EnumSet<Seal> snapshot() {
        return EnumSet.copyOf(ACTIVE);
    }

    /** 战斗结束 / 魔女复苏 / 她死亡时清空，并把玩家身上残留的失明 / 禁足一并摘掉。 */
    public static void clear() {
        BeatriceEntity boss = encounterBoss();
        if (boss != null && boss.level() instanceof ServerLevel level) {
            for (Player player : level.players()) {
                if (player instanceof ServerPlayer sp && boss.distanceTo(sp) <= AURA_RADIUS) {
                    sp.removeEffect(MobEffects.DARKNESS);
                    sp.removeEffect(ModEffects.CONFINEMENT.get());
                }
            }
        }
        ACTIVE.clear();
        LAST_HINT.clear();
        bossId = null;
        server = null;
    }

    /** 当前进行碑文遭遇的 boss（跨维度查找）。 */
    @Nullable
    public static BeatriceEntity encounterBoss() {
        if (server == null || bossId == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(bossId);
            if (entity instanceof BeatriceEntity boss) {
                return boss;
            }
        }
        return null;
    }

    /** 这名玩家此刻是否处在「被剜去部位」的范围内。 */
    public static boolean appliesTo(Player player) {
        if (ACTIVE.isEmpty() || bossId == null) {
            return false;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return false;
        }
        BeatriceEntity boss = encounterBoss();
        return boss != null
                && boss.isRitualActive()
                && boss.isAlive()
                && boss.level() == sp.level()
                && boss.distanceToSqr(sp) <= AURA_RADIUS * AURA_RADIUS;
    }

    /** 给 HUD / 提示语用的部位清单（「头 · 胸」，没封印时是「无」）。 */
    public static Component describe() {
        if (ACTIVE.isEmpty()) {
            return Component.translatable("epitaph.galboss.seal_none");
        }
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Seal seal : Seal.values()) {
            if (!ACTIVE.contains(seal)) {
                continue;
            }
            if (!first) {
                out.append(Component.literal(" · "));
            }
            out.append(seal.displayName());
            first = false;
        }
        return out;
    }

    // ------------------------------------------------------------------ 瞬发钩子

    /** 剜膝：跳不起来。事件本身不可取消，只能在起跳那一拍把垂直速度按回去。 */
    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.contains(Seal.KNEE) || !appliesTo(player)) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, -0.02, motion.z);
        hint(player, "epitaph.galboss.hint.knee");
    }

    /** 剜胸：举盾也挡不住。取消事件 = 原版判定里「这一下没挡住」。 */
    @SubscribeEvent
    public static void onShieldBlock(ShieldBlockEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.contains(Seal.CHEST) || !appliesTo(player)) {
            return;
        }
        event.setCanceled(true);
        hint(player, "epitaph.galboss.hint.chest");
    }

    /** 剜腹：不能进食。右键开始吃的那一刻就掐掉。 */
    @SubscribeEvent
    public static void onUseItemStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.contains(Seal.BELLY) || !appliesTo(player)) {
            return;
        }
        if (!event.getItem().isEdible()) {
            return;
        }
        event.setCanceled(true);
        hint(player, "epitaph.galboss.hint.belly");
    }

    private static void hint(ServerPlayer player, String key) {
        long now = player.level().getGameTime();
        Long last = LAST_HINT.get(player.getUUID());
        if (last != null && now - last < HINT_COOLDOWN) {
            return;
        }
        LAST_HINT.put(player.getUUID(), now);
        player.displayClientMessage(Component.translatable(key), true);
    }
}
