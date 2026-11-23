package com.example.galboss.item;

import com.example.galboss.block.ModBlocks;
import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.entity.ModEntities;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "galboss");

    // ---------------------------------------------------------------- 生成蛋
    //
    // 配色直接从各自皮肤的像素统计里取，主色 = 头发（角色最醒目的特征），
    // 副色 = 服装上的高对比色。括号里是该颜色在对应区域的占比。

    /** 冷泉院 桐香：青绿发色 + 粉色格纹裙。皮肤里没有紫色，旧的紫色配色是错的。 */
    public static final RegistryObject<Item> REIZEIN_TOHKA_SPAWN_EGG = ITEMS.register(
            "reizein_tohka_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.REIZEIN_TOHKA::get,
                    0x449783,  // 头发（头部区域 49%）
                    0xEFD6CE,  // 下身格纹（下身区域 30%）
                    new Item.Properties()
            )
    );

    /** オナブタ 郁子：亮蓝发色 + 白色水手服。 */
    public static final RegistryObject<Item> ONABUTA_IKUKO_SPAWN_EGG = ITEMS.register(
            "onabuta_ikuko_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.ONABUTA_IKUKO::get,
                    0x6189BB,  // 头发（头部区域 39%）
                    0xE6E9F0,  // 水手服上衣（躯干区域 14%）
                    new Item.Properties()
            )
    );

    /** 只須川 レイ：深棕发色 + 白色水手服（与郁子同款制服）。 */
    public static final RegistryObject<Item> TADASUGAWA_REI_SPAWN_EGG = ITEMS.register(
            "tadasugawa_rei_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.TADASUGAWA_REI::get,
                    0x483830,  // 头发（头部区域 45%）
                    0xE6E9F0,  // 水手服上衣（躯干区域 17%）
                    new Item.Properties()
            )
    );

    /**
     * 贝阿朵莉切：金色长发 + 酒红色礼服。
     *
     * <p>配色取自她的立绘主色：主色是她最醒目的特征（金发），副色取服装上对比最强的酒红。
     * 她不属于 FS Big Three 的冷色系，刷怪蛋放在一起时颜色上就能一眼认出来。
     */
    public static final RegistryObject<Item> BEATRICE_SPAWN_EGG = ITEMS.register(
            "beatrice_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.BEATRICE::get,
                    0xD9B45E,  // 头发（金色）
                    0x5A1C2E,  // 礼服（酒红）
                    new Item.Properties()
            )
    );

    /**
     * 黑山羊仆从：郁子「深渊召唤」召出的法术仆从，本身不自然生成，这颗蛋只给创造 / 调试用。
     *
     * <p>配色取契约里的主色：深靛长袍（#1b1830）+ 发光金瞳 / 符文（#ffd257）。
     */
    public static final RegistryObject<Item> BLACK_GOAT_SERVANT_SPAWN_EGG = ITEMS.register(
            "black_goat_servant_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.BLACK_GOAT_SERVANT::get,
                    0x1b1830,  // 长袍 / 骨面主色
                    0xffd257,  // 金瞳 / 角根符文
                    new Item.Properties()
            )
    );

    // --------------------------------------- 炼狱七桩（贝阿朵莉切的七名仆从）的刷怪蛋
    //
    // 配色规则沿用上面的做法：主色 = 头发（七人最醒目的区别，衣服是同一套酒红制服），
    // 副色 = 制服酒红。发色不是眼估的，是 tools/blockbench/import_purgatory_stakes.mjs
    // 转换后按「与其它六人不同的像素」的主色统计出来的（七人贴图只差头发区域）。

    /** 阿斯莫德：金色高马尾（#c09050）。 */
    public static final RegistryObject<Item> ASMODEUS_SPAWN_EGG = ITEMS.register(
            "asmodeus_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.ASMODEUS::get, 0xC09050, 0x7A2B3C, new Item.Properties()));

    /** 别西卜：深金波浪长发（#b08030）。 */
    public static final RegistryObject<Item> BEELZEBUB_SPAWN_EGG = ITEMS.register(
            "beelzebub_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.BEELZEBUB::get, 0xB08030, 0x7A2B3C, new Item.Properties()));

    /** 贝尔芬格：深蓝黑双马尾（#303040）。 */
    public static final RegistryObject<Item> BELPHEGOR_SPAWN_EGG = ITEMS.register(
            "belphegor_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.BELPHEGOR::get, 0x303040, 0x7A2B3C, new Item.Properties()));

    /** 利维坦：薄荷绿双马尾（#80a080）。 */
    public static final RegistryObject<Item> LEVIATHAN_SPAWN_EGG = ITEMS.register(
            "leviathan_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.LEVIATHAN::get, 0x80A080, 0x7A2B3C, new Item.Properties()));

    /** 路西法：黑色长直发（#202030，暂定发型）。 */
    public static final RegistryObject<Item> LUCIFER_SPAWN_EGG = ITEMS.register(
            "lucifer_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.LUCIFER::get, 0x202030, 0x7A2B3C, new Item.Properties()));

    /** 玛门：铜棕马尾（#705030）。 */
    public static final RegistryObject<Item> MAMMON_SPAWN_EGG = ITEMS.register(
            "mammon_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.MAMMON::get, 0x705030, 0x7A2B3C, new Item.Properties()));

    /** 撒旦：银白卷钻发（#c8c8d0）。 */
    public static final RegistryObject<Item> SATAN_SPAWN_EGG = ITEMS.register(
            "satan_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.SATAN::get, 0xC8C8D0, 0x7A2B3C, new Item.Properties()));

    // ------------------------------------------------- 手持武器（用于渲染层）
    //
    // 这两个既是模型载体也是实际物品：怪物手持武器走原版物品系统
    // （见 IkukoKatanaLayer / ReiGunLayer），物品模型在
    // models/item/ikuko_katana.json、rei_gun.json，
    // 由 tools/blockbench/geo_to_item_model.mjs 从 geo/ 生成 —— 别手改那两个 json。

    public static final RegistryObject<Item> IKUKO_KATANA = ITEMS.register(
            "ikuko_katana",
            () -> new Item(new Item.Properties())
    );

    public static final RegistryObject<Item> REI_GUN = ITEMS.register(
            "rei_gun",
            () -> new Item(new Item.Properties())
    );

    /**
     * 贝阿朵莉切的烟斗。
     *
     * <p>和武士刀 / 手枪一样，它既是怪物手上的模型载体，也是能放进背包的实际物品：
     * 渲染层把同一个物品画在角色模型的 RightHandLocator 骨骼上（见 BeatricePipeLayer），
     * 物品模型 models/item/beatrice_pipe.json 由 tools/blockbench 从
     * geo/beatrice_pipe.geo.json 生成 —— 别手改那个 json。
     */
    public static final RegistryObject<Item> BEATRICE_PIPE = ITEMS.register(
            "beatrice_pipe",
            () -> new Item(new Item.Properties())
    );

    /**
     * 炼狱之柱：七位炼狱之桩手里那根柱子，同时也是能拿在手里的实际物品。
     *
     * <p>和烟斗 / 武士刀 / 手枪同为「手持物」，但实现不同：柱子有 819/968 个自由角度旋转的方块，
     * 原版物品模型只接受 0 / ±22.5 / ±45，走 elements 会被削平甚至整份不加载 ——
     * 所以它是 GeckoLib 的 GeoItem（见 {@code PurgatoryPillarItem}），物品模型是
     * {@code models/item/purgatory_pillar.json}（{@code builtin/entity}）+ {@code geo/item/purgatory_pillar.geo.json}。
     */
    public static final RegistryObject<Item> PURGATORY_PILLAR = ITEMS.register(
            "purgatory_pillar",
            () -> new PurgatoryPillarItem(new Item.Properties().stacksTo(1))
    );

    // -------------------------------------- 棋盘竞技场的不可破坏方块（BlockItem）
    //
    // 这 5 个方块本身在 block/ModBlocks 里注册（硬度 -1），这里只补物品形态，
    // 方便创造模式取用 / 手搭。结构 galboss:chess_arena 的调色板已经指向它们。

    public static final RegistryObject<Item> ARENA_STONE = ITEMS.register(
            "arena_stone", () -> new BlockItem(ModBlocks.ARENA_STONE.get(), new Item.Properties()));

    public static final RegistryObject<Item> ARENA_DEEPSLATE = ITEMS.register(
            "arena_deepslate", () -> new BlockItem(ModBlocks.ARENA_DEEPSLATE.get(), new Item.Properties()));

    public static final RegistryObject<Item> ARENA_GRAY = ITEMS.register(
            "arena_gray", () -> new BlockItem(ModBlocks.ARENA_GRAY.get(), new Item.Properties()));

    public static final RegistryObject<Item> ARENA_WHITE = ITEMS.register(
            "arena_white", () -> new BlockItem(ModBlocks.ARENA_WHITE.get(), new Item.Properties()));

    public static final RegistryObject<Item> ARENA_BLACK = ITEMS.register(
            "arena_black", () -> new BlockItem(ModBlocks.ARENA_BLACK.get(), new Item.Properties()));

    // ------------------------------------------- 终局奖励素材 + 宠物召唤物
    //
    // 三枚徽章既是合成 FS Big Three Core 的素材，也是「宠物版本」的召唤物：
    // 右键地面即可召唤对应的宠物并自动认主（见 PetSummonItem）。合成表不受影响。

    /** 冷泉院 桐香掉落：学生会徽章 → 召唤桐香宠物 */
    public static final RegistryObject<Item> TOHKA_BADGE = ITEMS.register(
            "tohka_badge",
            () -> new PetSummonItem(FsPetEntity.PetVariant.TOHKA, new Item.Properties()));

    /** 只須川 レイ掉落：风纪臂章 → 召唤礼宠物 */
    public static final RegistryObject<Item> REI_ARMBAND = ITEMS.register(
            "rei_armband",
            () -> new PetSummonItem(FsPetEntity.PetVariant.REI, new Item.Properties()));

    /** オナブタ 郁子掉落：战斗勋章 → 召唤郁子宠物 */
    public static final RegistryObject<Item> IKUKO_MEDAL = ITEMS.register(
            "ikuko_medal",
            () -> new PetSummonItem(FsPetEntity.PetVariant.IKUKO, new Item.Properties()));

    /** 三枚勋章合成的核心素材 */
    public static final RegistryObject<Item> FS_BIG_THREE_CORE = ITEMS.register(
            "fs_big_three_core", () -> new Item(new Item.Properties()));

    /** 终局饰品：低血量触发增益 */
    public static final RegistryObject<Item> FS_BADGE = ITEMS.register(
            "fs_badge", () -> new Item(new Item.Properties().stacksTo(1).fireResistant()));

    /**
     * 变体 → 对应的召唤物（徽章）。
     *
     * <p>宠物系统各处（JEI 展示、界面图标）都靠这个映射，别再散落一份 switch。
     */
    public static Item summonItemOf(FsPetEntity.PetVariant variant) {
        return switch (variant) {
            case TOHKA -> TOHKA_BADGE.get();
            case REI -> REI_ARMBAND.get();
            case IKUKO -> IKUKO_MEDAL.get();
        };
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
