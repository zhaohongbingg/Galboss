package com.example.galboss.spell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
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
 * 棋盘审判：把施法点所在的<b>整个区块</b>铺成 8×8 的棋盘，黑白格交替点亮并结算。
 *
 * <h2>为什么是棋盘</h2>
 * 这是「黄金魔女」和棋盘竞技场之间唯一说得通的联系：她的战场本身就是棋盘，
 * 所以大范围招式的形状应该<b>借用棋盘</b>而不是随便画个圆。玩家也天然读得懂
 * 「这轮亮黑格，下一轮亮白格」——躲法明确，但需要一直移动。
 *
 * <h2>为什么按「相位」而不是一次性全炸</h2>
 * 一次性 64 格全结算等于无条件全场伤害，没有交互；分成 4 个相位、每相位只亮一半，
 * 玩家每 20 tick 必须换一次格子。跑动 8 格就能跨到另一个相位安全区，
 * 站着不动必吃 —— 和 Mjolnir 的「跑动必躲、站桩必吃」是同一条设计语言。
 *
 * <h2>性能</h2>
 * 结算<b>不逐格查实体</b>：只遍历区块内的玩家，用坐标反推格子奇偶性。
 * 64 次 {@code getEntitiesOfClass} 是没必要的，一次遍历就够。
 */
public class ChessboardJudgementSpell extends Spell {

    /** 每格边长（方块）。16 / 2 = 8 格 → 8×8 = 64 个格子，正好覆盖一个区块。 */
    private static final int TILE = 2;
    /** 区块边长。 */
    private static final int CHUNK = 16;
    /** 每边格子数。 */
    private static final int TILES = CHUNK / TILE;

    /** 开场：区块边框亮起来，告诉玩家「整块区域都是战场」。 */
    private static final int WINDUP = 30;
    /** 每个相位的长度（tick）。 */
    private static final int PHASE = 20;
    /** 相位内第几 tick 结算（留出 14 tick 给玩家跑）。 */
    private static final int RESOLVE_AT = 14;
    /** 相位数量：黑白各两轮。 */
    private static final int PHASES = 4;

    /** 每格伤害。4 个相位全吃 = 20，但正常只能吃到 1~2 个相位。 */
    private static final float DAMAGE = 5.0f;

    /** 白格 / 黑格的点缀色（金色与暗紫，和她的调色板一致）。 */
    private static final DustParticleOptions LIGHT = new DustParticleOptions(new Vector3f(1.0f, 0.85f, 0.35f), 1.2f);
    private static final DustParticleOptions DARK = new DustParticleOptions(new Vector3f(0.45f, 0.25f, 0.65f), 1.0f);

    /** 区块范围（施法时算一次，后面每 tick 都用）。 */
    private final int minX;
    private final int minZ;
    private final double baseY;

    /** 已经结算过的相位，防止同一相位结算两次。 */
    private final Set<Integer> resolved = new HashSet<>();
    /** 本轮已命中（每相位重置）。 */
    private Set<UUID> hit = new HashSet<>();

    public ChessboardJudgementSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        ChunkPos chunk = new ChunkPos(BlockPos.containing(origin));
        // 用位移而不是 ChunkPos#getMinBlockX()：字段是公开的，跨版本最稳
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
                        net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL,
                        net.minecraft.sounds.SoundSource.HOSTILE, 1.3f, 0.8f);
            }
            return;
        }

        int phase = (this.age - WINDUP - 1) / PHASE;
        if (phase >= PHASES) {
            return;
        }
        int local = (this.age - WINDUP - 1) % PHASE;

        // 预警：本轮要亮的格子画出来
        if (local % 4 == 0) {
            this.drawTiles(server, phase);
        }
        // 结算
        if (local == RESOLVE_AT && this.resolved.add(phase)) {
            this.resolve(server, center, phase);
        }
    }

    /** 画出本相位要结算的格子（奇偶性与相位一致的那一半）。 */
    private void drawTiles(ServerLevel server, int phase) {
        int parity = phase % 2;
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                if ((tx + tz) % 2 != parity) {
                    continue;
                }
                double x = this.minX + tx * TILE + TILE / 2.0;
                double z = this.minZ + tz * TILE + TILE / 2.0;
                server.sendParticles(parity == 0 ? LIGHT : DARK, x, this.baseY + 0.15, z,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * 结算：只遍历区块内的玩家，用坐标反推格子奇偶性。
     *
     * <p>不打自己人：她的召唤物、祭品都在场上，逐格 {@code SpellFx.magic} 会把它们一起劈了。
     */
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

        // 结算瞬间的视觉：亮起来的那一半格子炸一下（用粒子，不走 pixelfx，64 个实例会顶满上限）
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                if ((tx + tz) % 2 != parity) {
                    continue;
                }
                double x = this.minX + tx * TILE + TILE / 2.0;
                double z = this.minZ + tz * TILE + TILE / 2.0;
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                        x, this.baseY + 0.6, z, 3, 0.4, 0.5, 0.4, 0.02);
            }
        }
        server.playSound(null, center.x, center.y, center.z,
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0f, 0.7f);
        SpellFx.shake(server, center, 24.0, 0.22f, 4);
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
