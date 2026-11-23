package com.example.galboss.command;

import com.example.galboss.entity.AbstractFSBossEntity;
import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.fx.GoldenButterflyBurst;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.spell.AegisSpell;
import com.example.galboss.spell.ChainLightningSpell;
import com.example.galboss.spell.ChessboardFieldSpell;
import com.example.galboss.spell.ChessboardJudgementSpell;
import com.example.galboss.spell.GoldenRainSpell;
import com.example.galboss.spell.GungnirSpell;
import com.example.galboss.spell.LightningStrikeSpell;
import com.example.galboss.spell.MjolnirSpell;
import com.example.galboss.spell.SpellEngine;
import com.example.galboss.spell.SoulChainSpell;
import com.example.galboss.spell.StormSpell;
import com.example.galboss.spell.WitchDomainSpell;
import com.example.galboss.ritual.BodySeal;
import com.example.galboss.ritual.GoldenLand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /galboss} 调试命令。
 *
 * <p>子命令按「需要什么前提」分三组，{@code /galboss help} 打印的就是这份清单：
 * <pre>
 *   不需要 boss       : help / redtruth / butterfly particle|burst / spell / fx / golden / return
 *   需要贝阿朵莉切 64 格 : epitaph / seal / sniper / butterfly volley
 *   需要三人组 boss 64 格 : battle
 * </pre>
 *
 * <h2>为什么蝴蝶全收在 {@code butterfly} 下</h2>
 * 它们是同一只蝴蝶生命周期的三段，由三套独立代码实现，任何一段坏了另外两段都正常：
 * <ol>
 *   <li>{@code butterfly particle} —— 尾迹用的粒子素材本身（{@code ModParticles.GOLDEN_BUTTERFLY}）；</li>
 *   <li>{@code butterfly volley} —— 真正的飞行体（{@code ButterflyMissileEntity}，追踪/撞墙/扣血）；</li>
 *   <li>{@code butterfly burst} —— 命中结算后的爆发（{@code GoldenButterflyBurst}）；</li>
 *   <li>{@code butterfly path} —— 飞行螺旋的参数诊断（{@code butterfly_path.json}）。</li>
 * </ol>
 * 以前它们是三个只差一个字母的平级名字（{@code butterfly} / {@code butterflies} /
 * {@code butterflyburst}），{@code path} 还被塞在 {@code fx} 下（它其实跟 pixelfx 序列帧无关）。
 * 现在旧名全部保留为别名，不破坏已有习惯。
 *
 * <p>需要 OP 权限等级 2（和原版 {@code /setblock} 一档）。未知名 slug 直接失败并列出已知项，
 * <b>不静默</b>：调试命令看不懂「什么都没发生」。
 *
 * <p>挂在 {@link RegisterCommandsEvent} 上（见 {@code GalBoss} 构造函数里的
 * {@code MinecraftForge.EVENT_BUS.addListener}），这样不依赖 {@code @EventBusSubscriber}
 * 的 Dist 默认值，服务端/客户端都会注册。
 */
public final class GalBossCommand {

    /** 命令执行者前方多少格生成。 */
    private static final double SPAWN_DISTANCE = 3.0;

    /** scale 参数是「defaultScale 的倍数」，1 = 元数据默认大小。 */
    private static final float DEFAULT_SCALE = 1.0f;

    /**
     * 一条法术登记项。
     *
     * <p><b>为什么要这张表</b>：以前法术 id 散在三处 —— 补全列表、{@code switch} 分支、
     * {@code default} 里的错误消息。加一招要改三个地方，漏一个就会出现「能补全但放不出来」
     * 或者「报错消息里没有新 id」。现在 suggestion / {@code spell list} / {@code help} /
     * 分发全部读这一张表。
     *
     * @param id     命令里敲的短名
     * @param label  中文说明（{@code spell list} 与 help 里显示）
     * @param caster 施放逻辑；{@code ahead} 是执行者视线前方 8 格（贯穿类法术要用）
     */
    private record SpellEntry(String id, String label, SpellCaster caster) {
    }

