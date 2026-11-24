package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 金雨：在施法点所在的整个区块内持续落下金色蝶雨。
 */
public class GoldenRainSpell extends Spell {

    private static final int CHUNK = 16;
    private static final int DURATION = 140;
    private static final int DROP_INTERVAL = 8;
    private static final int DROP_TELEGRAPH = 6;

    private static final float DAMAGE = 2.0f;
    private static final double DROP_RADIUS = 1.9;
    private static final int PER_PLAYER_GAP = 8;

    private final int minX;
    private final int minZ;
    private final double baseY;

    private final List<Drop> pending = new ArrayList<>();
    private final Map<UUID, Integer> lastHit = new HashMap<>();

    private boolean opened;

    private record Drop(Vec3 at, int landAge) {
    }

    public GoldenRainSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        ChunkPos chunk = new ChunkPos(BlockPos.containing(origin));
        this.minX = chunk.x << 4;
        this.minZ = chunk.z << 4;
        this.baseY = origin.y;
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new GoldenRainSpell(level, origin, caster));
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        Vec3 center = new Vec3(this.minX + CHUNK / 2.0, this.baseY, this.minZ + CHUNK / 2.0);

        if (!this.opened) {
            this.opened = true;
            SpellFx.square(server, center, CHUNK / 2.0, SpellFx.GOLD);
            server.playSound(null, center.x, center.y, center.z,
                    SoundEvents.AMETHYST_BLOCK_RESONATE,
                    SoundSource.HOSTILE, 1.2f, 1.3f);
            // 区块四角升起金色蝶群界标
            double[] cornersX = {this.minX + 0.5, this.minX + CHUNK - 0.5};
            double[] cornersZ = {this.minZ + 0.5, this.minZ + CHUNK - 0.5};
            for (double cx : cornersX) {
                for (double cz : cornersZ) {
                    server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                            cx, this.baseY + 1.2, cz, 4, 0.3, 1.2, 0.3, 0.01);
                }
            }
        }

        if (this.age % DROP_INTERVAL == 0 && this.age <= DURATION - DROP_TELEGRAPH) {
            Vec3 target = this.randomPoint(server);
            this.pending.add(new Drop(target, this.age + DROP_TELEGRAPH));
            // 高空生成盘旋下坠的领航黄金蝶
            server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                    target.x, target.y + 5.2, target.z,
                    0, 0.0, -0.45, 0.0, 1.0);
        }

        for (Drop drop : this.pending) {
            int rem = drop.landAge() - this.age;
            if (rem > 0) {
                if (this.age % 2 == 0) {
                    SpellFx.circle(server, drop.at(), DROP_RADIUS, SpellFx.GOLD);
                }
                double fallY = drop.at().y + (rem / (double) DROP_TELEGRAPH) * 4.8;
                server.sendParticles(ModParticles.BURST_DOT.get(),
                        drop.at().x, fallY, drop.at().z, 2, 0.22, 0.15, 0.22, 0.01);
            }
        }

        this.pending.removeIf(drop -> {
            if (drop.landAge() > this.age) {
                return false;
            }
            this.land(server, drop);
            return true;
        });
    }

    private Vec3 randomPoint(ServerLevel server) {
        // 35% 概率偏向区块内的存活玩家附近，提升走位压迫感
        if (server.random.nextFloat() < 0.35f) {
            AABB chunkBox = new AABB(this.minX, this.baseY - 4.0, this.minZ,
                    this.minX + CHUNK, this.baseY + 8.0, this.minZ + CHUNK);
            List<Player> targets = server.getEntitiesOfClass(Player.class, chunkBox, SpellFx::isValidPlayerTarget);
            if (!targets.isEmpty()) {
                Player chosen = targets.get(server.random.nextInt(targets.size()));
                double jx = Math.max(this.minX + 1.0, Math.min(this.minX + CHUNK - 1.0,
                        chosen.getX() + (server.random.nextDouble() - 0.5) * 4.0));
                double jz = Math.max(this.minZ + 1.0, Math.min(this.minZ + CHUNK - 1.0,
                        chosen.getZ() + (server.random.nextDouble() - 0.5) * 4.0));
                return SpellFx.snapToGround(server, new Vec3(jx, this.baseY, jz), 3, 5);
            }
        }
        double x = this.minX + 1.0 + server.random.nextDouble() * (CHUNK - 2.0);
        double z = this.minZ + 1.0 + server.random.nextDouble() * (CHUNK - 2.0);
        return SpellFx.snapToGround(server, new Vec3(x, this.baseY, z), 3, 5);
    }

    private void land(ServerLevel server, Drop drop) {
        Vec3 at = drop.at();
        float randomYaw = server.random.nextFloat() * 360.0f;
        PixelfxFx.play(server, at.add(0.0, 0.15, 0.0), PixelfxSlugs.REI_CRIT_HIT, 0.72f, randomYaw);
        server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                at.x, at.y + 0.2, at.z, 2, 0.0, 0.0, 0.0, 0.0);
        server.sendParticles(ParticleTypes.END_ROD,
                at.x, at.y + 0.45, at.z, 4, 0.30, 0.35, 0.30, 0.03);
        server.playSound(null, at.x, at.y, at.z,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.HOSTILE, 0.35f, 1.7f + server.random.nextFloat() * 0.25f);

        SpellFx.magicPeriodicPlayers(server, at, DROP_RADIUS, DAMAGE, this.caster,
                this.lastHit, this.age, PER_PLAYER_GAP);
    }

    @Override
    public int durationTicks() {
        return DURATION + 4;
    }

    @Override
    public String id() {
        return "golden_rain";
    }
}
