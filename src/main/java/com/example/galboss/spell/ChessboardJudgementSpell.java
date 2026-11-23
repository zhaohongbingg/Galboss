package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
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
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 棋盘审判：把施法点所在的整个区块铺成 8×8 的棋盘，黑白格交替点亮并结算。
 */
public class ChessboardJudgementSpell extends Spell {

    private static final int TILE = 2;
    private static final int CHUNK = 16;
    private static final int TILES = CHUNK / TILE;

    private static final int WINDUP = 30;
    private static final int PHASE = 20;
    private static final int RESOLVE_AT = 14;
    private static final int PHASES = 4;

    private static final float DAMAGE = 5.0f;

    private static final DustParticleOptions LIGHT = new DustParticleOptions(new Vector3f(1.0f, 0.85f, 0.35f), 1.25f);
    private static final DustParticleOptions DARK = new DustParticleOptions(new Vector3f(0.55f, 0.22f, 0.82f), 1.15f);

    private final int minX;
    private final int minZ;
    private final double baseY;

    private final Set<Integer> resolved = new HashSet<>();
    private Set<UUID> hit = new HashSet<>();

    public ChessboardJudgementSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        ChunkPos chunk = new ChunkPos(BlockPos.containing(origin));
        this.minX = chunk.x << 4;
        this.minZ = chunk.z << 4;
        this.baseY = origin.y;
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new ChessboardJudgementSpell(level, origin, caster));
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        Vec3 center = new Vec3(this.minX + CHUNK / 2.0, this.baseY, this.minZ + CHUNK / 2.0);

        if (this.age <= WINDUP) {
            if (this.age % 4 == 1) {
                SpellFx.square(server, center, CHUNK / 2.0, LIGHT);
            }
            if (this.age == 1) {
                server.playSound(null, center.x, center.y, center.z,
                        SoundEvents.EVOKER_CAST_SPELL,
                        SoundSource.HOSTILE, 1.3f, 0.8f);
                PixelfxFx.play(server, center.add(0.0, 0.1, 0.0),
                        PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, 3.2f, 0.0f);
            }
            return;
        }

        int phase = (this.age - WINDUP - 1) / PHASE;
        if (phase >= PHASES) {
            return;
        }
        int local = (this.age - WINDUP - 1) % PHASE;

        if (local < RESOLVE_AT && local % 4 == 0) {
            this.drawTiles(server, phase, local >= 8);
        }
        if (local == RESOLVE_AT && this.resolved.add(phase)) {
            this.resolve(server, center, phase);
        }
    }

    /** 画出每个 2×2 激活格子的四边边界与中心点，蓄力后半段升起微光预警。 */
    private void drawTiles(ServerLevel server, int phase, boolean imminent) {
        int parity = phase % 2;
        DustParticleOptions dust = parity == 0 ? LIGHT : DARK;
        double y = this.baseY + 0.14;

        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                if ((tx + tz) % 2 != parity) {
                    continue;
                }
                double x0 = this.minX + tx * TILE + 0.15;
                double x1 = this.minX + (tx + 1) * TILE - 0.15;
                double z0 = this.minZ + tz * TILE + 0.15;
                double z1 = this.minZ + (tz + 1) * TILE - 0.15;
                double cx = (x0 + x1) * 0.5;
                double cz = (z0 + z1) * 0.5;

                // 四边中点勾勒 2×2 方格轮廓，使安全格与危险格清晰可辨
                server.sendParticles(dust, cx, y, z0, 1, 0.0, 0.0, 0.0, 0.0);
                server.sendParticles(dust, cx, y, z1, 1, 0.0, 0.0, 0.0, 0.0);
                server.sendParticles(dust, x0, y, cz, 1, 0.0, 0.0, 0.0, 0.0);
                server.sendParticles(dust, x1, y, cz, 1, 0.0, 0.0, 0.0, 0.0);

                if (imminent) {
                    server.sendParticles(parity == 0 ? ModParticles.BURST_DOT.get() : ParticleTypes.WITCH,
                            cx, y + 0.25, cz, 1, 0.25, 0.15, 0.25, 0.02);
                }
            }
        }
    }

    private void resolve(ServerLevel server, Vec3 center, int phase) {
        this.hit = new HashSet<>();
        int parity = phase % 2;
        AABB box = new AABB(this.minX, this.baseY - 2.0, this.minZ,
                this.minX + CHUNK, this.baseY + 6.0, this.minZ + CHUNK);

        int hits = 0;
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            int tx = (int) Math.floor((player.getX() - this.minX) / TILE);
            int tz = (int) Math.floor((player.getZ() - this.minZ) / TILE);
            if (tx < 0 || tx >= TILES || tz < 0 || tz >= TILES) {
                continue;
            }
            if ((tx + tz) % 2 != parity) {
                continue;
            }
            if (!this.hit.add(player.getUUID())) {
                continue;
            }
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), DAMAGE);
            hits++;
        }

        int activeIdx = 0;
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                if ((tx + tz) % 2 != parity) {
                    continue;
                }
                double x = this.minX + tx * TILE + TILE / 2.0;
                double z = this.minZ + tz * TILE + TILE / 2.0;

                if (parity == 0) {
                    server.sendParticles(ParticleTypes.END_ROD,
                            x, this.baseY + 0.65, z, 3, 0.35, 0.55, 0.35, 0.03);
                } else {
                    server.sendParticles(ParticleTypes.WITCH,
                            x, this.baseY + 0.65, z, 4, 0.40, 0.50, 0.40, 0.04);
                }

                // 均匀采样 8 个格子爆发蝴蝶与暴击闪光，兼顾满屏华丽度与 Pixelfx 上限
                if (activeIdx % 4 == (phase % 4)) {
                    server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                            x, this.baseY + 0.3, z, 2, 0.0, 0.0, 0.0, 0.0);
                    PixelfxFx.play(server, new Vec3(x, this.baseY + 0.2, z),
                            PixelfxSlugs.REI_CRIT_HIT, 0.75f, server.random.nextFloat() * 360.0f);
                }
                activeIdx++;
            }
        }
        server.playSound(null, center.x, center.y, center.z,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.HOSTILE, 1.0f, parity == 0 ? 0.85f : 0.65f);
        SpellFx.shake(server, center, 28.0, 0.32f, 5);
        com.example.galboss.GalBoss.LOGGER.debug("[Spell] chessboard phase {} hit {} player(s)", phase, hits);
    }

    @Override
    public int durationTicks() {
        return WINDUP + PHASE * PHASES + 10;
    }

    @Override
    public String id() {
        return "chessboard_judgement";
    }
}
