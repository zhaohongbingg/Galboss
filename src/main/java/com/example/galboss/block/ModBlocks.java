package com.example.galboss.block;

import com.example.galboss.GalBoss;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 棋盘竞技场的「不可破坏」方块。
 *
 * <p>结构 {@code galboss:chess_arena} 原本用原版 stone / polished_deepslate / 三色混凝土搭成，
 * 玩家可以在战斗里拆地板、挖墙。这里用 5 个外观完全复刻原版贴图、但<b>硬度 -1</b> 的方块
 * 替换它们：跟基岩同级，徒手 / 工具 / 爆炸 / 活塞都拆不动（{@code /setblock} 仍可清）。
 *
 * <p><b>创造模式仍然可以挖掉</b>，这是有意的：{@code strength(-1)} 只让
 * {@code BlockBehaviour#getDestroyProgress} 返回 {@code 0.0F}，而创造模式走的是
 * {@code MultiPlayerGameMode} 的 {@code instabuild} 分支，直接调 {@code destroyBlock}，
 * 根本不问破坏进度。建图 / 调试 / 改结构都需要能拆，所以不加任何拦截。
 *
 * <p>为什么不直接给原版混凝土加保护：原版没有「按位置改硬度」的机制，结构复制到哪里都得
 * 靠区域事件拦截；把方块本身做成不可破坏，结构放在哪个世界、哪个坐标都自带保护，
 * 不需要额外配置。
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, GalBoss.MOD_ID);

    /** 结构底层与外墙用的石头。 */
    public static final RegistryObject<Block> ARENA_STONE = BLOCKS.register("arena_stone", ModBlocks::arenaBlock);

    /** 结构里原本的抛光深板岩（立柱 / 台阶）。 */
    public static final RegistryObject<Block> ARENA_DEEPSLATE =
            BLOCKS.register("arena_deepslate", ModBlocks::arenaBlock);

    /** 棋盘外圈包边的灰色。 */
    public static final RegistryObject<Block> ARENA_GRAY = BLOCKS.register("arena_gray", ModBlocks::arenaBlock);

    /** 棋盘白格。 */
    public static final RegistryObject<Block> ARENA_WHITE = BLOCKS.register("arena_white", ModBlocks::arenaBlock);

    /** 棋盘黑格。 */
    public static final RegistryObject<Block> ARENA_BLACK = BLOCKS.register("arena_black", ModBlocks::arenaBlock);

    /** 黄金乡原木（可分解为不死图腾）。 */
    public static final RegistryObject<Block> GOLDEN_LOG = BLOCKS.register("golden_log",
            () -> new RotatedPillarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)));

    /** 黄金乡树叶（破坏有概率掉落金苹果与附魔金苹果）。 */
    public static final RegistryObject<Block> GOLDEN_LEAVES = BLOCKS.register("golden_leaves",
            () -> new LeavesBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.GOLD)
                    .strength(0.2F)
                    .randomTicks()
                    .sound(SoundType.GRASS)
                    .noOcclusion()
                    .isValidSpawn((state, level, pos, type) -> false)
                    .isSuffocating(ModBlocks::never)
                    .isViewBlocking(ModBlocks::never)));

    private static boolean never(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    private ModBlocks() {
    }

    /**
     * 硬度 -1 = 不可破坏；爆炸抗性拉满，活塞也推不动。
     *
     * <p>{@code -1.0F} 会让 {@code BlockBehaviour#getDestroyProgress} 直接返回 {@code 0.0F}，
     * 生存/冒险的破坏进度永远推不满。创造模式绕过这条路，可以挖 —— 见类注释。
     *
     * <p>不写 {@code noLootTable()}：拆不掉就没有掉落判定，写了反而多一个数据文件。
     */
    private static Block arenaBlock() {
        return new Block(BlockBehaviour.Properties.of()
                .strength(-1.0F, 3600000.0F)
                .pushReaction(PushReaction.BLOCK)
                .sound(SoundType.STONE));
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
