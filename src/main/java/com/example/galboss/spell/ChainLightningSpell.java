package com.example.galboss.spell;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * L2 链雷：一道电从施法者/原点跳到最近的人，然后每跳最多 8 格继续跳，伤害递减。
 *
 * <p>目标链在**构造时**定死（不是每跳重新找），所以玩家在 20 tick 内散开就没第二跳——
 * 这就是它的反制方式。节点之间用 {@code ELECTRIC_SPARK} 连线，读得出「电顺着连过去」。
 */
public class ChainLightningSpell extends Spell {

    /** 每跳间隔（tick）。 */
    private static final int HOP_GAP = 6;
    /** 每跳最大跳跃距离（格）。 */
    private static final double HOP_RANGE = 8.0;
    /** 最多几跳（含第一跳）。 */
    private static final int MAX_HOPS = 3;
    /** 第 1/2/3 跳伤害。 */
    private static final float[] HOP_DAMAGE = {9.0f, 6.0f, 4.0f};

    private final List<LivingEntity> chain = new ArrayList<>();
    private final Set<UUID> hit = new HashSet<>();
    private int nextHop;

    public ChainLightningSpell(Level level, Vec3 origin, @Nullable LivingEntity caster,
                               @Nullable LivingEntity firstTarget) {
        super(level, origin, caster);
        this.buildChain(firstTarget);
    }

    /** 直接以「最近的玩家」为首个目标施放。 */
    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        Player nearest = null;
        double best = 24.0 * 24.0;
        for (Player player : SpellFx.players(level, origin, 24.0)) {
            double d = player.position().distanceToSqr(origin);
            if (d < best) {
                best = d;
                nearest = player;
            }
        }
        SpellEngine.cast(new ChainLightningSpell(level, origin, caster, nearest));
    }

    private void buildChain(@Nullable LivingEntity first) {
        LivingEntity current = first;
        for (int i = 0; i < MAX_HOPS && current != null; i++) {
            if (this.chain.contains(current)) {
                break;
            }
            this.chain.add(current);
            current = i + 1 < MAX_HOPS ? this.nearestOther(current) : null;
        }
    }

    @Nullable
    private LivingEntity nearestOther(LivingEntity from) {
        LivingEntity best = null;
        double bestDist = HOP_RANGE * HOP_RANGE;
        var box = from.getBoundingBox().inflate(HOP_RANGE);
        for (LivingEntity candidate : this.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!this.isChainCandidate(from, candidate)) {
                continue;
            }
            double d = candidate.distanceToSqr(from);
            if (d <= bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isChainCandidate(LivingEntity from, LivingEntity candidate) {
        if (candidate == from || !candidate.isAlive() || candidate.isSpectator()
                || candidate == this.caster || candidate instanceof ArmorStand
                || this.chain.contains(candidate)) {
            return false;
        }
        if (candidate instanceof Player player) {
            return !player.isCreative();
        }
        if (from instanceof Player) {
            return candidate instanceof Enemy;
        }
        return candidate instanceof Mob;
    }

    @Override
    protected void step() {
        if (this.chain.isEmpty()) {
            return;
        }
        int hop = (this.age - 1) / HOP_GAP;
        if (hop != this.nextHop || hop >= this.chain.size()) {
            return;
        }
        this.nextHop = hop;
        LivingEntity target = this.chain.get(hop);
        if (!target.isAlive()) {
            return;
        }
        Vec3 from = hop == 0
                ? this.origin
                : this.chain.get(hop - 1).position().add(0.0, 1.0, 0.0);
        Vec3 to = target.position().add(0.0, 1.0, 0.0);

        this.sparkLine(this.level, from, to);
        com.example.galboss.fx.PixelfxFx.play(this.level, to,
                com.example.galboss.fx.PixelfxSlugs.REI_BULLET_IMPACT, 0.8f, 0.0f);
        if (this.level instanceof ServerLevel server) {
            server.playSound(null, to.x, to.y, to.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
                    SoundSource.HOSTILE, 0.6f, 1.4f);
        }
        SpellFx.magic(this.level, to, 1.2, HOP_DAMAGE[Math.min(hop, HOP_DAMAGE.length - 1)],
                this.caster, this.hit, 10, 1, 0.0);
    }

    /** 两个节点之间的电光：沿直线每 0.4 格一颗，带一点横向抖动。包级共享给魂链复用。 */
    static void sparkLine(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Vec3 delta = to.subtract(from);
        int steps = Math.max(4, (int) (delta.length() / 0.4));
        for (int i = 0; i <= steps; i++) {
            Vec3 at = from.add(delta.scale(i / (double) steps));
            double jitter = 0.12;
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    at.x + (level.random.nextDouble() - 0.5) * jitter,
                    at.y + (level.random.nextDouble() - 0.5) * jitter,
                    at.z + (level.random.nextDouble() - 0.5) * jitter,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public int durationTicks() {
        return Math.max(HOP_GAP, this.chain.size() * HOP_GAP) + 4;
    }

    @Override
    public String id() {
        return "chain_lightning";
    }
}
