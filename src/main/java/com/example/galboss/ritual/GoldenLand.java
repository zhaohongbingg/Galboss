package com.example.galboss.ritual;

import com.example.galboss.GalBoss;
import com.example.galboss.block.ModBlocks;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.fx.GoldenButterflyBurst;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.spell.SpellFx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「黄金乡」维度（{@code galboss:golden_land}）。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GoldenLand {

    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("galboss", "golden_land"));

    public static final Vec3 SPAWN = new Vec3(0.5, 4.0, 0.5);
    /** 战后返回主世界的虚空之井中心坐标（666, 4, 666）。 */
    public static final BlockPos RETURN_WELL = new BlockPos(666, 4, 666);
    private static final double SPREAD = 1.2;
    private static final double VOID_TRIGGER_Y = -4.0;

    private GoldenLand() {
    }

    @Nullable
    public static ServerLevel level(@Nullable MinecraftServer server) {
        return server == null ? null : server.getLevel(DIMENSION);
    }

    public static void prepare(ServerLevel level) {
        level.getChunkAt(BlockPos.containing(SPAWN));
        ensureReturnWell(level);
        ensureGoldenTrees(level);
    }

    /**
     * 在黄金乡围绕出生点与归途之井生成黄金树林（幂等：已生成过则跳过）。
     */
    public static void ensureGoldenTrees(ServerLevel level) {
        BlockPos marker = new BlockPos(26, 4, 0);
        level.getChunkAt(marker);
        if (level.getBlockState(marker).is(ModBlocks.GOLDEN_LOG.get())) {
            return;
        }
        int[][] treeSpots = {
                {26, 0, 6}, {18, 19, 5}, {0, 26, 7}, {-19, 18, 6},
                {-26, 0, 5}, {-18, -19, 7}, {0, -26, 6}, {19, -18, 5},
                {658, 660, 6}, {674, 672, 6}
        };
        for (int[] spot : treeSpots) {
            placeGoldenTree(level, new BlockPos(spot[0], 4, spot[1]), spot[2]);
        }
    }

    private static void placeGoldenTree(ServerLevel level, BlockPos base, int height) {
        level.getChunkAt(base);
        BlockState log = ModBlocks.GOLDEN_LOG.get().defaultBlockState();
        BlockState leaf = ModBlocks.GOLDEN_LEAVES.get().defaultBlockState()
                .setValue(LeavesBlock.DISTANCE, 1)
                .setValue(LeavesBlock.PERSISTENT, false);

        for (int dy = 0; dy < height; dy++) {
            level.setBlock(base.above(dy), log, 3);
        }
        int top = height - 1;
        for (int dy = top - 2; dy <= top + 1; dy++) {
            int radius = (dy == top + 1) ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy <= top) {
                        continue;
                    }
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && dy >= top) {
                        continue;
                    }
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, leaf, 3);
                    }
                }
            }
        }
    }

    /**
     * 在 (666, ~, 666) 处凿出 3×3 贯穿基岩的虚空之井，用于战后跳回主世界。
     * 幂等操作：若中心 y=0 已是空气则跳过。
     */
    public static void ensureReturnWell(ServerLevel level) {
        level.getChunkAt(RETURN_WELL);
        BlockPos coreBottom = new BlockPos(RETURN_WELL.getX(), 0, RETURN_WELL.getZ());
        if (level.getBlockState(coreBottom).isAir()) {
            return;
        }
        int cx = RETURN_WELL.getX();
        int cz = RETURN_WELL.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 0; y <= 4; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                level.setBlock(new BlockPos(cx + dx, 4, cz + dz),
                        (corner ? Blocks.SHROOMLIGHT : Blocks.GILDED_BLACKSTONE).defaultBlockState(), 3);
            }
        }
    }

    /** 黄金乡内是否已无存活的贝阿朵莉切（即战后可跳井返回）。 */
    public static boolean isBattleCleared(ServerLevel level) {
        level.getChunkAt(BlockPos.containing(SPAWN));
        return level.getEntities(ModEntities.BEATRICE.get(), LivingEntity::isAlive).isEmpty();
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
            // 七桩登场：直接在这里判定，不依赖 PlayerChangedDimensionEvent 的时序
            com.example.galboss.event.PurgatoryStakes.ensureStands(target, player);
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
        // 七桩登场：直接在这里判定，不依赖 PlayerChangedDimensionEvent 的时序
        com.example.galboss.event.PurgatoryStakes.ensureStands(target, player);
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
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        GoldenButterflyBurst.burstAtChest(overworld, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        overworld.playSound(null, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0f, 1.2f);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !DIMENSION.equals(level.dimension())) {
            return;
        }
        double dx = player.getX() - (RETURN_WELL.getX() + 0.5);
        double dz = player.getZ() - (RETURN_WELL.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;
        if (distSq <= 48.0 * 48.0 && player.tickCount % 20 == 0) {
            ensureReturnWell(level);
            if (isBattleCleared(level)) {
                level.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                        RETURN_WELL.getX() + 0.5, 2.0, RETURN_WELL.getZ() + 0.5,
                        6, 0.7, 1.2, 0.7, 0.02);
                level.sendParticles(ParticleTypes.END_ROD,
                        RETURN_WELL.getX() + 0.5, 1.5, RETURN_WELL.getZ() + 0.5,
                        4, 0.5, 1.0, 0.5, 0.03);
            }
        }

        if (player.getY() >= VOID_TRIGGER_Y) {
            return;
        }
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;

        if (isBattleCleared(level)) {
            sendToOverworld(player);
        } else {
            player.teleportTo(level, SPAWN.x, SPAWN.y, SPAWN.z, player.getYRot(), player.getXRot());
            level.playSound(null, SPAWN.x, SPAWN.y, SPAWN.z,
                    SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.0f, 0.85f);
            player.displayClientMessage(
                    Component.literal("黄金乡的结界尚未解除，请先击败贝阿朵莉切！").withStyle(ChatFormatting.GOLD),
                    true);
        }
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && DIMENSION.equals(player.level().dimension())
                && event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            event.setCanceled(true);
        }
    }
}