    /** 法术施放器。 */
    @FunctionalInterface
    private interface SpellCaster {
        void cast(CommandSourceStack source, ServerPlayer player, Vec3 ahead);
    }

    /** 全部可放的法术。顺序就是 {@code /galboss spell list} 的打印顺序。 */
    private static final List<SpellEntry> SPELLS = List.of(
            new SpellEntry("strike", "单发落雷",
                    (s, p, ahead) -> LightningStrikeSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("chain", "链雷（最多跳 3 次，伤害递减）",
                    (s, p, ahead) -> SpellEngine.cast(new ChainLightningSpell(s.getLevel(), ahead, null, p))),
            new SpellEntry("soulchain", "魂链（把周围怪物串成共伤链，10 秒）",
                    (s, p, ahead) -> SoulChainSpell.cast(s.getLevel(), p.position(), p)),
            new SpellEntry("storm", "雷云（半径 24，持续随机落雷）",
                    (s, p, ahead) -> StormSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("gungnir", "神枪贯穿（24 格直线判定带）",
                    (s, p, ahead) -> GungnirSpell.cast(s.getLevel(), ahead, null, p)),
            new SpellEntry("mjolnir", "雷锤砸落（内外双环）",
                    (s, p, ahead) -> MjolnirSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("aegis", "神盾（远程伤害 ×0.25）",
                    (s, p, ahead) -> AegisSpell.cast(s.getLevel(), p, null)),
            new SpellEntry("chess", "棋盘审判（整区块 8×8 黑白交替）",
                    (s, p, ahead) -> ChessboardJudgementSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("rain", "金雨（整区块持续落点）",
                    (s, p, ahead) -> GoldenRainSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("domain", "魔女领域（收缩审判圈，站圈外挨打）",
                    (s, p, ahead) -> WitchDomainSpell.cast(s.getLevel(), p.position(), null)),
            new SpellEntry("field", "棋盘场地（32×32 黑白交替，踩危险格挨打）",
                    (s, p, ahead) -> ChessboardFieldSpell.cast(s.getLevel(), p.position(), null)));

    /** {@code /galboss help} 打印的清单。加子命令时记得同步这一行。 */
    private static final List<String> SUBCOMMANDS = List.of(
            "help — 就是你现在看的这个",
            "battle — 把 64 格内最近的 boss 直接拉进战斗",
            "epitaph [kills] — 开「黄金碑文」；带 kills 时把献祭数设到该值（0~17）",
            "seal <part|clear> — 给执行者挂 / 清一个部位封印",
            "sniper — 让最近的贝阿朵莉切对执行者放一次狙击",
            "butterfly particle [count] — 撒金色镂空蝴蝶粒子（纯视觉，不动、不扣血）",
            "butterfly volley [count] — 让最近的贝阿朵莉切放一组黄金蝶（真发射物）",
            "butterfly burst — 播放一次「金色蝴蝶攻击」命中爆发",
            "butterfly path — 打印蝴蝶螺旋参数（半径 / 螺距 / 每圈采样）",
            "redtruth <text> — 在屏幕上打一句红字",
            "spell <id|list> — 以自己为目标放一发法术（不涉及任何 boss）",
            "golden — 把执行者送进黄金乡维度",
            "return — 送回主世界出生点",
            "fx list|reload|<slug> [scale] [yaw] — 序列帧特效调试");

    private GalBossCommand() {
    }

    /** 逗号分隔的法术 id，给补全 / 报错用。 */
    private static List<String> spellIds() {
        return SPELLS.stream().map(SpellEntry::id).toList();
    }

    /** {@code RegisterCommandsEvent} 回调。 */
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("galboss")
                // 只有 OP(2) 能用：这是调试入口，别让普通玩家拿来刷屏
                .requires(source -> source.hasPermission(2))
                // /galboss help：不记命令也能查到全部子命令
                .then(Commands.literal("help")
                        .executes(GalBossCommand::help))
                // /galboss battle：把最近的 boss 直接拉进战斗。
                // 结构里放出来的 boss 要玩家右键对话才开战，自动化验证（probe）没法右键，
                // 所以留这个调试入口，用来在真实战斗里观察技能特效。
                .then(Commands.literal("battle")
                        .executes(GalBossCommand::forceBattle))
                // /galboss epitaph [kills]：直接开「黄金碑文」遭遇；带 kills 时把献祭数设到该值
                .then(Commands.literal("epitaph")
                        .executes(context -> epitaph(context, -1))
                        .then(Commands.argument("kills", IntegerArgumentType.integer(0, 17))
                                .executes(context -> epitaph(context,
                                        IntegerArgumentType.getInteger(context, "kills")))))
                // /galboss seal <part|clear>：调试用，手动给玩家挂 / 清掉一个部位封印
                .then(Commands.literal("seal")
                        .then(Commands.argument("part", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("head", "chest", "belly", "knee", "foot", "clear"), builder))
                                .executes(GalBossCommand::sealPart)))
                // ---- 蝴蝶：同一只蝴蝶生命周期的三段 + 螺旋调参，全收在一个父节点下
                //      particle（尾迹粒子素材）/ volley（飞行体）/ burst（命中爆发）/ path（螺旋参数）
                .then(Commands.literal("butterfly")
                        // 不带子命令 = 撒粒子，保持 /galboss butterfly 的旧行为
                        .executes(context -> spawnButterflies(context, 24))
                        .then(Commands.literal("particle")
                                .executes(context -> spawnButterflies(context, 24))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                        .executes(context -> spawnButterflies(context,
                                                IntegerArgumentType.getInteger(context, "count")))))
                        .then(Commands.literal("volley")
                                .executes(context -> butterflies(context, 3))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 12))
                                        .executes(context -> butterflies(context,
                                                IntegerArgumentType.getInteger(context, "count")))))
                        .then(Commands.literal("burst")
                                .executes(GalBossCommand::butterflyBurst))
                        .then(Commands.literal("path")
                                .executes(GalBossCommand::showButterflyPath))
                        // 旧写法兼容：/galboss butterfly 24
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(context -> spawnButterflies(context,
                                        IntegerArgumentType.getInteger(context, "count")))))
                // 旧名别名：/galboss butterflies [count]
                .then(Commands.literal("butterflies")
                        .executes(context -> butterflies(context, 3))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 12))
                                .executes(context -> butterflies(context,
                                        IntegerArgumentType.getInteger(context, "count")))))
                // 旧名别名：/galboss butterflyburst
                .then(Commands.literal("butterflyburst")
                        .executes(GalBossCommand::butterflyBurst))
                // /galboss sniper：让最近的贝阿朵莉切对执行者放一次狙击（锁定 2s → 光箭）
                .then(Commands.literal("sniper").executes(GalBossCommand::sniper))
                // /galboss redtruth <text>：在屏幕上打一句「红字」（纯表现，实机验收用）
                .then(Commands.literal("redtruth")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(GalBossCommand::redTruth)))
                // /galboss spell <id>：以自己为目标放一发法术（特效 + 伤害，不涉及任何 boss）
                .then(Commands.literal("spell")
                        // /galboss spell list：列出全部可用法术（不用记 id）
                        .then(Commands.literal("list")
                                .executes(GalBossCommand::listSpells))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        spellIds(), builder))
                                .executes(GalBossCommand::castSpell)))
                // /galboss golden：把执行者送进黄金乡维度；/galboss return：回主世界出生点
                .then(Commands.literal("golden")
                        .executes(GalBossCommand::toGoldenLand))
                .then(Commands.literal("return")
                        .executes(GalBossCommand::toOverworld))
                .then(Commands.literal("fx")
                        .then(Commands.literal("list")
                                .executes(GalBossCommand::listSlugs))
                        // /galboss fx reload：重扫 assets/galboss/pixelfx + config/galboss/fx，
                        // 编辑器改完 json 不用重启游戏
                        .then(Commands.literal("reload")
                                .executes(GalBossCommand::reloadFx))
                        // 旧名别名：/galboss fx path。它跟 pixelfx 序列帧无关，
                        // 正式位置是 /galboss butterfly path（见上面 butterfly 父节点）
                        .then(Commands.literal("path")
                                .executes(GalBossCommand::showButterflyPath))
                        .then(Commands.argument("slug", StringArgumentType.word())
                                .suggests(GalBossCommand::suggestSlugs)
                                .executes(context -> spawn(context, DEFAULT_SCALE, 0.0f))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.01f, 50.0f))
                                        .executes(context -> spawn(context,
                                                FloatArgumentType.getFloat(context, "scale"), 0.0f))
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-360.0f, 360.0f))
                                                .executes(context -> spawn(context,
                                                        FloatArgumentType.getFloat(context, "scale"),
                                                        FloatArgumentType.getFloat(context, "yaw"))))))));
    }

    /**
     * {@code /galboss epitaph [kills]}：让最近的贝阿朵莉切开碑文 / 把献祭数设到 kills。
     *
     * <p>碑文是数量判定的，所以调试参数也变成「已经献上几个」而不是「第几夜」：
     * {@code /galboss epitaph 11} = 前 10 位都已献上、第 11 位刚倒下（剜头已生效）。
     */
    private static int epitaph(CommandContext<CommandSourceStack> context, int kills) {
        CommandSourceStack source = context.getSource();
        Vec3 origin = source.getPosition();
        List<BeatriceEntity> bosses = source.getLevel().getEntitiesOfClass(
                BeatriceEntity.class, new AABB(origin, origin).inflate(64.0));
        BeatriceEntity nearest = bosses.stream()
                .min(Comparator.comparingDouble(boss -> boss.position().distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal("64 格内没有贝阿朵莉切（galboss:beatrice）"));
            return 0;
        }
        if (kills < 0) {
            nearest.debugStartRitual();
            source.sendSuccess(() -> Component.literal("黄金碑文开始：" + nearest.getName().getString()), false);
        } else {
            nearest.debugJumpToKills(kills);
            source.sendSuccess(() -> Component.literal("黄金碑文献祭数设为 " + kills
                    + "（当前第 " + nearest.getRitualNight() + " 夜）"), false);
        }
        return 1;
    }

    /**
     * {@code /galboss butterfly volley [count]}：让最近的贝阿朵莉切放一组黄金蝶。
     *
     * <p>黄金蝶是真正的发射物（{@code ButterflyMissileEntity}），所以这个入口既能验「飞得对不对」，
     * 也能验「撞墙会不会消失」「命中掉不掉血」—— 比等她自己按冷却放要快得多。
     */
    /** `/galboss sniper`：最近的贝阿朵莉切锁定执行者 2 秒后放光箭（调试/验收用）。 */
    private static int sniper(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        net.minecraft.world.entity.player.Player player = source.getPlayerOrException();
        var boss = source.getLevel().getEntitiesOfClass(
                com.example.galboss.entity.BeatriceEntity.class,
                player.getBoundingBox().inflate(64.0),
                com.example.galboss.entity.BeatriceEntity::isAlive).stream()
                .min(java.util.Comparator.comparingDouble(bossEntity -> bossEntity.distanceTo(player)))
                .orElse(null);
        if (boss == null) {
            source.sendFailure(Component.translatable("command.galboss.no_beatrice"));
            return 0;
        }
        if (!boss.debugSnipe(player)) {
            source.sendFailure(Component.literal("狙击没能开始（她正在施放 / 目标无效）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("狙击锁定：2 秒后放光箭"), true);
        return 1;
    }

    private static int butterflies(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        Vec3 origin = source.getPosition();
        List<BeatriceEntity> bosses = source.getLevel().getEntitiesOfClass(
                BeatriceEntity.class, new AABB(origin, origin).inflate(64.0));
        BeatriceEntity nearest = bosses.stream()
                .min(Comparator.comparingDouble(boss -> boss.position().distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal("64 格内没有贝阿朵莉切（galboss:beatrice）"));
            return 0;
        }
        nearest.debugLaunchButterflies(count);
        source.sendSuccess(() -> Component.literal("放出 " + count + " 只黄金蝶"), false);
        return 1;
    }

    /**
     * {@code /galboss redtruth <text>}：给 64 格内的玩家打一句红字。
     *
     * <p>红字是纯客户端表现（{@code S2CRedTruthPacket} → {@code RedTruthOverlay}），
     * 服务端只发文本，所以这个命令适合在实机里检查描边、缩放、淡出这些手感细节。
     */
    private static int redTruth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String text = StringArgumentType.getString(context, "text");
        ModNetwork.sendRedTruthLiteral(source.getLevel(), source.getPosition(), 64.0, text, 60);
        source.sendSuccess(() -> Component.literal("红字：" + text), false);
        return 1;
    }

    /**
     * {@code /galboss seal <part|clear>}：调试用，手动挂 / 清一个部位封印。
     *
     * <p>碑文本身会在第 4~8 夜自动挂封印；这个入口只是让实机验证能在不开战的情况下
     * 直接看到 HUD 与失明 / 禁足的效果，不改变正式流程。
     */
    private static int sealPart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BeatriceEntity boss = BodySeal.encounterBoss();
        if (boss == null || !boss.isRitualActive()) {
            source.sendFailure(Component.literal("当前没有进行中的黄金碑文遭遇"));
            return 0;
        }
        String part = StringArgumentType.getString(context, "part").toLowerCase(Locale.ROOT);
        if ("clear".equals(part)) {
            BodySeal.clear();
            source.sendSuccess(() -> Component.literal("已清除全部部位封印"), false);
            return 1;
        }
        BodySeal.Seal seal;
        try {
            seal = BodySeal.Seal.valueOf(part.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("未知部位：" + part + "（head/chest/belly/knee/foot/clear）"));
            return 0;
        }
        BodySeal.seal(seal);
        for (ServerPlayer player : source.getLevel().players()) {
            if (boss.distanceTo(player) <= 64.0) {
                player.displayClientMessage(
                        Component.translatable("epitaph.galboss.seal.apply", seal.displayName()), true);
            }
        }
        source.sendSuccess(() -> Component.literal("已挂上部位封印：" + seal.name()), false);
        return 1;
    }

    /**
     * {@code /galboss spell <id>}：以自己为目标放一发法术。
     *
     * <p>这套法术**不属于任何 boss**（见 {@code spell/SpellEngine}），所以调试入口也不需要先召唤她：
     * 直接站在空地上放，就能验「预警是否看得懂 / 落点对不对 / 伤害数字 / 屏震幅度」。
     *
     * <p>{@code caster} 传 null，这样法术会照常打到自己（省掉「施法者免疫」那一层），
     * 实机验证时能立刻看到掉血。
     *
     * <p>分发读 {@link #SPELLS}，不再写 {@code switch}：加一招只改那张表一处。
     */
    private static int castSpell(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String id = StringArgumentType.getString(context, "id").toLowerCase(Locale.ROOT);
        SpellEntry entry = SPELLS.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (entry == null) {
            source.sendFailure(Component.literal(
                    "未知法术：" + id + "；可用：" + String.join(" / ", spellIds())));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        Vec3 ahead = new Vec3(player.getX() + look.x * 8.0, player.getY(), player.getZ() + look.z * 8.0);
        entry.caster().cast(source, player, ahead);
        source.sendSuccess(() -> Component.literal(
                "施放 " + entry.label() + "（当前在施法 " + SpellEngine.activeCount() + "）"), false);
        return 1;
    }

    /** {@code /galboss spell list}：列出全部法术，不用记 id。 */
    private static int listSpells(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("可用法术（" + SPELLS.size() + "）："), false);
        for (SpellEntry entry : SPELLS) {
            source.sendSuccess(() -> Component.literal("  " + entry.id() + " — " + entry.label()), false);
        }
        return SPELLS.size();
    }

    /** {@code /galboss help}：列出全部子命令。 */
    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("§6/galboss§r —— GalBoss 调试命令（需要 OP 2）"), false);
        for (String line : SUBCOMMANDS) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        source.sendSuccess(() -> Component.literal(
                "  spell 可用：" + String.join(" / ", spellIds())), false);
        source.sendSuccess(() -> Component.literal(
                "  （旧名 butterflies / butterflyburst / fx path 仍可用）"), false);
        return SUBCOMMANDS.size();
    }

    /** {@code /galboss golden}：把执行者送进黄金乡维度。 */
    private static int toGoldenLand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!GoldenLand.send(player)) {
            context.getSource().sendFailure(Component.literal(
                    "黄金乡维度（galboss:golden_land）没有加载：检查数据包是否被覆盖"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已传送到黄金乡"), false);
        return 1;
    }

    /** {@code /galboss return}：从黄金乡回到主世界出生点。 */
    private static int toOverworld(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        GoldenLand.sendToOverworld(player);
        context.getSource().sendSuccess(() -> Component.literal("已返回主世界出生点"), false);
        return 1;
    }

    /**
     * {@code /galboss butterfly particle [count]}：在命令位置撒一把金色镂空蝴蝶。
     *
     * <p>走服务端 {@code sendParticles}，附近玩家都会看到；用来在不打贝阿朵莉切的情况下
     * 单独验收粒子的外观 / 数量 / 性能。
     */
    private static int spawnButterflies(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        Vec3 pos = source.getPosition();
        source.getLevel().sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                pos.x, pos.y + 1.2, pos.z, count, 2.2, 1.2, 2.2, 0.01);
        source.sendSuccess(() -> Component.literal("撒了 " + count + " 只金色镂空蝴蝶"), false);
        return count;
    }

    /** {@code /galboss butterfly burst}：在命令位置播放一次「金色蝴蝶攻击」命中爆发。 */
    private static int butterflyBurst(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Vec3 pos = source.getPosition();
        GoldenButterflyBurst.burst(source.getLevel(), pos.add(0.0, 1.0, 0.0));
        source.sendSuccess(() -> Component.literal("播放金色蝴蝶攻击特效"), false);
        return 1;
    }

    /**
     * {@code /galboss fx list}：打印当前实际可用的 slug。
     *
     * <p>清单来自 {@code EffectLibrary}（扫描 assets + config 覆盖），不再是硬编码常量——
     * 所以这里能看到 17 个（含 7 个原先白名单漏掉的 {@code epitaph_*}）。
     */
    private static int listSlugs(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> all = PixelfxSlugs.all();
        source.sendSuccess(() -> Component.literal(
                "pixelfx 可用特效 (" + all.size() + "): " + PixelfxSlugs.asText()), false);
        return all.size();
    }

    /**
     * {@code /galboss butterfly path}：打印黄金蝶螺旋的当前参数与派生量。
     *
     * <p>调参时先看这个：{@code 每圈采样 < 6} 就说明落点太少，眼睛会看成折线而不是螺旋 ——
     * 要么把 {@code pitch} 调大（转得慢一点），要么把 {@code spawnIntervalTicks} 调小。
     */
    private static int showButterflyPath(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var path = com.example.galboss.fx.ButterflyPathParams.load();
        double speed = path.speed();
        double samples = path.samplesPerTurn(speed);
        source.sendSuccess(() -> Component.literal(String.format(
                "蝴蝶螺旋 R=%.2f 格 · 螺距 %.2f 格/圈 · 竖直压扁 %.2f · 旋向 %+.0f · 落点每 %d tick"
                        + " · 速度 %.2f 格/tick  →  每圈 %.1f tick（%.2f 圈/秒），每圈采样 %.1f 点%s",
                path.radius(), path.pitch(), path.verticalSquash(), path.spin(),
                path.spawnIntervalTicks(), speed, path.ticksPerTurn(speed),
                20.0 / path.ticksPerTurn(speed), samples,
                samples < 6.0 ? "  ⚠ 采样偏少，会看成折线" : "")), false);
        return 1;
    }

    /**
     * {@code /galboss fx reload}：重扫特效定义库。
     *
     * <p>开发期调参的主循环：改 {@code config/galboss/fx/<slug>.json} → 敲这一条 → 立刻看效果。
     * 走服务端资源管理器扫描（jar 内的 {@code assets/galboss/pixelfx/} 也在其中），
     * 单机下客户端下一帧就会因为 generation 变化重新解析元数据，不需要重进世界。
     */
    private static int reloadFx(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        // getServer() 是可空的：客户端命令源（或服务端已关）时会拿到 null，
        // 以前直接 .getResourceManager() 会 NPE，这里显式挡一层
        var server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("没有服务端实例，无法重载特效库"));
            return 0;
        }
        net.minecraft.server.packs.resources.ResourceManager manager = server.getResourceManager();
        var scanned = com.example.galboss.fx.EffectLibrary.scan(manager);
        com.example.galboss.fx.EffectLibrary.clearWarnings();
        com.example.galboss.fx.EffectLibrary.publish(scanned);
        source.sendSuccess(() -> Component.literal(
                "特效库已重载：" + scanned.size() + " 个定义 —— "
                        + com.example.galboss.fx.EffectLibrary.asText()), false);
        return scanned.size();
    }

    /** slug 补全。 */
    private static CompletableFuture<Suggestions> suggestSlugs(CommandContext<CommandSourceStack> context,
                                                               SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(PixelfxSlugs.all(), builder);
    }

    /**
     * 在命令执行者前方铺一个特效。
     *
     * <p>位置取执行者脚部高度：ground 锚点的特效就会正好贴在他站的那层地面上，
     * billboard 锚点则从同一高度立起来。朝向取执行者的水平视线方向。
     */
    private static int spawn(CommandContext<CommandSourceStack> context, float scale, float yaw) {
        CommandSourceStack source = context.getSource();
        String slug = StringArgumentType.getString(context, "slug");
        if (!PixelfxSlugs.isKnown(slug)) {
            source.sendFailure(Component.literal(
                    "未知 pixelfx slug: \"" + slug + "\"；已知: " + PixelfxSlugs.asText()));
            return 0;
        }

        Vec3 origin = source.getPosition();
        Vec3 direction = horizontalLook(source);
        Vec3 target = origin.add(direction.scale(SPAWN_DISTANCE));
        // 用执行者脚部高度而不是眼睛高度：贴地特效才不会浮在半空
        Vec3 at = new Vec3(target.x, origin.y, target.z);

        PixelfxFx.play(source.getLevel(), at, slug, scale, yaw);
        source.sendSuccess(() -> Component.literal(String.format(
                "已在 (%.2f, %.2f, %.2f) 播放 pixelfx %s（scale=%sx, yaw=%s°, loop/one-shot 由元数据决定）",
                at.x, at.y, at.z, slug, trim(scale), trim(yaw))), false);
        return 1;
    }

    /**
     * {@code /galboss battle}：让执行者 64 格内最近的 GalBoss boss 立刻进入战斗。
     *
     * <p>用途是自动化/实机验证：结构或 {@code /summon} 放出来的 boss 停在待机对话状态，
     * 必须玩家右键才会 {@code startBattle()}；probe 没法模拟右键，这里给一个等价的调试入口。
     * 不改变任何战斗逻辑，只是把「右键」这一步显式化。
     */
    private static int forceBattle(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Vec3 origin = source.getPosition();
        List<AbstractFSBossEntity> bosses = source.getLevel().getEntitiesOfClass(
                AbstractFSBossEntity.class, new AABB(origin, origin).inflate(64.0));
        AbstractFSBossEntity nearest = bosses.stream()
                .min(Comparator.comparingDouble(boss -> boss.position().distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "64 格内没有 GalBoss 的 boss（例如 galboss:reizein_tohka）"));
            return 0;
        }
        nearest.debugStartBattle();
        source.sendSuccess(() -> Component.literal("已让 " + nearest.getName().getString()
                + " 进入战斗（原版需要玩家右键对话）"), false);
        return 1;
    }

    /** 执行者的水平朝向；控制台 / 命令方块没有实体时退回 source 的 rotation。 */
    private static Vec3 horizontalLook(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity != null) {
            Vec3 look = entity.getLookAngle();
            Vec3 flat = new Vec3(look.x, 0.0, look.z);
            if (flat.lengthSqr() > 1.0E-6) {
                return flat.normalize();
            }
        }
        // MC 习惯：yaw 0 朝 +Z，yaw 90 朝 -X
        double yawRad = Math.toRadians(source.getRotation().y);
        return new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
    }

    private static String trim(float value) {
        return String.format("%.3f", value);
    }
}
