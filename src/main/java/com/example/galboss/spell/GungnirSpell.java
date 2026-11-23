package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gungnir（奥丁的永恒之枪）：空中显形蓄力 → 沿 24 格直线疾驰贯地。
 */
public class GungnirSpell extends Spell {

    private static final int WARN = 24;
    private static final int HIT_TICK = 30;
    private static final double LENGTH = 24.0;
    private static final float DAMAGE = 14.0f;
    private static final double HALF_WIDTH = 1.5;
    private static final int IMPACT_NODES = 7;

    /**
     * 每个贯地落点上再插一把枪的缩放。主枪是 1.55（约 11.6 格高），
     * 落点枪用 1.0（约 7.5 格），沿 24 格弹道每 4 格一把，不至于把画面糊死。
     */
    private static final float NODE_SPEAR_SCALE = 1.0f;

    private final Vec3 forward;
    private final Vec3 end;
    private final float yaw;
    private final Set<UUID> hit = new HashSet<>();
    private boolean fired;
    private boolean resolved;

    public GungnirSpell(Level level, Vec3 origin, @Nullable LivingEntity caster, @Nullable LivingEntity target) {
        super(level, origin, caster);
        Vec3 dir = target == null
                ? (caster == null ? new Vec3(0, 0, 1) : caster.getLookAngle())
                : target.position().subtract(origin);
        Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        this.forward = flat.normalize();
        this.end = origin.add(this.forward.scale(LENGTH));
        this.yaw = directionToFxYaw(this.forward);
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster, @Nullable LivingEntity target) {
        SpellEngine.cast(new GungnirSpell(level, origin, caster, target));
    }

    @Override
    protected void step() {
        if (this.age <= WARN) {
            if (this.age % 4 == 1) {
                SpellFx.line(this.level, this.origin, this.end, 1.2, HALF_WIDTH);
                // 中心瞄准基准线，强化贯通轴线读感
                SpellFx.line(this.level, this.origin, this.end, 2.0, 0.0);
            }
            if (this.age == 1 || this.age == 14) {
                SpellFx.circle(this.level, this.origin.add(0.0, 0.1, 0.0), 2.2, SpellFx.GOLD);
                if (this.level instanceof ServerLevel server) {
                    server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                            this.origin.x, this.origin.y + 2.4, this.origin.z,
                            6, 0.8, 0.6, 0.8, 0.02);
                }
            }
            return;
        }

        if (!this.fired) {
            this.fired = true;
            // 枪尖直接压到地面：epitaph_gungnir 的 pivot 在枪尖（json pivot 在帧右端），
            // roll=-90 之后枪身自 pivot 向上延伸约 11 格，所以把 pivot 放在地表高度
            // 就得到「自天上贯地插入」的读感。原来的 (0,3,0) 会让枪尖悬在半空。
            Vec3 spearTip = this.origin.add(this.forward.scale(1.0)).add(0.0, 0.05, 0.0);
            PixelfxFx.play(this.level, spearTip, PixelfxSlugs.EPITAPH_GUNGNIR, 1.55f, this.yaw);
            // 已移除 epitaph_spear_fall（ice/icicle-fall 地刺）：与插地后的枪体重复，
            // 而且它是「从天上掉下来」的方向，与枪已贯地的状态冲突。

            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.origin.x, this.origin.y, this.origin.z,
                        SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1.25f, 0.72f);
                server.sendParticles(ModParticles.BURST_STAR.get(),
                        spearTip.x, spearTip.y + 0.5, spearTip.z, 3, 0.2, 0.2, 0.2, 0.0);
            }
            SpellFx.shake(this.level, this.origin, 48.0, 0.65f, 10);
        }

        // 沿 24 格弹道逐 tick 向前推进贯地裂痕与蝴蝶尾流（WARN+1 ~ WARN+7）
        int waveIndex = this.age - (WARN + 1);
        if (waveIndex >= 0 && waveIndex < IMPACT_NODES) {
            double progress = waveIndex / (double) (IMPACT_NODES - 1);
            Vec3 nodePos = this.origin.lerp(this.end, progress);

            // 每个落点上方再插下一把枪：divine-lance 的 pivot 在枪尖，roll=-90 之后
            // 它自带的「前刺」动画正好读作自上而下贯地，所以把枪尖放在地表即可。
            // 7 把枪共用同一张贴图，合批渲染器会把它们并成 1 次 Draw Call。
            PixelfxFx.play(this.level, nodePos.add(0.0, 0.05, 0.0),
                    PixelfxSlugs.EPITAPH_GUNGNIR, NODE_SPEAR_SCALE, this.yaw);

            PixelfxFx.play(this.level, nodePos.add(0.0, 0.05, 0.0),
                    PixelfxSlugs.EPITAPH_SPEAR_IMPACT, 0.9f, this.yaw);

            if (this.level instanceof ServerLevel server) {
                server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                        nodePos.x, nodePos.y + 0.6, nodePos.z,
                        2, 0.45, 0.3, 0.45, 0.04);
                server.sendParticles(ParticleTypes.END_ROD,
                        nodePos.x, nodePos.y + 0.4, nodePos.z,
                        4, 0.35, 0.2, 0.35, 0.05);
            }
        }

        if (this.age >= HIT_TICK && !this.resolved) {
            this.resolved = true;
            AABB box = this.lineBox();
            int hits = SpellFx.magicBox(this.level, box, DAMAGE, this.caster, this.hit, 0, 0, 1.2);
            if (this.level instanceof ServerLevel server) {
                Vec3 tipPos = this.end.add(0.0, 0.5, 0.0);
                server.sendParticles(ModParticles.BURST_RING.get(), tipPos.x, tipPos.y, tipPos.z, 1, 0, 0, 0, 0);
                server.sendParticles(ModParticles.BURST_BUTTERFLY.get(), tipPos.x, tipPos.y, tipPos.z, 5, 0.2, 0.2, 0.2, 0);
            }
            com.example.galboss.GalBoss.LOGGER.debug("[Spell] gungnir hit {} target(s)", hits);
        }
    }

    private AABB lineBox() {
        double minX = Math.min(this.origin.x, this.end.x) - HALF_WIDTH;
        double maxX = Math.max(this.origin.x, this.end.x) + HALF_WIDTH;
        double minZ = Math.min(this.origin.z, this.end.z) - HALF_WIDTH;
        double maxZ = Math.max(this.origin.z, this.end.z) + HALF_WIDTH;
        double minY = this.origin.y - 1.0;
        double maxY = this.origin.y + 3.0;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public int durationTicks() {
        return HIT_TICK + 10;
    }

    @Override
    public String id() {
        return "gungnir";
    }
}
