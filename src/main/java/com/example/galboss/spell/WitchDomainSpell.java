package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 魔女领域：以施法点为中心张开一个<b>持续收缩</b>的审判圈。
 *
 * <h2>机制</h2>
 * 圈从半径 8 缩到 1.5（正好覆盖一个区块的对角范围），<b>站在圈外就挨打</b>。
 * 这是唯一一个「强制位移」而不是「强制躲闪」的招式：别的招式要你走开，
 * 这个要你往中间挤 —— 和她的蝶群（追着你跑）刚好一推一拉，逼玩家在两种压力间切换。
 *
 * <h2>为什么不直接结算全场</h2>
 * 收缩给了明确的进度反馈（圈每 tick 都在变小），玩家能读出「还有多久收完」。
 * 一次性大圈伤害只会变成「听到声音就跑」，没有节奏感。
 *
 * <h2>收尾</h2>
 * 收到底时中心炸一发（{@code epitaph_aegis} 立牌 + 冲击环），圈内的人吃满伤害 ——
 * 「挤进来」不是免费的，最后一秒还得再扛一下。
 */
public class WitchDomainSpell extends Spell {

    /** 起始半径（格）。8 = 直径 16，正好一个区块。 */
    private static final double RADIUS_START = 8.0;
    /** 收缩到的最终半径。 */
    private static final double RADIUS_END = 1.5;
    /** 张开到开始收缩之间的停顿（tick），给玩家反应时间。 */
    private static final int WINDUP = 20;
    /** 收缩过程时长（tick）。 */
    private static final int SHRINK = 100;
    /** 圈外扣血的间隔（tick）。 */
    private static final int TICK_DAMAGE_INTERVAL = 10;
    /** 圈外每次扣血。100 tick / 10 = 10 次，全吃 = 25。 */
    private static final float OUTSIDE_DAMAGE = 2.5f;
    /** 收尾时圈内伤害。 */
    private static final float FINAL_DAMAGE = 10.0f;

    /** 审判圈颜色（金色）。 */
    private static final DustParticleOptions RING = new DustParticleOptions(new Vector3f(1.0f, 0.82f, 0.28f), 1.1f);

    private final Vec3 center;
    /** 玩家 UUID → 上次圈外扣血的 age。 */
    private final Map<UUID, Integer> lastHit = new HashMap<>();
    private boolean finished;

    public WitchDomainSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        this.center = origin;
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new WitchDomainSpell(level, origin, caster));
    }

    /** 当前审判圈半径。 */
    private double currentRadius() {
        if (this.age <= WINDUP) {
            return RADIUS_START;
        }
        double progress = Math.min(1.0, (this.age - WINDUP) / (double) SHRINK);
        return RADIUS_START + (RADIUS_END - RADIUS_START) * progress;
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        double radius = this.currentRadius();

        // 圈本身：每 2 tick 重画一次（半径一直在变，画太密会糊成一片）
        if (this.age % 2 == 0) {
            SpellFx.circle(server, this.center, radius, RING);
        }

        if (this.age == 1) {
            server.playSound(null, this.center.x, this.center.y, this.center.z,
                    net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                    net.minecraft.sounds.SoundSource.HOSTILE, 1.1f, 0.7f);
            PixelfxFx.play(server, this.center.add(0.0, 0.1, 0.0),
                    PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, 3.0f, 0.0f);
        }

        // 圈外扣血（收尾之后不再扣，交给 finalBurst）
        if (this.age > WINDUP && this.age % TICK_DAMAGE_INTERVAL == 0 && !this.finished) {
            this.hurtOutside(server, radius);
        }

        // 收到底：炸一发
        if (!this.finished && this.age >= WINDUP + SHRINK) {
            this.finished = true;
            this.finalBurst(server);
        }
    }

    /** 圈外的人扣血；圈内的人安全。 */
    private void hurtOutside(ServerLevel server, double radius) {
        AABB box = new AABB(this.center, this.center).inflate(RADIUS_START + 2.0);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (player.position().distanceTo(this.center) <= radius) {
                continue;
            }
            Integer last = this.lastHit.get(player.getUUID());
            if (last != null && this.age - last < TICK_DAMAGE_INTERVAL - 1) {
                continue;
            }
            this.lastHit.put(player.getUUID(), this.age);
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), OUTSIDE_DAMAGE);
            // 圈外的提示：脚下一圈暗紫，读得出「你站在审判范围外」
            server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    player.getX(), player.getY() + 0.2, player.getZ(), 6, 0.3, 0.1, 0.3, 0.01);
        }
    }

    /** 收到底的收尾：中心立牌 + 冲击环 + 圈内满伤害。 */
    private void finalBurst(ServerLevel server) {
        PixelfxFx.play(server, this.center.add(0.0, 0.5, 0.0),
                PixelfxSlugs.EPITAPH_AEGIS, 0.5f, 0.0f);
        PixelfxFx.play(server, this.center.add(0.0, 0.05, 0.0),
                PixelfxSlugs.TOHKA_BELL_WAVE, 1.6f, 0.0f);
        server.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                this.center.x, this.center.y + 0.5, this.center.z, 1, 0.0, 0.0, 0.0, 0.0);
        server.playSound(null, this.center.x, this.center.y, this.center.z,
                net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                net.minecraft.sounds.SoundSource.HOSTILE, 1.4f, 0.7f);
        SpellFx.shake(server, this.center, 32.0, 0.5f, 8);

        AABB box = new AABB(this.center, this.center).inflate(RADIUS_END + 1.5);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (player.position().distanceTo(this.center) > RADIUS_END + 1.0) {
                continue;
            }
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), FINAL_DAMAGE);
        }
    }

    @Override
    public int durationTicks() {
        return WINDUP + SHRINK + 12;
    }

    @Override
    public String id() {
        return "witch_domain";
    }
}
