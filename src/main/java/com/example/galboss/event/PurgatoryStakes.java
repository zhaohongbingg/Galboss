package com.example.galboss.event;

import com.example.galboss.entity.AbstractPurgatoryStakeEntity;
import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.fx.GoldenButterflyBurst;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.ritual.GoldenLand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 炼狱七桩在黄金乡的登场与复活（游戏总线）。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li><b>进入黄金乡即登场</b>：玩家切到 {@code galboss:golden_land} 时，若该维度里还没有活着的七桩，
 *       就在玩家周围 4 格摆一圈把七个人一起召出来（面朝玩家）。已经有活着的就不重复召 ——
 *       多人依次进场不会刷成 14 个。</li>
 *   <li><b>死一个，贝阿朵把她拉回来</b>：七桩里任何一位在黄金乡死亡，
 *       只要有<b>活着的贝阿朵莉切</b>在同一维度，{@value #REVIVE_DELAY_TICKS} tick 后就在原地复活。</li>
 *   <li>贝阿朵死了就不再复活 —— 这是这条机制唯一的解法，也是给玩家的反制点。</li>
 * </ol>
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li>复活队列按 {@code level.getGameTime()} 记到期时间，在 {@code ServerTickEvent.END} 里统一处理；
 *       不用 {@code level.scheduleTick}（那是方块刻，不是实体刻）。</li>
 *   <li>复活用 {@code entity.getType().create(level)} 重建，所以七位各自复活成自己，
 *       不需要额外的「类型 → 工厂」映射表。</li>
 *   <li>队列里的维度/实体类型都做过校验：存档卸载、类型被移除这类情况会直接丢弃而不是抛异常。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PurgatoryStakes {

    /** 七桩围绕玩家出现的半径（格）。 */
    private static final double ARRIVAL_RING_RADIUS = 4.0;

    /** 死亡到复活之间的间隔（tick）：2 秒，够玩家看清"她倒下了"再被拉起来。 */
    private static final int REVIVE_DELAY_TICKS = 40;

    /** 全场扫描用的包围盒（维度级别查询，Y 给足）。 */
    private static final AABB WORLD_BOX = new AABB(-3.0E7D, -1024.0D, -3.0E7D, 3.0E7D, 1024.0D, 3.0E7D);

    /** 待复活队列。 */
    private static final List<PendingRevival> PENDING = new ArrayList<>();

    private PurgatoryStakes() {
    }

    private record PendingRevival(ServerLevel level, EntityType<?> type, Vec3 pos, long dueTick) {
    }

    // ---------------------------------------------------------------- 进入黄金乡 → 七人登场

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean toGolden = GoldenLand.DIMENSION.equals(event.getTo());
        boolean fromGolden = GoldenLand.DIMENSION.equals(event.getFrom());
        if (toGolden) {
            // 关键：把登场判定**推迟到下一个服务器 tick**。
            // 事件触发时玩家还没真正落到目标维度（player.level() 可能仍是旧维度、
            // 位置也可能还是传送前的），当场判定会整个 early-return
            // —— 实测过两次：从主世界 /galboss golden 进场时七桩一个都不登场（日志里 0 条战斗记录）。
            // 推迟一 tick 后 player.level() 与坐标都已就位，等同"登录时"那条路径（那条一直是好的）。
            var server = player.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (player.level() instanceof ServerLevel ready && GoldenLand.DIMENSION.equals(ready.dimension())) {
                        ensureStands(ready, player);
                    }
                });
            }
        } else if (fromGolden) {
            // 离开就清掉升级点：重生点只在黄金乡里生效，不污染主世界的正常复活（用户要求）
            player.setRespawnPosition(event.getTo(), null, 0.0F, false, true);
        }
    }

    /**
     * 登录时也要检查一遍：玩家的重生点已经被钉在黄金乡（{@link #onChangedDimension} 做的），
     * 死亡复活 / 重新登录时人本来就在这个维度里 —— 不会再触发「维度变化」，七桩就永远不登场了（实测踩过）。
     */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.level() instanceof ServerLevel level
                && GoldenLand.DIMENSION.equals(level.dimension())) {
            ensureStands(level, player);
        }
    }

    /** 确保黄金乡里有活着的七桩（并顺手把重生点钉好）。 */
    private static void ensureStands(ServerLevel level, ServerPlayer player) {
        // 把重生点钉在降临点：在黄金乡里被打死就地复活，省得每次重新进场（调试与实战都省事）。
        // forced 必须为 true —— 原版 respawn 只在「床/重生锚」才会采纳非强制点，
        // 否则 findRespawnPositionAndUseSpawnBlock 直接返回空、退回主世界出生点（实测踩过）。
        player.setRespawnPosition(level.dimension(), BlockPos.containing(GoldenLand.SPAWN),
                player.getYRot(), true, true);
        // 七位是她的棋子：她不在（或已倒下）就不登场（用户要求）。
        // 注意这会连带一个效果：没召贝阿朵就进黄金乡，里面是空的。
        boolean beatriceNear = hasLivingBeatriceNear(level, GoldenLand.SPAWN);
        com.example.galboss.stake.StakeDebug.log("gate dim={} beatriceNear={}", level.dimension().location(), beatriceNear);
        if (!beatriceNear) {
            return;
        }
        // 已经有活着的七桩就不重复登场（多人依次进场时避免刷成 14 个）。
        // 分身（countsAsStake=false）不算，否则阿斯莫德留两个影子就会把登场判定挡死。
        int existingCount = 0;
        for (AbstractPurgatoryStakeEntity existing : level.getEntitiesOfClass(AbstractPurgatoryStakeEntity.class, WORLD_BOX)) {
            if (existing.countsAsStake()) {
                existingCount++;
            }
        }
        com.example.galboss.stake.StakeDebug.log("gate existingStakes={}", existingCount);
        if (existingCount > 0) {
            return;
        }
        summonAll(level);
        com.example.galboss.stake.StakeDebug.log("gate summonAll done at {}", level.dimension().location());
    }

    /**
     * 七人围绕黄金乡的**固定降临点**摆一圈，面朝圆心。
     *
     * <p>为什么不以「玩家当时的位置」为圆心：{@code PlayerChangedDimensionEvent} 触发时
     * 玩家的坐标还没落到 {@code GoldenLand.SPAWN}（实测会拿到传送前的旧坐标，七个人直接出现在
     * 十几个方块外、玩家到场时一个都看不见）。所有进入黄金乡的路径最终都会把人放到 {@code SPAWN}，
     * 所以用固定点最稳。
     */
    private static void summonAll(ServerLevel level) {
        EntityType<?>[] types = {
                ModEntities.ASMODEUS.get(), ModEntities.BEELZEBUB.get(), ModEntities.BELPHEGOR.get(),
                ModEntities.LEVIATHAN.get(), ModEntities.LUCIFER.get(), ModEntities.MAMMON.get(),
                ModEntities.SATAN.get()
        };
        Vec3 center = GoldenLand.SPAWN;
        int spawned = 0;
        for (int i = 0; i < types.length; i++) {
            double angle = Math.PI * 2.0D * i / types.length;
            double x = center.x + Math.cos(angle) * ARRIVAL_RING_RADIUS;
            double z = center.z + Math.sin(angle) * ARRIVAL_RING_RADIUS;
            Vec3 spot = safeSpot(level, x, center.y, z, center);
                var stake = spawn(level, types[i], spot, yawFacing(spot, center));
            if (stake instanceof AbstractPurgatoryStakeEntity stakeEntity) {
                scaleForPartySize(stakeEntity, level);
                spawned++;
            }
        }
        if (spawned == 0) {
            return;
        }
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.6F, 0.8F);
        ModNetwork.sendRedTruthLiteral(level, center, 48.0D,
                "「欢迎来到黄金乡。我的七枚棋子，请陪客人玩玩。」", 70);
    }

    /**
     * 按在场人数缩放血量：×1 / ×1.5 / ×2 / ×2.5（与项目里 FS 三巨头同一口径）。
     * 七位各 140 血，四人以上时单体 350 血，避免"人一多就成割草"。
     */
    private static void scaleForPartySize(AbstractPurgatoryStakeEntity stake, ServerLevel level) {
        int players = Math.max(1, level.players().size());
        double scale = players >= 4 ? 2.5D : players == 3 ? 2.0D : players == 2 ? 1.5D : 1.0D;
        var attribute = stake.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attribute != null && scale > 1.0D) {
            attribute.setBaseValue(attribute.getBaseValue() * scale);
            stake.setHealth(stake.getMaxHealth());
        }
    }

    /** 从 from 看向 to 的 yaw（MC 约定：0 = +Z，90 = -X）。 */
    private static float yawFacing(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---------------------------------------------------------------- 死亡 → 贝阿朵复活

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractPurgatoryStakeEntity stake)) {
            return;
        }
        if (!(stake.level() instanceof ServerLevel level)) {
            return;
        }
        if (!GoldenLand.DIMENSION.equals(level.dimension())) {
            return;
        }
        // 分身不参与复活（否则一次清场就是几十次入队）
        if (!stake.countsAsStake()) {
            return;
        }
        // 贝阿朵莉切不在（或已倒下）就不复活 —— 这是玩家唯一的解法。
        // 只查附近：曾经写成整维度扫描，几百个实体同时死亡时单 tick 跑满 60 秒被看门狗强杀。
        if (!hasLivingBeatriceNear(level, stake.position())) {
            return;
        }
        Vec3 at = stake.position();
        PENDING.add(new PendingRevival(level, stake.getType(), at, level.getGameTime() + REVIVE_DELAY_TICKS));
        // 倒下瞬间：金色蝴蝶四散，提示"这不算结束"
        GoldenButterflyBurst.burstAtChest(level, at);
        level.playSound(null, at.x, at.y, at.z,
                SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.HOSTILE, 1.2F, 1.4F);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        Iterator<PendingRevival> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingRevival pending = it.next();
            if (pending.level().getGameTime() < pending.dueTick()) {
                continue;
            }
            it.remove();
            // 存档卸载 / 维度被移除：直接丢弃
            if (pending.level().getServer() == null || pending.level().getServer().getLevel(pending.level().dimension()) == null) {
                continue;
            }
            Entity revived = spawn(pending.level(), pending.type(), pending.pos(), 0.0F);
            if (revived == null) {
                continue;
            }
            GoldenButterflyBurst.burstAtChest(pending.level(), pending.pos());
            pending.level().playSound(null, pending.pos().x, pending.pos().y, pending.pos().z,
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.2F, 1.15F);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 建实体、定位、落地、加入世界。失败返回 null。 */
    private static Entity spawn(ServerLevel level, EntityType<?> type, Vec3 pos, float yaw) {
        Entity created = type.create(level);
        if (!(created instanceof AbstractPurgatoryStakeEntity stake)) {
            return null;
        }
        stake.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        stake.setPersistenceRequired();
        level.addFreshEntity(stake);
        return stake;
    }

    /** 落点被方块占住就退回中心点，避免把七桩塞进墙里。 */
    private static Vec3 safeSpot(ServerLevel level, double x, double y, double z, Vec3 fallback) {
        BlockPos pos = BlockPos.containing(x, y + 0.1D, z);
        boolean feetFree = level.isEmptyBlock(pos);
        boolean headFree = level.isEmptyBlock(pos.above());
        return feetFree && headFree ? new Vec3(x, y, z) : fallback;
    }

    /** 死亡点附近（96 格）有没有活着的贝阿朵莉切。 */
    private static boolean hasLivingBeatriceNear(ServerLevel level, Vec3 at) {
        return !level.getEntitiesOfClass(BeatriceEntity.class,
                net.minecraft.world.phys.AABB.ofSize(at, 96.0D, 96.0D, 96.0D),
                net.minecraft.world.entity.LivingEntity::isAlive).isEmpty();
    }

    /** 供调试/命令读取：当前排队的复活数。 */
    public static int pendingRevivals() {
        return PENDING.size();
    }

    /** 当前维度里还活着的七桩数量。 */
    public static int livingCount(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        return serverLevel.getEntitiesOfClass(AbstractPurgatoryStakeEntity.class, WORLD_BOX).size();
    }
}
