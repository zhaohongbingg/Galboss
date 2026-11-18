package com.example.galboss.ritual;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.network.ModNetwork;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 「黄金碑文」的祭品账本 —— 碑文唯一推进方式就是<b>数死了几个</b>。
 *
 * <p>旧版的十夜是「按夜分组」的：13 只山羊分成 1/1/1/1/1/1/2/2/4/5/6/7/8 夜，只有轮到的那几只
 * 会被唤醒，第 3 夜还要玩家在聊天栏打出她的名字。现在改成数量判定之后，分组、休眠、
 * 名字全都不需要了：<b>任何</b>身上带 {@link #TAG_SACRIFICE} 的实体死亡就计一个数，
 * 至于它是黑山羊还是原版宾客、谁杀的、用什么杀的，一概不管。
 *
 * <p>这个钩子还兼管<b>玩家死亡的红字</b>：碑文进行中、且死在六轩岛的祭坛范围里，
 * 屏幕上打一句「&lt;玩家名&gt;，已死。」（2026-11-17 按用户要求加的）。
 *
 * <p>真正的判定（这是第几个、该剜哪个部位）在 {@link BeatriceEntity#onSacrificeDied(Entity)} 里。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SacrificeLedger {

    /** 祭品标签：开碑文时打在 13 只黑山羊 + 4 位宾客身上，存档也会带着它。 */
    public static final String TAG_SACRIFICE = "galboss_epitaph_sacrifice";

    private SacrificeLedger() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity dead = event.getEntity();
        if (dead.level().isClientSide()) {
            return;
        }
        // 玩家死亡 → 「<玩家名>，已死。」红字（只在碑文进行中、且死在祭坛附近时播）
        if (dead instanceof Player player) {
            BeatriceEntity boss = BodySeal.encounterBoss();
            if (boss != null && boss.isRitualActive() && boss.distanceTo(player) <= 96.0) {
                // 100 tick：死亡界面会盖在 HUD 之上，红字要留到重生之后还看得见
                ModNetwork.sendRedTruthKey(player.level(), player.position(), 96.0,
                        "redtruth.galboss.player_dead", player.getGameProfile().getName(), 100);
            }
            return;
        }
        if (!dead.getTags().contains(TAG_SACRIFICE)) {
            return;
        }
        BeatriceEntity boss = BodySeal.encounterBoss();
        if (boss == null || !boss.isRitualActive()) {
            // 这条日志是给「碑文卡住」留的证据：boss 的区块被卸载 / 她已经不在场时，
            // encounterBoss() 查不到人，这一份献祭就不会被记账。
            GalBoss.LOGGER.warn("[Epitaph] sacrifice died but the ritual boss is not reachable: {}",
                    dead.getType().toShortString());
            return;
        }
        boss.onSacrificeDied(dead);
    }
}
