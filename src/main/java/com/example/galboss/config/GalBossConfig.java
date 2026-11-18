package com.example.galboss.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 通用配置（服务端 + 客户端一致即可，落在 {@code config/galboss-common.toml}）。
 *
 * <p>这一组全部是「整合包适配」旋钮：原版数值下三个 boss 是按 800/1000/1200 血设计的，
 * 整合包里玩家的武器动辄打出几十倍伤害，直接就会被秒。默认值就是为此准备的减伤档：
 * <ul>
 *   <li>{@code nonPlayerDamageMultiplier = 0.10} —— 非玩家来源只吃一成</li>
 *   <li>{@code playerMagicDamageMultiplier = 0.50} —— 玩家的魔法 / 法术减半</li>
 *   <li>{@code damageCapPerHit = 100} —— 单次伤害上限，{@code <=0} 关闭</li>
 * </ul>
 *
 * <p>整合包作者按自家伤害经济改这三个数就行；想彻底回到「原版硬碰硬」，
 * 把两个倍率改成 1.0、上限改成 0。
 */
public final class GalBossConfig {

    // ------------------------------------------------------------------ 减伤

    /** 非玩家来源（环境、野怪、其它模组的非玩家单位）的伤害倍率。 */
    public static final ForgeConfigSpec.DoubleValue NON_PLAYER_DAMAGE_MULTIPLIER;

    /** 玩家魔法 / 法术伤害的倍率（判定含 {@code witch_resistant_to} 标签，可被数据包扩充）。 */
    public static final ForgeConfigSpec.DoubleValue PLAYER_MAGIC_DAMAGE_MULTIPLIER;

    /** 玩家名下的宠物 / 召唤物（OwnableEntity）是否按「玩家伤害」处理。 */
    public static final ForgeConfigSpec.BooleanValue PETS_COUNT_AS_PLAYER;

    // ------------------------------------------------------------------ 限伤

    /**
     * 单次伤害上限。
     *
     * <p>乘完所有倍率之后封顶 —— 整合包里一刀几万的爆发因此最多打掉这么多。
     * {@code <= 0} 表示不封顶。{@code /kill} 与虚空伤害不受此限制（否则 boss 清不掉）。
     */
    public static final ForgeConfigSpec.IntValue DAMAGE_CAP_PER_HIT;

    // -------------------------------------------------------------- 宠物养成
    //
    // 礼物表与货架都做成字符串列表，改数值不用重新编译：
    //   礼物   "物品ID=好感度"                      例 "minecraft:golden_apple=8"
    //   偏爱   "搭档=物品ID"                        例 "tohka=minecraft:cake"
    //   商品   "商品ID 数量 货币ID 价格 解锁好感度"   例 "minecraft:book 3 minecraft:emerald 2 0"
    // 搭档可写 tohka / rei / ikuko，也可以写完整实体名 reizein_tohka 等。
    // 解析失败的行会被跳过并写一条 warn 日志，不会让整个配置加载失败。

