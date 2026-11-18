package com.example.galboss.spell;

import com.example.galboss.GalBoss;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aegis（雅典娜的神盾）：给**任意被施法的生物**罩一层光盾，远程伤害大幅衰减。
 *
 * <p>这是唯一一个「挂在某个生物身上」的法术，但它挂的是**施法目标**（{@code warded}），
 * 不是「贝阿朵莉切」这个类：以后要给别的 boss、召唤物、甚至玩家加盾，一行
 * {@code AegisSpell.cast(level, entity)} 就行。
 *
 * <p>减伤走 {@link LivingDamageEvent}：只对 {@link DamageTypeTags#IS_PROJECTILE} 生效（×0.25），
 * 近战穿盾 —— 这就是它的反制方式（绕后 / 硬砍）。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AegisSpell extends Spell {

    /** 护盾持续时间。 */
    private static final int DURATION = 60;
    /** 远程伤害倍率。 */
    private static final float RANGED_MULTIPLIER = 0.25f;

    /** 当前正在被护盾保护的实体（静态，供事件钩子查询）。 */
    private static final Set<UUID> WARDED = ConcurrentHashMap.newKeySet();

    private final LivingEntity warded;
    private boolean raised;

    public AegisSpell(Level level, LivingEntity warded, @Nullable LivingEntity caster) {
        super(level, warded.position(), caster);
        this.warded = warded;
    }

    public static void cast(Level level, LivingEntity warded, @Nullable LivingEntity caster) {
        SpellEngine.cast(new AegisSpell(level, warded, caster));
    }

    @Override
    protected void step() {
        if (!this.raised) {
            this.raised = true;
            WARDED.add(this.warded.getUUID());
            // 盾影：epitaph_aegis = 八边形能量罩（现烘自 holy/shield-aura，48 帧 / 2.4s 无缝循环）。
            // 用 playLooping 撑满 DURATION 而不是一次性播放：48 帧比盾的 60 tick 短，一次性播会提前消失。
            // 缩放 0.5：罩体约 130px 高 × 0.04 × 0.5 ≈ 2.6 格，罩得住大型 boss；玩家则会留一圈余量。
            PixelfxFx.playLooping(this.level, this.warded.position().add(0.0, 1.2, 0.0),
                    PixelfxSlugs.EPITAPH_AEGIS, DURATION, 0.5f, 0.0f);
            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.warded.getX(), this.warded.getY(), this.warded.getZ(),
                        SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.9f, 1.4f);
            }
        }
        // 每 10 tick 一圈金色粒子，让「还罩着」这件事一直看得见
        if (this.age % 10 == 0 && this.level instanceof ServerLevel server) {
            Vec3 at = this.warded.position();
            for (int i = 0; i < 12; i++) {
                double angle = Math.PI * 2.0 * i / 12;
                server.sendParticles(ParticleTypes.ENCHANT,
                        at.x + Math.cos(angle) * 1.1,
                        at.y + 0.8 + Math.sin(angle * 3) * 0.25,
                        at.z + Math.sin(angle) * 1.1,
                        1, 0.0, 0.02, 0.0, 0.0);
            }
        }
        if (this.age >= DURATION) {
            WARDED.remove(this.warded.getUUID());
            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.warded.getX(), this.warded.getY(), this.warded.getZ(),
                        SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 0.8f, 1.2f);
            }
        }
    }

    /** 某个实体此刻是否被盾罩着（事件钩子 / 自检用）。 */
    public static boolean isWarded(LivingEntity entity) {
        return WARDED.contains(entity.getUUID());
    }

    /** 盾只挡远程：`#is_projectile` 的伤害类型（箭、三叉戟、烈焰球…）。 */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity living = event.getEntity();
        if (!isWarded(living)) {
            return;
        }
        if (!event.getSource().is(DamageTypeTags.IS_PROJECTILE)) {
            return;
        }
        event.setAmount(event.getAmount() * RANGED_MULTIPLIER);
    }

    @Override
    public int durationTicks() {
        return DURATION + 1;
    }

    @Override
    public String id() {
        return "aegis";
    }
}
