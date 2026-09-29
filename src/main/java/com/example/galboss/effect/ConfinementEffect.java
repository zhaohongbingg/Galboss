package com.example.galboss.effect;

import com.example.galboss.boss.BossTelegraph;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * 禁足：会长「禁足令」与「学生会处分」施加的短时封锁。
 *
 * <p>做法和 Geburah 的锁链陷阱一样是<b>软控</b>：不给伤害，只让人 3 秒内走不动。
 * 移速属性挂 -100%（客户端也会收到这条 modifier，所以玩家自己按方向键也推不动），
 * 同时每 5 tick 把水平速度清零，避免冲刺 / 鞘翅之类绕过属性。
 * 垂直方向不动 —— 还能被击飞、也不会卡进地里。
 */
public class ConfinementEffect extends MobEffect {

    /**
     * 属性 modifier 的固定 ID，重复施加时是替换而不是叠加。
     *
     * <p>注意 {@code MobEffect#addAttributeModifier} 收的是字符串形式的 UUID（原版效果系统
     * 一直用 String 当键），不是 {@link UUID} 对象。
     */
    private static final String SPEED_ID = "c3000000-0000-4000-8000-000000000001";

    public ConfinementEffect() {
        super(MobEffectCategory.HARMFUL, 0x3A3F8F);
        this.addAttributeModifier(Attributes.MOVEMENT_SPEED, SPEED_ID, -1.0,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return duration % 5 == 0;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0, motion.y, 0.0);
        if (entity.level() instanceof ServerLevel serverLevel) {
            // 脚边一圈锁链紫光，提示「你还在禁足期内」
            BossTelegraph.circle(serverLevel, entity.position(), 0.9, BossTelegraph.VIOLET);
        }
    }
}
