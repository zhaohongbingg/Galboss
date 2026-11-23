package com.example.galboss.event;

import com.example.galboss.item.ModItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 炼狱之柱的战斗规则（游戏总线）。
 *
 * <p><b>只做一件事：清掉目标的受伤无敌帧。</b>
 *
 * <p>原版 {@code LivingEntity.invulnerableTime} 在受伤后被置 20，而 {@code hurt()}
 * 里有一段 {@code if (invulnerableTime > 10.0F && !source.is(BYPASSES_COOLDOWN))}
 * —— 这会把「4 tick 一次」的伤害节奏整段吞掉（实际只剩 ~10 tick 一次，且同强度伤害
 * 还会因为 {@code amount <= lastHurt} 直接 return false）。
 *
 * <p>所以：凡是<b>手持炼狱之柱的玩家打出的直接近战</b>，在
 * {@link LivingAttackEvent}（它在 {@code hurt()} 最开头、无敌帧判断之前触发）里把目标的
 * {@code invulnerableTime} 清零，伤害就会按武器节奏真正落地。
 *
 * <p>为什么卡得这么死：这个事件对<b>所有</b>伤害都触发。如果只判"玩家手上有柱子"，
 * 那玩家用柱子时打的火焰、摔落、箭矢伤害也会一起绕过无敌帧。所以额外要求
 * {@code getDirectEntity() == getEntity()}（直接近战，排除弹射物/法术），
 * 并且只看主手 —— 副手拿柱子不影响主手武器的原有节奏。
 *
 * <p>右键突刺在 {@code PurgatoryPillarItem#thrust} 里自己清零，规则与这里一致；
 * 两条路都只对这把武器生效。
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PurgatoryPillarCombat {

    private PurgatoryPillarCombat() {
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player)) {
            return;
        }
        // 直接近战才算：排除箭矢、法术、爆炸等（它们的直接来源不是玩家本体）
        if (event.getSource().getDirectEntity() != player) {
            return;
        }
        if (!player.getMainHandItem().is(ModItems.PURGATORY_PILLAR.get())) {
            return;
        }
        LivingEntity target = event.getEntity();
        // 与原版 hurt() 的判断对齐：清零后 (invulnerableTime > 10) 不成立，伤害正常结算
        target.invulnerableTime = 0;
    }
}
