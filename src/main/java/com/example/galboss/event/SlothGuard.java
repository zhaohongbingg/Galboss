package com.example.galboss.event;

import com.example.galboss.entity.BelphegorEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 贝尔菲戈尔「懒惰结界」的减伤结算点。
 *
 * <p><b>为什么不在 {@code BelphegorEntity#hurt} 里乘 0.4</b>：{@code hurt()} 拿到的 amount
 * 是**护甲/抗性/药水减免之前**的原始伤害，直接改它等于把减伤叠在护甲前面 ——
 * 玩家穿重甲时会被扣两次，穿布甲时又显得减伤不足，而且真实伤害/魔法伤害一样吃减伤。
 *
 * <p>{@link LivingHurtEvent} 在 1.20.1 里是「护甲吸收 + 魔法吸收都算完之后、真正扣血之前」触发，
 * 所以在这里乘 0.4 才是「最终伤害再打 6 折」的正确语义。
 *
 * <p>触发条件与设计表一致：玩家贴近到 5 格以内 → 减伤失效（逼你近身破她的阵地）。
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SlothGuard {

    /** 减伤系数：只剩 40%（即 60% 减伤）。 */
    private static final float DAMAGE_SCALE = 0.4F;

    /** 玩家贴近到这个距离内，减伤失效。 */
    private static final double BREAK_RADIUS = 5.0D;

    private SlothGuard() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof BelphegorEntity sloth)) {
            return;
        }
        if (sloth.level().isClientSide) {
            return;
        }
        if (sloth.playerWithinRadius(BREAK_RADIUS)) {
            return;
        }
        event.setAmount(event.getAmount() * DAMAGE_SCALE);
    }
}
