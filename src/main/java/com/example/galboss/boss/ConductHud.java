package com.example.galboss.boss;

import com.example.galboss.GalBoss;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

/**
 * 风纪状态显示 —— 两处：actionbar 上的一行读数，以及背记过的人头顶的红色通缉标记。
 *
 * <p>为什么是 actionbar：记过不是状态效果（没图标），属性惩罚在原版界面上也看不见，
 * 玩家本来只有「层数变化那一瞬间」的一条提示，很容易漏掉 —— 而记过现在直接决定
 * 礼 / 郁子对他的伤害，必须随时能看到。
 *
 * <p>Geburah 那套是专门画一个 overlay（要自定义 GUI + 一条 S2C 同步封包）。这里用原版
 * actionbar 达到同样的效果：零封包、零渲染层、重连不丢状态，也不需要玩家学新界面。
 * 真要更显眼（屏幕角落常驻图标），再上 {@code IGuiOverlay} + 封包也不迟。
 *
 * <p>禁令和记过会合成一行发出去，避免两条消息互相顶掉：
 * <pre>风纪检查：禁止跳跃（剩 6 秒）｜【记过 2/3】礼 / 郁子 +30%</pre>
 *
 * <p>头顶标记是给<b>别人</b>看的：actionbar 只有本人可见，而「谁在违纪」是整支队伍的信息。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ConductHud {

    /** 刷新间隔：0.5 秒一次，够快也够安静。 */
    private static final int REFRESH_INTERVAL = 10;

    /** 头顶通缉标记的红尘粒。 */
    private static final DustParticleOptions MARKER_DUST =
            new DustParticleOptions(new Vector3f(1.0f, 0.12f, 0.12f), 0.9f);

    private static int timer = REFRESH_INTERVAL;

    private ConductHud() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        timer = REFRESH_INTERVAL;

        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Component line = hudLine(player);
            if (line != null) {
                player.displayClientMessage(line, true);
            }
            markDemerits(player);
        }
    }

    /**
     * 在背记过的人头顶打一个红色通缉标记。
     *
     * <p>用原版尘粒 + 原版「愤怒」云，不注册任何自定义粒子；每 0.5 秒补一次，
     * 高度取碰撞箱顶部，所以蹲下 / 爬行时标记会跟着降，不会飘在半空。
     * 红色尘粒数量随记过条数增加（1 条 2 颗、3 条 6 颗），三条时再加一团愤怒云 ——
     * 远处也能一眼看出「谁被记满了」。
     */
    private static void markDemerits(ServerPlayer player) {
        int count = DemeritLedger.count(player);
        if (count <= 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        double x = player.getX();
        double y = player.getBoundingBox().maxY + 0.35;
        double z = player.getZ();
        level.sendParticles(MARKER_DUST, x, y, z, count * 2, 0.22, 0.06, 0.22, 0.0);
        if (count >= DemeritLedger.maxCount()) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y + 0.3, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 拼一行状态。
     *
     * @return 没有禁令也没有记过时返回 null —— 这时不定期刷新，原版会让 actionbar 自己淡出
     */
    public static Component hudLine(ServerPlayer player) {
        Component rule = null;
        int ticks = DisciplineRules.remainingTicks(player);
        DisciplineRules.Rule active = DisciplineRules.getRule(player);
        if (ticks > 0 && active != null) {
            rule = Component.translatable("conduct.galboss.rule",
                    DisciplineRules.displayName(active), (ticks + 19) / 20)
                    .withStyle(ChatFormatting.GOLD);
        }

        int demerits = DemeritLedger.count(player);
        if (demerits <= 0) {
            return rule;
        }
        // 记过在现实里就是档案上的一笔红字：读数用红色（还带方括号当「标记」），
        // 和禁令的金色区分开，扫一眼就知道自己现在挨的是哪一条
        Component demerit = Component.translatable("conduct.galboss.demerit",
                demerits, DemeritLedger.maxCount(), DemeritLedger.bonusPercent(player))
                .withStyle(ChatFormatting.RED);
        return rule == null ? demerit : Component.translatable("conduct.galboss.both", rule, demerit);
    }
}
