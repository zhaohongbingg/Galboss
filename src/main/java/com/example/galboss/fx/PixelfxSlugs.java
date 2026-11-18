package com.example.galboss.fx;

import java.util.List;
import java.util.Set;

/**
 * pixelfx 序列帧特效的 slug 常量表。
 *
 * <p><b>v0 起这个类不再是唯一真相</b>：实际可用清单由 {@link EffectLibrary} 扫描
 * {@code assets/galboss/pixelfx/} 与 {@code config/galboss/fx/} 得出，
 * 新增特效只要放 json，<b>不用改这里、不用重编译</b>。
 *
 * <p>下面的常量仍然保留，作用有两个：
 * <ol>
 *   <li>给调用点做编译期安全（{@code PixelfxSlugs.TOHKA_IRON_FIST} 拼错就编译不过）；</li>
 *   <li>作为 {@link #all()} / {@link #isKnown(String)} 的兜底 —— 库尚未加载时（启动极早期）仍认这批。</li>
 * </ol>
 *
 * <p>这个类是纯服务端可用的（不引用任何 {@code net.minecraft.client.*}），
 * 命令补全、服务端广播前的白名单校验都读它。
 */
public final class PixelfxSlugs {

    // ---------------------------------------------------------------- 桐香（猩红 / 纪律金 / 威压紫）
    /** 铁拳制裁命中：猩红重击。 */
    public static final String TOHKA_IRON_FIST = "tohka_iron_fist";
    /** 判决铃冲击波开始：金色雷击波。 */
    public static final String TOHKA_BELL_WAVE = "tohka_bell_wave";
    /** 禁足令锁定目标：紫色禁足印记（立牌）。 */
    public static final String TOHKA_CONFINEMENT_SIGIL = "tohka_confinement_sigil";
    /** 学生会召集：金色魔法阵。 */
    public static final String TOHKA_CONVOCATION_CIRCLE = "tohka_convocation_circle";
    /** 全校裁决命中：圣光裁决（立牌）。 */
    public static final String TOHKA_VERDICT_SMITE = "tohka_verdict_smite";
    /** 二阶段过场：循环日食（立牌，寿命 50 tick）。 */
    public static final String TOHKA_PHASE2_ECLIPSE = "tohka_phase2_eclipse";

    // ---------------------------------------------------------------- 礼（琥珀）
    /** 枪击命中：弹着点。 */
    public static final String REI_BULLET_IMPACT = "rei_bullet_impact";
    /** 高伤 / 连段终结：暴击命中。 */
    public static final String REI_CRIT_HIT = "rei_crit_hit";

    // ---------------------------------------------------------------- 郁子（天青）
    /** 刀锋相交（命中 / 被格挡）：格挡闪光。 */
    public static final String IKUKO_PARRY_FLASH = "ikuko_parry_flash";
    /** 狂战突击落地：震地波。 */
    public static final String IKUKO_QUAKE = "ikuko_quake";

    // ---------------------------------------------------------------- 碑文兵器（v1 烘焙新增）
    /** 奥丁的永恒之枪：枪体盘旋显形（billboard，循环）。 */
    public static final String EPITAPH_GUNGNIR = "epitaph_gungnir";
    /** 枪影坠落（billboard）。 */
    public static final String EPITAPH_SPEAR_FALL = "epitaph_spear_fall";
    /** 枪落点冲击（ground，可沿贯穿线复用成一路犁过去）。 */
    public static final String EPITAPH_SPEAR_IMPACT = "epitaph_spear_impact";
    /** 索尔的雷霆之锤（billboard）。 */
    public static final String EPITAPH_MJOLNIR = "epitaph_mjolnir";
    /** 雅典娜的神盾（billboard）。 */
    public static final String EPITAPH_AEGIS = "epitaph_aegis";
    /** 雷云期间的竖直雷柱（billboard）。 */
    public static final String EPITAPH_LIGHTNING_BOLT = "epitaph_lightning_bolt";
    /** 雷云天幕（billboard，循环）。 */
    public static final String EPITAPH_STORM_SKY = "epitaph_storm_sky";

    /** 契约 §3 的 10 个 slug，顺序固定。库未加载时的兜底清单（不是权威清单，权威见 {@link EffectLibrary}）。 */
    public static final List<String> ALL = List.of(
            TOHKA_IRON_FIST,
            TOHKA_BELL_WAVE,
            TOHKA_CONFINEMENT_SIGIL,
            TOHKA_CONVOCATION_CIRCLE,
            TOHKA_VERDICT_SMITE,
            TOHKA_PHASE2_ECLIPSE,
            REI_BULLET_IMPACT,
            REI_CRIT_HIT,
            IKUKO_PARRY_FLASH,
            IKUKO_QUAKE,
            // v1 烘焙新增的 7 个碑文兵器
            EPITAPH_GUNGNIR,
            EPITAPH_SPEAR_FALL,
            EPITAPH_SPEAR_IMPACT,
            EPITAPH_MJOLNIR,
            EPITAPH_AEGIS,
            EPITAPH_LIGHTNING_BOLT,
            EPITAPH_STORM_SKY);

    private static final Set<String> KNOWN = Set.copyOf(ALL);

    private PixelfxSlugs() {
    }

    /**
     * 当前<b>实际可用</b>的 slug 清单。
     *
     * <p>优先取 {@link EffectLibrary}（扫描 assets + config 覆盖得到）；库尚未加载时退回
     * {@link #ALL}，保证命令补全不至于空。
     */
    public static List<String> all() {
        List<String> live = EffectLibrary.slugs();
        return live.isEmpty() ? ALL : live;
    }

    /**
     * slug 是否可播。未知 slug 一律不播（避免拿半成品 JSON 上屏）。
     *
     * <p>先问 {@link EffectLibrary}；库未加载时退回契约常量，避免启动早期误判。
     */
    public static boolean isKnown(String slug) {
        if (slug == null || slug.isEmpty()) {
            return false;
        }
        return EffectLibrary.isKnown(slug) || KNOWN.contains(slug);
    }

    /** 逗号分隔的已知 slug，给命令报错与日志用。 */
    public static String asText() {
        return String.join(", ", all());
    }
}
