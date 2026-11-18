package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
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
 * 金雨：在施法点所在的<b>整个区块</b>内持续落下金色蝶雨。
 *
 * <h2>和雷云（{@link StormSpell}）的区别</h2>
 * 雷云是「隔 30 tick 劈一道，靠单发伤害」，节奏慢、威慑大；金雨是「一直在下」，
 * 单发很低（2 点）但密度高，作用是<b>逼你一直走</b>——站着不动就会被连续砸中。
 * 两者一个是重锤一个是砂纸，战斗里可以叠着用。
 *
 * <h2>公平性</h2>
 * 每一滴雨都有 6 tick 的落地预警（地面小圈 + 落点微光），不是凭空扣血。
 * 玩家看得到「这块要挨打」，只是雨点太多，没法逐滴躲 —— 这正是要的效果。
 */
public class GoldenRainSpell extends Spell {

    private static final int CHUNK = 16;

    /** 整场持续时间。 */
    private static final int DURATION = 140;
    /** 每隔多少 tick 安排一滴雨。140 / 8 = 17 滴。 */
    private static final int DROP_INTERVAL = 8;
    /** 落地预警时长（tick）。 */
    private static final int DROP_TELEGRAPH = 6;

    /** 单滴伤害。 */
    private static final float DAMAGE = 2.0f;
    /** 单滴命中半径。 */
    private static final double DROP_RADIUS = 1.9;
    /** 同一玩家两次被砸的最小间隔（tick）：防止贴脸连击。 */
    private static final int PER_PLAYER_GAP = 8;

    private final int minX;
    private final int minZ;
    private final double baseY;

    /** 已安排、还没落地的雨滴。 */
    private final List<Drop> pending = new ArrayList<>();
    /** 玩家 UUID → 上次被砸的 age。 */
    private final Map<UUID, Integer> lastHit = new HashMap<>();

    private boolean opened;

    /** 一滴待落的雨。 */
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

        // 开场：区块边框亮一下，宣告「这一块要下雨」
        if (!this.opened) {
            this.opened = true;
            SpellFx.square(server, center, CHUNK / 2.0, SpellFx.GOLD);
            server.playSound(null, center.x, center.y, center.z,
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                    net.minecraft.sounds.SoundSource.HOSTILE, 1.2f, 1.3f);
        }

        // 安排新雨滴
        if (this.age % DROP_INTERVAL == 0 && this.age <= DURATION - DROP_TELEGRAPH) {
            this.pending.add(new Drop(this.randomPoint(server), this.age + DROP_TELEGRAPH));
        }

        // 预警中的雨滴：地面上画小圈
        for (Drop drop : this.pending) {
            if (drop.landAge() - this.age > 0 && (this.age % 2 == 0)) {
                SpellFx.circle(server, drop.at(), DROP_RADIUS, SpellFx.GOLD);
            }
        }

        // 落地的雨滴
        this.pending.removeIf(drop -> {
            if (drop.landAge() > this.age) {
                return false;
            }
            this.land(server, drop);
            return true;
        });
    }

    /** 区块内随机一点（留 1 格边，避免正好压在区块缝上）。 */
    private Vec3 randomPoint(ServerLevel server) {
        double x = this.minX + 1.0 + server.random.nextDouble() * (CHUNK - 2.0);
        double z = this.minZ + 1.0 + server.random.nextDouble() * (CHUNK - 2.0);
        return new Vec3(x, this.baseY, z);
    }

    private void land(ServerLevel server, Drop drop) {
        Vec3 at = drop.at();
        // 视觉：复用暴击闪光（19 帧，短促），不用雷柱——金雨是「点」不是「柱」
        PixelfxFx.play(server, at.add(0.0, 0.15, 0.0), PixelfxSlugs.REI_CRIT_HIT, 0.7f, 0.0f);
        server.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.5, at.z, 6, 0.35, 0.4, 0.35, 0.03);
        server.playSound(null, at.x, at.y, at.z,
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.HOSTILE, 0.35f, 1.8f);

        AABB box = new AABB(at, at).inflate(DROP_RADIUS + 1.0);
        for (Player player : server.getEntitiesOfClass(Player.class, box)) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            if (player.position().distanceTo(at) > DROP_RADIUS) {
                continue;
            }
            Integer last = this.lastHit.get(player.getUUID());
            if (last != null && this.age - last < PER_PLAYER_GAP) {
                continue;
            }
            this.lastHit.put(player.getUUID(), this.age);
            player.hurt(this.caster == null
                    ? server.damageSources().magic()
                    : server.damageSources().indirectMagic(this.caster, this.caster), DAMAGE);
        }
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
