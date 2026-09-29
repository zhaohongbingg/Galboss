package com.example.galboss.config;

import net.minecraftforge.common.ForgeConfigSpec;

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

    public static final ForgeConfigSpec SPEC;

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

        SPEC = builder.build();
    }

    private GalBossConfig() {
    }
}
