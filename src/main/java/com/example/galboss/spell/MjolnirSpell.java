package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mjolnir（索尔的雷霆之锤）：收缩预警圈 → 战锤砸落 → 内外双环重击与余震冲击波。
 */
public class MjolnirSpell extends Spell {

    private static final int WARN = 24;
    private static final double INNER_RADIUS = 6.0;
    private static final float INNER_DAMAGE = 10.0f;
    private static final double OUTER_RADIUS = 12.0;
    private static final float OUTER_DAMAGE = 5.0f;

    private final Vec3 at;
    private final float impactYaw;
    private final Set<UUID> hit = new HashSet<>();
    private boolean landed;

    public MjolnirSpell(Level level, Vec3 at, @Nullable LivingEntity caster) {
        super(level, at, caster);
        this.at = at;
        if (caster != null) {
            Vec3 fromCaster = at.subtract(caster.position());
            this.impactYaw = directionToFxYaw(fromCaster);
        } else {
            this.impactYaw = 0.0f;
        }
    }

    public static void cast(Level level, Vec3 at, @Nullable LivingEntity caster) {
        SpellEngine.cast(new MjolnirSpell(level, at, caster));
    }

    @Override
    protected void step() {
        if (this.age <= WARN) {
            if (this.age % 4 == 1) {
                SpellFx.circle(this.level, this.at, INNER_RADIUS, SpellFx.GOLD);
                SpellFx.circle(this.level, this.at, OUTER_RADIUS, SpellFx.SPARK);
                SpellFx.cross(this.level, this.at, INNER_RADIUS);

                // 随蓄力进度向外收缩的倒计时提示圈
                double shrinkRatio = 1.0 - (this.age / (double) WARN);
                double countdownRadius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * shrinkRatio;
                SpellFx.circle(this.level, this.at, countdownRadius, SpellFx.GOLD);
            }
            if (this.age % 6 == 0 && this.level instanceof ServerLevel server) {
                server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                        this.at.x, this.at.y + 0.4, this.at.z,
                        4, 2.5, 0.6, 2.5, 0.02);
            }
            return;
        }

        // 砸落主冲击与两段扩散余震
        if (!this.landed) {
            this.landed = true;
            PixelfxFx.play(this.level, this.at.add(0.0, 0.05, 0.0),
                    PixelfxSlugs.EPITAPH_MJOLNIR, 1.45f, this.impactYaw);
            PixelfxFx.play(this.level, this.at.add(0.0, 0.05, 0.0),
                    PixelfxSlugs.TOHKA_BELL_WAVE, 2.2f, this.impactYaw);
            PixelfxFx.play(this.level, this.at.add(0.0, 0.06, 0.0),
                    PixelfxSlugs.EPITAPH_SPEAR_IMPACT, 1.35f, this.impactYaw);

            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.at.x, this.at.y, this.at.z, SoundEvents.ANVIL_LAND,
                        SoundSource.HOSTILE, 1.4f, 0.5f);
                server.playSound(null, this.at.x, this.at.y, this.at.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
                        SoundSource.HOSTILE, 1.0f, 0.8f);
                server.sendParticles(ModParticles.BURST_STAR.get(),
                        this.at.x, this.at.y + 0.5, this.at.z, 3, 0.4, 0.3, 0.4, 0.0);
                server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                        this.at.x, this.at.y + 0.4, this.at.z, 10, 1.6, 0.4, 1.6, 0.0);
            }
            SpellFx.shake(this.level, this.at, 56.0, 0.75f, 12);

            int inner = SpellFx.magic(this.level, this.at, INNER_RADIUS, INNER_DAMAGE,
                    this.caster, this.hit, 40, 1, 0.45);
            int outer = SpellFx.magic(this.level, this.at, OUTER_RADIUS, OUTER_DAMAGE,
                    this.caster, this.hit, 0, 0, 0.25);
            com.example.galboss.GalBoss.LOGGER.debug("[Spell] mjolnir inner={} outer={}", inner, outer);
        } else {
            int afterImpact = this.age - WARN - 1;
            if (afterImpact == 2 || afterImpact == 4 || afterImpact == 6) {
                // 余震沿地面从内圈推到外圈；只有砸落瞬间结算伤害。
                double radius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * afterImpact / 6.0;
                SpellFx.circle(this.level, this.at, radius, SpellFx.SPARK, 24);
            }
        }
    }

    @Override
    public int durationTicks() {
        return WARN + 12;
    }

    @Override
    public String id() {
        return "mjolnir";
    }
}
