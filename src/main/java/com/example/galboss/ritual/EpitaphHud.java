package com.example.galboss.ritual;

import com.example.galboss.GalBoss;
import com.example.galboss.effect.ModEffects;
import com.example.galboss.entity.BeatriceEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 碑文遭遇的常驻显示与<b>持续型</b>负面 buff。
 *
 * <p>每 0.5 秒一次，只做两件事：
 * <ul>
 *   <li>actionbar：第几夜、献祭数 x/17、身上被剜掉了哪些部位（见
 *       {@link BeatriceEntity#hudLine()}）</li>
 *   <li>持续型封印：剜头 → {@code DARKNESS}（屏幕周期性发黑，比全瞎好受也更恐怖）、
 *       剜足 → 本模组的 {@code CONFINEMENT}（移速 -100% + 每 5 tick 清零水平速度）。
 *       原版效果有时长，所以按 60 tick 一续，玩家跑出 64 格自然失效。</li>
 * </ul>
 *
 * <h2>负面 buff 的两条下法</h2>
 *
 * <p>这一套是「怎么给玩家上 debuff」的完整答案，五个部位分成两类：
 * <ol>
 *   <li><b>持续型（这里）</b>：能用一个原版 / 自定义 {@link net.minecraft.world.effect.MobEffect}
 *       表达的，就按时长续。写一次时长会过期，所以必须每个刷新周期重新 addEffect。</li>
 *   <li><b>行为型（{@link BodySeal} 的事件钩子）</b>：原版没有「不能举盾」「不能吃东西」
 *       「不能跳」这三种效果，硬凑（比如给个无限失明）玩家也看不懂。正确做法是拦事件：
 *       举盾走 {@code ShieldBlockEvent}、进食走 {@code LivingEntityUseItemEvent.Start}、
 *       起跳走 {@code LivingEntityJumpEvent}，在那一拍取消 / 抵消掉。</li>
 * </ol>
 *
 * <p>和 {@code ConductHud} 一样走原版 actionbar：零封包、零客户端渲染层、重连不丢状态。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EpitaphHud {

    /** 0.5 秒刷新一次。 */
    private static final int REFRESH_INTERVAL = 10;

    /** 遭遇半径（与 BodySeal / 碑文本身保持一致）。 */
    private static final double AURA_RADIUS = 64.0;

    /** 持续型效果每次施加的时长：比刷新间隔长一截，避免网络抖动时闪断。 */
    private static final int AURA_TICKS = 60;

    private static int timer = REFRESH_INTERVAL;

    private EpitaphHud() {
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
        BeatriceEntity boss = BodySeal.encounterBoss();
        if (boss == null || !boss.isRitualActive() || !boss.isAlive()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != boss.level() || boss.distanceTo(player) > AURA_RADIUS) {
                continue;
            }
            player.displayClientMessage(boss.hudLine(), true);
            applySealAura(player);
        }
    }

    /** 持续型封印：剜头 → 黑暗；剜足 → 禁足。行为型封印见 {@link BodySeal}。 */
    private static void applySealAura(ServerPlayer player) {
        if (BodySeal.has(BodySeal.Seal.HEAD)) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, AURA_TICKS, 0, false, false, true));
        }
        if (BodySeal.has(BodySeal.Seal.FOOT)) {
            player.addEffect(new MobEffectInstance(ModEffects.CONFINEMENT.get(), AURA_TICKS, 0, false, false, true));
        }
    }
}
