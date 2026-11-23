package com.example.galboss.spell;

import com.example.galboss.GalBoss;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
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
 * Aegis（雅典娜的神盾）：随身跟随的八边形黄金能量壁垒，大幅偏折远程弹射物。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AegisSpell extends Spell {

    private static final int DURATION = 60;
    private static final float RANGED_MULTIPLIER = 0.25f;

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
        if (!this.warded.isAlive()) {
            WARDED.remove(this.warded.getUUID());
            return;
        }
        if (!this.raised) {
            this.raised = true;
            WARDED.add(this.warded.getUUID());
            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.warded.getX(), this.warded.getY(), this.warded.getZ(),
                        SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.9f, 1.4f);
                Vec3 center = this.warded.position().add(0.0, 1.2, 0.0);
                server.sendParticles(ModParticles.BURST_RING.get(), center.x, center.y, center.z, 1, 0, 0, 0, 0);
            }
        }

        // 每 12 tick 在目标当前位置刷新一次 16 tick 寿命的护盾片段，配合淡入淡出实现移动随身跟随
        if (this.age == 1 || (this.age % 12 == 0 && this.age <= DURATION - 12)) {
            int segLife = Math.min(16, DURATION - this.age + 2);
            PixelfxFx.playLooping(this.level, this.warded.position().add(0.0, 1.2, 0.0),
                    PixelfxSlugs.EPITAPH_AEGIS, segLife, 0.52f, 0.0f);
        }

        // 双螺旋黄金蝶与余晖绕身护卫
        if (this.age % 6 == 0 && this.level instanceof ServerLevel server) {
            Vec3 at = this.warded.position();
            double baseAngle = this.age * 0.22;
            for (int i = 0; i < 6; i++) {
                double angle = baseAngle + Math.PI * 2.0 * i / 6.0;
                double px = at.x + Math.cos(angle) * 1.15;
                double py = at.y + 0.9 + Math.sin(angle * 2.0) * 0.35;
                double pz = at.z + Math.sin(angle) * 1.15;
                if (i % 3 == 0) {
                    server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(), px, py, pz, 1, 0.05, 0.05, 0.05, 0.0);
                } else {
                    server.sendParticles(ModParticles.BURST_DOT.get(), px, py, pz, 1, 0.0, 0.02, 0.0, 0.0);
                }
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

    public static boolean isWarded(LivingEntity entity) {
        return WARDED.contains(entity.getUUID());
    }

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

        // 远程弹射物偏折视觉与音效反馈
        if (living.level() instanceof ServerLevel server) {
            Vec3 impactPos = living.position().add(0.0, living.getBbHeight() * 0.55, 0.0);
            PixelfxFx.play(server, impactPos, PixelfxSlugs.REI_CRIT_HIT, 0.85f, server.random.nextFloat() * 360.0f);
            server.sendParticles(ModParticles.BURST_STAR.get(), impactPos.x, impactPos.y, impactPos.z, 1, 0.1, 0.1, 0.1, 0);
            server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), impactPos.x, impactPos.y, impactPos.z, 3, 0, 0, 0, 0);
            server.playSound(null, impactPos.x, impactPos.y, impactPos.z,
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0f, 1.85f);
        }
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
