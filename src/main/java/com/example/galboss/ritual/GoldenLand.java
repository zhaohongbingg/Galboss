package com.example.galboss.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「黄金乡」维度（{@code galboss:golden_land}）。
 *
 * <p>第九夜魔女复苏时，整场遭遇被搬进这个维度：数据包定义的<b>超平坦全金块世界</b>
 * （第 0 层基岩，上面 3 层金块，站人高度 y=4），战败后的玩家也留在这里探索。
 *
 * <p>维度本身完全由数据包生成（{@code data/galboss/dimension/golden_land.json} 等三份文件），
 * 这里只负责三件事：拿维度、预生成出生区块、把人送过去。维度加载失败（比如数据包被别的包覆盖）
 * 时返回 false，由调用方决定降级方案 —— 绝不假装传送成功。
 */
public final class GoldenLand {

    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("galboss", "golden_land"));

    /** 站人点：超平坦地表 y=4（0 基岩 + 1~3 金块）。 */
    public static final Vec3 SPAWN = new Vec3(0.5, 4.0, 0.5);

    /** 多人落点半径，避免全叠在一格里。 */
    private static final double SPREAD = 1.2;

    private GoldenLand() {
    }

    /** 黄金乡维度；数据包没加载出来时是 null。 */
    @Nullable
    public static ServerLevel level(@Nullable MinecraftServer server) {
        return server == null ? null : server.getLevel(DIMENSION);
    }

    /** 预生成出生点区块：第一次传送前必须先把脚下那块地生成出来。 */
    public static void prepare(ServerLevel level) {
        level.getChunkAt(BlockPos.containing(SPAWN));
    }

    /**
     * 把一组玩家送进黄金乡。
     *
     * @return 维度不存在时返回 false，调用方必须报告这个缺口而不是静默继续
     */
    public static boolean sendPlayers(@Nullable MinecraftServer server, List<ServerPlayer> players) {
        ServerLevel target = level(server);
        if (target == null) {
            return false;
        }
        prepare(target);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            double angle = Math.PI * 2.0 * i / Math.max(1, players.size());
            double x = SPAWN.x + Math.cos(angle) * SPREAD;
            double z = SPAWN.z + Math.sin(angle) * SPREAD;
            player.teleportTo(target, x, SPAWN.y, z, player.getYRot(), player.getXRot());
        }
        return true;
    }

    /** {@code /galboss golden}：单人进黄金乡。 */
    public static boolean send(ServerPlayer player) {
        ServerLevel target = level(player.getServer());
        if (target == null) {
            return false;
        }
        prepare(target);
        player.teleportTo(target, SPAWN.x, SPAWN.y, SPAWN.z, player.getYRot(), player.getXRot());
        return true;
    }

    /** {@code /galboss return}：送回主世界出生点。 */
    public static void sendToOverworld(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }
}
