package com.example.galboss.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FS 徽章的终局效果。
 *
 * 生命值低于 30% 时获得 Speed II / Strength I / Resistance I，各持续 8 秒，冷却 60 秒。
 * 这个 mod 没有饰品栏依赖，所以「装备」定义为放在主手或副手。
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FsBadgeEvents {

    private static final float TRIGGER_HEALTH_RATIO = 0.30f;
    private static final int BUFF_DURATION = 8 * 20;
    private static final int COOLDOWN = 60 * 20;

    /** 剩余冷却 tick。 */
    private static final Map<UUID, Integer> COOLDOWN_LEFT = new HashMap<>();

    private FsBadgeEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();

        int left = COOLDOWN_LEFT.getOrDefault(id, 0);
        if (left > 0) {
            COOLDOWN_LEFT.put(id, left - 1);
            return;
        }
        if (!isHoldingBadge(player) || player.isSpectator()) {
            return;
        }
        if (player.getHealth() > player.getMaxHealth() * TRIGGER_HEALTH_RATIO) {
            return;
        }

        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, BUFF_DURATION, 1, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, BUFF_DURATION, 0, false, true, true));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, BUFF_DURATION, 0, false, true, true));
        COOLDOWN_LEFT.put(id, COOLDOWN);
    }

    private static boolean isHoldingBadge(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.FS_BADGE.get())
                || player.getOffhandItem().is(ModItems.FS_BADGE.get());
    }
}
