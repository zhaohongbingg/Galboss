package com.example.galboss.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 流血：每秒造成 1 + 等级 点无视护甲的伤害，可叠层延长 / 加强。
 *
 * 郁子「一番队突击」命中时施加。如果以后整合包里接了自己的流血系统，
 * 把 {@code OnabutaIkukoEntity} 里挂的这个效果换掉即可。
 */
public class BleedingEffect extends MobEffect {

    public static final int INTERVAL_TICKS = 20;

    public BleedingEffect() {
        super(MobEffectCategory.HARMFUL, 0x8B0000);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return duration % INTERVAL_TICKS == 0;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        entity.hurt(entity.damageSources().magic(), 1.0f + amplifier);
    }
}
