package com.example.galboss.spell;

import com.example.galboss.block.ModBlocks;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 棋盘场地：让 boss 场地**自带的实体棋盘**交替变危险。
 *
 * <p>关键设计：**不自己画格子**。场地本来就是 {@code arena_black} / {@code arena_white}
 * 方块搭的棋盘，如果再叠一层粒子网格，两套格子会错位——玩家看到的颜色和伤害判定对不上。
 * 所以这里直接读玩家脚下的方块：站黑块算黑格，站白块算白格，站灰石/深板岩/圈外都安全。
 *
 * <p>格子大小、棋盘尺寸、边界形状全部交给场地本身，代码里一个数字都不用写。
 */
public class ChessboardFieldSpell extends Spell {

    /** 一个完整周期：危险 → 预警 → 翻转。 */
    private static final int CYCLE = 100;
    /** 翻转前的预警时长。 */
    private static final int WARN = 24;
    /** 站在危险格上的扣血间隔。 */
    private static final int DAMAGE_INTERVAL = 20;
    private static final float DAMAGE = 4.0f;
    /** 总持续时长（30 秒）。 */
    private static final int DURATION = 600;

    /** 作用半径：超出这个范围不结算，防止跑到场地外还挨打。 */
    private static final double FIELD_RADIUS = 40.0;

    /** 预警扫描半径（格）与刷新间隔——只扫玩家附近，别把整块场地都点亮。 */
    private static final int WARN_SCAN = 8;
    private static final int WARN_INTERVAL = 8;

    private static final DustParticleOptions DARK =
            new DustParticleOptions(new Vector3f(0.55f, 0.22f, 0.82f), 1.15f);
    private static final DustParticleOptions LIGHT =
            new DustParticleOptions(new Vector3f(1.0f, 0.85f, 0.35f), 1.25f);

    private final Vec3 center;
    private final Map<UUID, Integer> lastHit = new HashMap<>();
    private boolean opened;
    private int lastCycle = -1;

    public ChessboardFieldSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        this.center = origin;
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new ChessboardFieldSpell(level, origin, caster));
    }

    /** 当前周期索引（每 CYCLE tick 翻一次）。 */
    private int cycleIndex() {
        return Math.max(0, this.age - 1) / CYCLE;
    }

    /** 当前危险色：0 = 白块，1 = 黑块。 */
    private int activeParity() {
        return this.cycleIndex() % 2;
    }

    private int localAge() {
        return Math.max(0, this.age - 1) % CYCLE;
    }

    private boolean warning() {
        return this.localAge() >= CYCLE - WARN;
    }

    /** 读某个坐标脚下的棋盘色：0=白，1=黑，-1=不是棋盘块。 */
    private int parityAt(ServerLevel server, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.2, z);
        if (!server.hasChunkAt(pos)) {
            return -1;
        }
        BlockState state = server.getBlockState(pos);
        if (state.is(ModBlocks.ARENA_BLACK.get())) {
            return 1;
        }
        if (state.is(ModBlocks.ARENA_WHITE.get())) {
            return 0;
        }
        return -1;
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }
        if (this.caster != null && !this.caster.isAlive()) {
            this.finish();
            return;
        }

        if (!this.opened) {
            this.opened = true;
            server.playSound(null, this.center.x, this.center.y, this.center.z,
                    SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.4f, 0.7f);
            PixelfxFx.play(server, this.center.add(0.0, 0.1, 0.0),
                    PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, 3.6f, 0.0f);
            SpellFx.shake(server, this.center, 40.0, 0.45f, 8);
        }

        // 翻转瞬间：钟声 + 震屏，让「棋盘翻了」有明确反馈
        int cycle = this.cycleIndex();
        if (cycle != this.lastCycle) {
            this.lastCycle = cycle;
            if (cycle > 0) {
                this.playFlipFx(server);
            }
        }

        if (this.warning() && this.age % WARN_INTERVAL == 0) {
            this.warnNextColor(server);
        }

        if (this.age % DAMAGE_INTERVAL == 0) {
            this.applyDamage(server);
        }
    }

    private void playFlipFx(ServerLevel server) {
        server.playSound(null, this.center.x, this.center.y, this.center.z,
                SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.2f, 0.55f);
        server.playSound(null, this.center.x, this.center.y, this.center.z,
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0f, 0.7f);
        SpellFx.shake(server, this.center, 32.0, 0.55f, 8);
        server.sendParticles(ModParticles.BURST_RING.get(),
                this.center.x, this.center.y + 0.3, this.center.z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * 预警：扫玩家附近的方块，在**即将变危险**的那一色上升起火星。
     *
     * <p>不画网格、不猜格子大小——直接问方块。提示和实际结算用的是同一套判定。
     */
    private void warnNextColor(ServerLevel server) {
        int next = (this.activeParity() + 1) % 2;
        AABB box = new AABB(this.center, this.center).inflate(FIELD_RADIUS);
        // 多名玩家站位重叠时去重，避免重复发送同一方块的预警粒子包
        Set<BlockPos> warned = new HashSet<>();
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());
            for (int dx = -WARN_SCAN; dx <= WARN_SCAN; dx++) {
                for (int dz = -WARN_SCAN; dz <= WARN_SCAN; dz++) {
                    // 隔一格采样，密度够看又不至于把包打爆
                    if (((dx + dz) & 1) != 0) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(px + dx, py - 1, pz + dz);
                    if (!warned.add(pos)) {
                        continue;
                    }
                    if (this.parityAt(server, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5) != next) {
                        continue;
                    }
                    server.sendParticles(ModParticles.BURST_DOT.get(),
                            pos.getX() + 0.5, pos.getY() + 1.15, pos.getZ() + 0.5,
                            1, 0.15, 0.05, 0.15, 0.02);
                    server.sendParticles(next == 0 ? LIGHT : DARK,
                            pos.getX() + 0.5, pos.getY() + 1.02, pos.getZ() + 0.5,
                            1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }
    }

    /** 结算：站在当前危险色方块上的玩家扣血。 */
    private void applyDamage(ServerLevel server) {
        int active = this.activeParity();
        double radiusSq = FIELD_RADIUS * FIELD_RADIUS;
        AABB box = new AABB(this.center, this.center).inflate(FIELD_RADIUS + 2.0);
        var damageSource = SpellFx.magicSource(server, this.caster);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!SpellFx.isValidPlayerTarget(player)) {
                continue;
            }
            if (player.position().distanceToSqr(this.center) > radiusSq) {
                continue;
            }
            if (this.parityAt(server, player.getX(), player.getY(), player.getZ()) != active) {
                continue;
            }
            Integer last = this.lastHit.get(player.getUUID());
            if (last != null && this.age - last < DAMAGE_INTERVAL - 1) {
                continue;
            }
            this.lastHit.put(player.getUUID(), this.age);
            player.hurt(damageSource, DAMAGE);

            server.sendParticles(active == 0 ? LIGHT : DARK,
                    player.getX(), player.getY() + 0.15, player.getZ(),
                    4, 0.3, 0.2, 0.3, 0.02);
        }
    }

    @Override
    public int durationTicks() {
        return DURATION + 1;
    }

    @Override
    public String id() {
        return "chessboard_field";
    }
}
