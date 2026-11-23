package com.example.galboss.ritual;

import com.example.galboss.fx.GoldenButterflyBurst;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.spell.SpellFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「黄金乡」维度（{@code galboss:golden_land}）。
 */
public final class GoldenLand {

    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("galboss", "golden_land"));

    public static final Vec3 SPAWN = new Vec3(0.5, 4.0, 0.5);
    private static final double SPREAD = 1.2;

    private GoldenLand() {
    }

    @Nullable
    public static ServerLevel level(@Nullable MinecraftServer server) {
        return server == null ? null : server.getLevel(DIMENSION);
    }

    public static void prepare(ServerLevel level) {
        level.getChunkAt(BlockPos.containing(SPAWN));
    }

    public static boolean sendPlayers(@Nullable MinecraftServer server, List<ServerPlayer> players) {
        ServerLevel target = level(server);
        if (target == null) {
            return false;
        }
        prepare(target);
        playArrivalCeremony(target, SPAWN);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            double angle = Math.PI * 2.0 * i / Math.max(1, players.size());
            double x = SPAWN.x + Math.cos(angle) * SPREAD;
            double z = SPAWN.z + Math.sin(angle) * SPREAD;
            player.teleportTo(target, x, SPAWN.y, z, player.getYRot(), player.getXRot());
            GoldenButterflyBurst.burstAtChest(target, new Vec3(x, SPAWN.y, z));
        }
        return true;
    }

    public static boolean send(ServerPlayer player) {
        ServerLevel target = level(player.getServer());
        if (target == null) {
            return false;
        }
        prepare(target);
        player.teleportTo(target, SPAWN.x, SPAWN.y, SPAWN.z, player.getYRot(), player.getXRot());
        playArrivalCeremony(target, SPAWN);
        GoldenButterflyBurst.burstAtChest(target, SPAWN);
        return true;
    }

    /** 黄金乡降临法阵与黄金蝶欢迎光环。 */
    public static void playArrivalCeremony(ServerLevel level, Vec3 at) {
        PixelfxFx.play(level, at.add(0.0, 0.08, 0.0), PixelfxSlugs.TOHKA_CONVOCATION_CIRCLE, 2.8f, 0.0f);
        PixelfxFx.play(level, at.add(0.0, 0.05, 0.0), PixelfxSlugs.TOHKA_BELL_WAVE, 2.0f, 0.0f);
        level.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                at.x, at.y + 1.2, at.z, 18, 1.8, 1.2, 1.8, 0.03);
        level.playSound(null, at.x, at.y, at.z,
                SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.5f, 1.2f);
        SpellFx.shake(level, at, 32.0, 0.45f, 8);
    }

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