    /** 礼物表：物品 → 基础好感度。不在表里的物品一律不算礼物。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PET_GIFT_VALUES;

    /** 每个搭档最喜欢的东西，加成 = 基础值 / 2 + 2。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PET_GIFT_FAVORITES;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PET_TRADES_TOHKA;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PET_TRADES_REI;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PET_TRADES_IKUKO;

    public static final ForgeConfigSpec SPEC;

    // ------------------------------------------------------------- 默认值表
    //
    // 必须声明在下面的 static 块之前：Java 按书写顺序执行静态初始化。

    private static final List<String> DEFAULT_GIFT_VALUES = List.of(
            "minecraft:sweet_berries=2",
            "minecraft:glow_berries=2",
            "minecraft:cookie=3",
            "minecraft:gold_ingot=3",
            "minecraft:amethyst_shard=3",
            "minecraft:honey_bottle=4",
            "minecraft:emerald=5",
            "minecraft:diamond=6",
            "minecraft:cake=7",
            "minecraft:golden_apple=8",
            "minecraft:enchanted_golden_apple=15"
    );

    private static final List<String> DEFAULT_GIFT_FAVORITES = List.of(
            "tohka=minecraft:cake",
            "rei=minecraft:honey_bottle",
            "ikuko=minecraft:golden_apple"
    );

    private static final List<String> DEFAULT_TRADES_TOHKA = List.of(
            "minecraft:book 3 minecraft:emerald 2 0",
            "minecraft:paper 16 minecraft:emerald 3 30",
            "minecraft:golden_apple 1 minecraft:emerald 9 60",
            "galboss:fs_big_three_core 1 minecraft:emerald 32 90"
    );

    private static final List<String> DEFAULT_TRADES_REI = List.of(
            "minecraft:arrow 16 minecraft:emerald 2 0",
            "minecraft:iron_ingot 6 minecraft:emerald 5 30",
            "minecraft:diamond 1 minecraft:emerald 8 60",
            "galboss:fs_badge 1 minecraft:emerald 40 90"
    );

    private static final List<String> DEFAULT_TRADES_IKUKO = List.of(
            "minecraft:cooked_beef 8 minecraft:emerald 3 0",
            "minecraft:iron_sword 1 minecraft:emerald 4 30",
            "minecraft:diamond_sword 1 minecraft:emerald 16 60",
            "galboss:ikuko_katana 1 minecraft:emerald 32 90"
    );

    /** 宽松校验：只挡明显不是字符串的项，真正的解析留给 PetGiftTable / PetTradeTable 做。 */
    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("boss_defense");
        NON_PLAYER_DAMAGE_MULTIPLIER = builder
                .comment("Damage multiplier for non-player sources (environment, mobs, other mods' non-player units).",
                        "Example: 0.10 = only 10% of that damage gets through.")
                .defineInRange("nonPlayerDamageMultiplier", 0.10, 0.0, 64.0);

        PLAYER_MAGIC_DAMAGE_MULTIPLIER = builder
                .comment("Damage multiplier for player-sourced magic/spell damage.",
                        "Magic detection: vanilla magic, indirect_magic, and the `witch_resistant_to` tag",
                        "(modpacks can add their own spell damage types to that tag via datapack).")
                .defineInRange("playerMagicDamageMultiplier", 0.50, 0.0, 64.0);

        PETS_COUNT_AS_PLAYER = builder
                .comment("Whether tamed pets / player-owned minions count as player damage.",
                        "Combat maids, wolves, turrets etc. are excluded from the non-player reduction when true.")
                .define("petsCountAsPlayer", true);
        builder.pop();

        builder.push("damage_cap");
        DAMAGE_CAP_PER_HIT = builder
                .comment("Hard cap applied to a single hit of damage (after all multipliers).",
                        "Set to 0 or below to disable. /kill and void damage are never capped.")
                .defineInRange("perHit", 100, 0, 1_000_000);
        builder.pop();

        builder.push("pet_gift");

        PET_GIFT_VALUES = builder
                .comment("Gift table entries: \"<item>=<affection>\". Items not listed here are not gifts.",
                        "Example: \"minecraft:golden_apple=8\"")
                .defineListAllowEmpty(List.of("giftValues"), DEFAULT_GIFT_VALUES,
                        GalBossConfig::isNonBlankString);

        PET_GIFT_FAVORITES = builder
                .comment("Favourite gift of each partner: \"<partner>=<item>\".",
                        "A favourite gains base / 2 + 2 extra affection.",
                        "Partners: tohka / rei / ikuko (or the full entity id, e.g. reizein_tohka).")
                .defineListAllowEmpty(List.of("giftFavorites"), DEFAULT_GIFT_FAVORITES,
                        GalBossConfig::isNonBlankString);

        builder.pop();

        builder.push("pet_trade");

        PET_TRADES_TOHKA = builder
                .comment("Tohka's shop entries: \"<result> <count> <currency> <price> <unlockAffection>\".",
                        "Example: \"minecraft:book 3 minecraft:emerald 2 0\"")
                .defineListAllowEmpty(List.of("tohkaTrades"), DEFAULT_TRADES_TOHKA,
                        GalBossConfig::isNonBlankString);

        PET_TRADES_REI = builder
                .comment("Rei's shop entries, same format as tohkaTrades.")
                .defineListAllowEmpty(List.of("reiTrades"), DEFAULT_TRADES_REI,
                        GalBossConfig::isNonBlankString);

        PET_TRADES_IKUKO = builder
                .comment("Ikuko's shop entries, same format as tohkaTrades.")
                .defineListAllowEmpty(List.of("ikukoTrades"), DEFAULT_TRADES_IKUKO,
                        GalBossConfig::isNonBlankString);

        builder.pop();

        SPEC = builder.build();
    }

    private GalBossConfig() {
    }
}
