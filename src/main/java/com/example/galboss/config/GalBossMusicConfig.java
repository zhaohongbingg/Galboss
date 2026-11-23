package com.example.galboss.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端音乐配置（{@code config/galboss-client.toml}）。
 *
 * <p>BGM 是纯本地表现：服务端既不发包也不校验，所以旋钮放在 <b>CLIENT</b> 配置里，
 * 每个玩家自己调，联机时不会互相影响。整合包直接改这个文件即可，不用重新编译。
 */
public final class GalBossMusicConfig {

    /** 总开关：关掉之后两条曲目都不会自动播放。 */
    public static final ForgeConfigSpec.BooleanValue ENABLED;

    /** 播放音量倍率（再乘原版「音乐」滑条）。 */
    public static final ForgeConfigSpec.DoubleValue VOLUME;

    /**
     * 黄金乡里打架时，战斗曲是否压过黄金乡主题。
     *
     * <p>默认 {@code true}：进黄金乡先听到主题曲，一旦开战就切成战斗曲（打完整场）。
     * 想让黄金乡主题一直播到底，把这个改成 {@code false}。
     */
    public static final ForgeConfigSpec.BooleanValue BATTLE_OVERRIDES_GOLDEN_LAND;

    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("music");

        ENABLED = builder
                .comment("Play GalBoss background music on this client.",
                        "Purely client-side: it is not synced, every player decides for themselves.")
                .define("enabled", true);

        VOLUME = builder
                .comment("Music volume multiplier applied on top of the vanilla Music slider.")
                .defineInRange("volume", 0.8, 0.0, 1.0);

        BATTLE_OVERRIDES_GOLDEN_LAND = builder
                .comment("Whether boss battle music takes over while fighting inside the Golden Land.",
                        "true (default): Golden Land theme plays on arrival, battle music takes over once a boss fight starts.",
                        "false: the Golden Land theme keeps playing for the whole stay.")
                .define("battleOverridesGoldenLand", true);

        builder.pop();

        SPEC = builder.build();
    }

    private GalBossMusicConfig() {
    }
}
