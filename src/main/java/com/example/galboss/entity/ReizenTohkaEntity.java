package com.example.galboss.entity;

import com.example.galboss.boss.BossSkill;
import com.example.galboss.boss.BossTelegraph;
import com.example.galboss.boss.DemeritLedger;
import com.example.galboss.boss.DisciplineRules;
import com.example.galboss.boss.SkillScheduler;
import com.example.galboss.effect.ModEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ① 冷泉院 桐香 —— 控制 / 指挥 / 审判。
 *
 * <p>她不是主要输出，作用是限制玩家并让另外两人更强。技能全部挂在
 * {@link SkillScheduler} 上（结构参考 FDBosses 里「严厉（Geburah）」的攻击池 +
 * 预警 + 罪罚三层），所以同一时刻只会有一段招式在演，不会再出现两条技能抢同一个动画。
 *
 * <h2>记过（Violation Demerits）</h2>
 * 所有「违纪行为」都汇到同一条计数上：违反禁令、短时间爆发输出超标。记过会逐级加重对玩家的
 * 属性惩罚，首次满 3 条触发<b>学生会处分</b>。
 *
 * <p>层数存在共享的 {@link DemeritLedger} 里，因为它是一张<b>全队通缉令</b>：
 * 每一条记过都让礼与郁子对这名玩家的伤害 +15%（最多 +45%）。会长只负责记账与惩罚，
 * 另外两人只读 —— 所以「疯狂输出、无视禁令」的代价是整个队伍都要挨打。
 *
 * <h2>技能表</h2>
 * <ul>
 *   <li>纪律检查（25s）：铃响 1 秒 → 全体抽一条禁令，违反记一条记过</li>
 *   <li>学生会召集（35s）：召唤 FS Guard，护卫存活期间她持续抗性</li>
 *   <li>判决铃（22s）：地面金色预警环 → 冲击波向外扩散，扫过谁打谁</li>
 *   <li>铁拳制裁（18s）：身前猩红矩形预警 → 命中区重击 + 挑飞</li>
 *   <li>禁足令（30s）：锁定最「嚣张」的玩家，预警圈 1.2 秒 → 禁足 3 秒</li>
 *   <li>全校纪律（P2，30s）：全场减速 / 虚弱 / 失明</li>
 *   <li>全校裁决（P2，45s）：按记过条数逐人判罚，无罪者免罚</li>
 *   <li>驳回（被动，40s CD）：单次伤害超过最大生命 10% 时减半</li>
 * </ul>
 */
public class ReizenTohkaEntity extends AbstractFSBossEntity {

    private static final String ID = "reizein_tohka";

    // ------------------------------------------------------------------ 记过
    //
    // 层数存在共享的 {@link DemeritLedger} 里（礼 / 郁子的增伤也要读它），
    // 会长这边只负责两件事：按层数给玩家挂属性惩罚、满层时开一次「学生会处分」。
    /** 每多久对一次账（发现层数变化 → 更新惩罚与提示）。 */
    private static final int DEMERIT_SYNC_INTERVAL = 10;

    /** 学生会在判决之后抬头看人：处分让玩家掉多少血、禁足多久。 */
    private static final float SANCTION_DAMAGE = 12.0f;
    private static final int SANCTION_CONFINEMENT = 3 * 20;
    /** 每完成一次处分，她回一点血 —— 玩家的失控就是她的续航。 */
    private static final float SANCTION_HEAL_RATIO = 0.02f;

    private static final UUID DEMERIT_ATTACK_UUID = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID DEMERIT_SPEED_UUID = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    /** 记过档位的属性惩罚，下标 = 当前记过条数。 */
    private static final double[] DEMERIT_ATTACK = {0.0, 0.0, -0.10, -0.20};
    private static final double[] DEMERIT_SPEED = {0.0, -0.10, -0.15, -0.25};

    // ------------------------------------------------------------ 被动：威严
    private static final int PASSIVE_INTERVAL = 15 * 20;   // 每 15 秒检查一次输出
    private static final int COMBO_WINDOW = 3 * 20;        // 连续攻击统计窗口
    private static final int BURST_WINDOW = 10 * 20;       // 爆发伤害统计窗口
    private static final int COMBO_THRESHOLD = 5;          // 连续攻击 > 5 次
    private static final float BURST_THRESHOLD = 250.0f;   // 10 秒内 > 250 伤害

    // ------------------------------------------------------------------ 驳回
    private static final double REJECT_CD = 40.0;
    private static final float REJECT_THRESHOLD = 0.10f;   // 单次伤害 > 最大生命 10%

    // ------------------------------------------------------------------ 阶段
    private static final double PHASE2_HEALTH = 0.5;
    /** 进入 P2 的过场时长：这段时间她只摆姿态，不放技能。 */
    private static final int PHASE_TRANSITION = 50;

    // ------------------------------------------------------------------ 技能参数
    private static final int TELEGRAPH_INSPECTION = 20;
    private static final int INSPECTION_DURATION = 8 * 20;
    private static final int SUMMON_RADIUS = 4;

    private static final int BELL_TELEGRAPH = 30;          // 1.5 秒预警
    private static final double BELL_RADIUS = 8.0;
    private static final float BELL_DAMAGE = 8.0f;

    private static final int FIST_TELEGRAPH = 24;          // 1.2 秒预警
    private static final float FIST_DAMAGE = 14.0f;
    private static final double FIST_RANGE = 2.4;          // 命中区中心离她多远
    private static final double FIST_HALF_SIZE = 1.5;

    private static final int CONFINEMENT_TELEGRAPH = 24;   // 1.2 秒反应窗口
    private static final double CONFINEMENT_RADIUS = 2.5;
    private static final float CONFINEMENT_DAMAGE = 6.0f;
    private static final int CONFINEMENT_DURATION = 3 * 20;

    private static final int ORDER_TELEGRAPH = 12;
    private static final int VERDICT_TELEGRAPH = 30;
    private static final float VERDICT_BASE_DAMAGE = 6.0f;
    private static final float VERDICT_PER_DEMERIT = 4.0f;

    /** 单个玩家的输出记录。 */
    private static final class DamageLog {
        final Deque<long[]> hits = new ArrayDeque<>(); // {gameTime, damageBits}

        void record(long gameTime, float amount) {
            hits.addLast(new long[]{gameTime, Float.floatToIntBits(amount)});
        }

        void prune(long gameTime, int window) {
            while (!hits.isEmpty() && gameTime - hits.peekFirst()[0] > window) {
                hits.removeFirst();
            }
        }
    }

    private final Map<UUID, DamageLog> damageLogs = new HashMap<>();

    /** 上一次同步给玩家的记过层数，用来发现台账变化（另外两人也在读同一份台账）。 */
    private final Map<UUID, Integer> appliedTiers = new HashMap<>();

    private final SkillScheduler<ReizenTohkaEntity> skills = new SkillScheduler<>();
    private final BossSkill<ReizenTohkaEntity> inspection;
    private final BossSkill<ReizenTohkaEntity> convocation;
    private final BossSkill<ReizenTohkaEntity> judgmentBell;
    private final BossSkill<ReizenTohkaEntity> ironFist;
    private final BossSkill<ReizenTohkaEntity> confinement;
    private final BossSkill<ReizenTohkaEntity> schoolOrder;
    private final BossSkill<ReizenTohkaEntity> verdict;

    private int passiveTimer = PASSIVE_INTERVAL;
    private int rejectCooldown = 0;
    private int transition = 0;
    private boolean phase2 = false;

    public ReizenTohkaEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);

        this.inspection = new InspectionSkill();
        this.convocation = new ConvocationSkill();
        this.judgmentBell = new JudgmentBellSkill();
        this.ironFist = new IronFistSkill();
        this.confinement = new ConfinementSkill();
        this.schoolOrder = new SchoolOrderSkill();
        this.verdict = new VerdictSkill();

        skills.add(inspection)
                .add(convocation)
                .add(judgmentBell)
                .add(ironFist)
                .add(confinement)
                .add(schoolOrder)
                .add(verdict);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // 基础血量；实际按参战人数 ×1 / 1.5 / 2 / 2.5（见 AbstractFSBossEntity#applyHealthScaling）
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 400.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.7)
                .add(Attributes.ARMOR, 8.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    public FSRole getRole() {
        return FSRole.TOHKA;
    }

    @Override
    protected String getEntityId() {
        return ID;
    }

    @Override
    protected BossEvent.BossBarColor getBossBarColor() {
        return BossEvent.BossBarColor.PURPLE;
    }

    @Override
    protected double getMeleeReach() {
        return 4.0;
    }

    @Override
    protected double getAttackMultiplier() {
        return phase2 ? 1.20 : 1.0;
    }

    @Override
    protected double getCooldownMultiplier() {
        return phase2 ? 0.75 : 1.0;
    }

    // ------------------------------------------------------------------ 主循环

    @Override
    protected void tickBoss() {
        if (this.level().isClientSide) {
            return;
        }

        if (rejectCooldown > 0) {
            rejectCooldown--;
        }

        tickTransition();
        tickDemeritTiers();
        tickPassive();
        tickGuards();
        tickPhase2();

        // 阶段过场期间她只顾着摆姿态：不选新技能，也不推进旧的
        if (transition > 0) {
            return;
        }
        skills.tick(this, this.random);
    }

    // ------------------------------------------------------------------ 记过

    private int demeritCount(Player player) {
        return DemeritLedger.count(player);
    }

    /**
     * 记一条过。
     *
     * <p>这里只写台账、不直接发提示：层数是共享的（礼 / 郁子也要读它决定增伤），
     * 统一交给 {@link #tickDemeritTiers()} 对账后再更新惩罚与提示 ——
     * 一次违纪只提示一次，同一 tick 里多条违纪也不会把 actionbar 刷花。
     */
    private void addDemerit(Player player, Component reason, int amount) {
        if (!(player instanceof ServerPlayer) || !player.isAlive()) {
            return;
        }
        DemeritLedger.add(player, amount, reason);
    }

    /**
     * 每 0.5 秒对一次账：层数变了就更新属性惩罚 / 发提示，首次满层时开一次「学生会处分」。
     *
     * <p>遍历的是全场玩家而不是「附近的参战玩家」：跑出参战半径的人层数掉下来了，
     * 属性惩罚也得摘掉，否则会一直挂在他身上。
     */
    private void tickDemeritTiers() {
        if (this.tickCount % DEMERIT_SYNC_INTERVAL != 0) {
            return;
        }
        for (Player player : this.level().players()) {
            int count = DemeritLedger.count(player);
            Integer applied = appliedTiers.get(player.getUUID());
            int before = applied == null ? 0 : applied;
            if (count == before) {
                continue;
            }
            applyDemeritTier(player, count);
            appliedTiers.put(player.getUUID(), count);

            if (count > before) {
                // 记过是全队通缉令：提示里把「礼 / 郁子的增伤」一并报出来。
                // actionbar 两秒就淡了，所以同时往聊天栏留一条，玩家事后翻得回去
                Component notice = Component.translatable(getDialogueKey() + "demerit",
                                count, DemeritLedger.maxCount(), DemeritLedger.lastReason(player),
                                DemeritLedger.bonusPercent(player))
                        .withStyle(ChatFormatting.RED);
                player.displayClientMessage(notice, true);
                player.sendSystemMessage(notice);
                if (before < DemeritLedger.maxCount() && count >= DemeritLedger.maxCount()
                        && player instanceof ServerPlayer sp) {
                    sanction(sp);
                }
            } else {
                // 掉层是好消息：绿色，和记过的红字区分开
                player.displayClientMessage(Component.translatable(getDialogueKey() + "demerit_decay",
                                count, DemeritLedger.maxCount(), DemeritLedger.bonusPercent(player))
                        .withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    /** 把属性惩罚按记过层数同步给全场（读档、她倒下时都要用到）。 */
    private void syncAllDemeritTiers() {
        appliedTiers.clear();
        for (Player player : this.level().players()) {
            int count = DemeritLedger.count(player);
            applyDemeritTier(player, count);
            appliedTiers.put(player.getUUID(), count);
        }
    }

    private void applyDemeritTier(Player player, int tier) {
        int index = Math.max(0, Math.min(DEMERIT_ATTACK.length - 1, tier));
        setPlayerModifier(player, Attributes.ATTACK_DAMAGE, DEMERIT_ATTACK_UUID,
                "galboss:demerit_attack", DEMERIT_ATTACK[index]);
        setPlayerModifier(player, Attributes.MOVEMENT_SPEED, DEMERIT_SPEED_UUID,
                "galboss:demerit_speed", DEMERIT_SPEED[index]);
    }

    private void setPlayerModifier(Player player, Attribute attribute, UUID uuid, String name, double multiplier) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(uuid);
        if (Math.abs(multiplier) > 1.0e-4) {
            instance.addTransientModifier(new AttributeModifier(
                    uuid, name, multiplier, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    /**
     * 学生会处分：记过首次满层时当场执行。
     *
     * <p>禁足 + 魔法伤害 + 摘掉一个正面效果。层数<b>不清零</b>：它同时是礼 / 郁子的增伤依据，
     * 清掉等于「挨完罚就把通缉令撕了」。要掉层只能靠安静 {@code DEMERIT_DECAY} 熬时间。
     */
    private void sanction(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(ModEffects.CONFINEMENT.get(), SANCTION_CONFINEMENT, 0, false, true, true));
        player.hurt(player.damageSources().magic(), SANCTION_DAMAGE);
        stripOneBeneficial(player);
        Component notice = Component.translatable(getDialogueKey() + "sanction_target")
                .withStyle(ChatFormatting.RED);
        player.displayClientMessage(notice, true);
        player.sendSystemMessage(notice);

        BossTelegraph.impact(this.level(), player.position().add(0.0, 1.0, 0.0), BossTelegraph.SCARLET, 16);
        this.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.2f, 0.6f);

        // 玩家失控就是她的续航
        this.heal(this.getMaxHealth() * SANCTION_HEAL_RATIO);
        this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 10 * 20, 0, false, false, true));
        playAnim("rage");
        broadcastNearby(getDialogueKey() + "sanction", "§c");
    }

    /** 摘掉一个正面效果（记过满的人不配再有增益）。 */
    private void stripOneBeneficial(Player player) {
        for (MobEffectInstance instance : player.getActiveEffects()) {
            if (instance.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                player.removeEffect(instance.getEffect());
                return;
            }
        }
    }

    /** 被动：学生会长的威严 —— 短时间猛攻也算违纪。 */
    private void tickPassive() {
        if (--passiveTimer > 0) {
            return;
        }
        passiveTimer = PASSIVE_INTERVAL;

        long now = this.level().getGameTime();
        Iterator<Map.Entry<UUID, DamageLog>> it = damageLogs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DamageLog> entry = it.next();
            DamageLog log = entry.getValue();
            log.prune(now, BURST_WINDOW);
            if (log.hits.isEmpty()) {
                it.remove();
                continue;
            }

            int combo = 0;
            float burst = 0f;
            for (long[] hit : log.hits) {
                burst += Float.intBitsToFloat((int) hit[1]);
                if (now - hit[0] <= COMBO_WINDOW) {
                    combo++;
                }
            }
            if (combo <= COMBO_THRESHOLD && burst <= BURST_THRESHOLD) {
                continue;
            }
            Player player = this.level().getPlayerByUUID(entry.getKey());
            if (player == null || !player.isAlive()) {
                continue;
            }
            addDemerit(player, Component.translatable(getDialogueKey() + "reason_dps"), 1);
            // 她同时获得抗性，防止玩家硬顶
            this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 8 * 20, 0, false, false, true));
        }
    }

    /** 护卫存在期间桐香保持抗性。 */
    private void tickGuards() {
        if (this.tickCount % 20 != 0) {
            return;
        }
        if (FsGuardEntity.countNearby(this.level(), this, 32.0) > 0) {
            this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, 0, false, false, true));
        }
    }

    // ------------------------------------------------------------------ 阶段

    private void tickPhase2() {
        if (phase2 || transition > 0) {
            return;
        }
        if (this.getHealth() <= this.getMaxHealth() * (float) PHASE2_HEALTH) {
            enterPhase2();
        }
    }

    /**
     * 进入二阶段：一段 2.5 秒的过场。
     *
     * <p>对应 Geburah 的 {@code SecondPhaseInitializer} —— 阶段切换本身是一段有始有终的脚本
     * （锁技能 → 演出 → 解锁新技能池），而不是在 tick 里直接翻一个 boolean。
     */
    private void enterPhase2() {
        this.transition = PHASE_TRANSITION;
        skills.interrupt(this);
        skills.block(PHASE_TRANSITION + 20);
        playAnim("phase");
        broadcastNearby(getDialogueKey() + "phase2", "§5");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.6f, 1.2f);
    }

    private void tickTransition() {
        if (transition <= 0) {
            return;
        }
        transition--;

        int elapsed = PHASE_TRANSITION - transition;
        if (this.level() instanceof ServerLevel serverLevel && transition % 4 == 0) {
            // 一圈向外铺开的威压光环
            BossTelegraph.circle(serverLevel, this.position(), 1.5 + elapsed * 0.16, BossTelegraph.VIOLET);
            serverLevel.sendParticles(ParticleTypes.ENCHANT,
                    this.getX(), this.getY() + 1.0, this.getZ(), 8, 0.7, 0.9, 0.7, 0.4);
        }

        if (transition == 0) {
            this.phase2 = true;
            broadcastNearby(getDialogueKey() + "phase2_unlock", "§5");
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.6f, 0.6f);
        }
    }

    // ------------------------------------------------------------------ 战斗

    @Override
    public boolean hurt(DamageSource source, float amount) {
        float effective = amount;

        // 驳回：单次高额伤害减半
        if (isBattleActive() && rejectCooldown <= 0 && effective > this.getMaxHealth() * REJECT_THRESHOLD) {
            rejectCooldown = cd(REJECT_CD);
            effective *= 0.5f;
            playAnim("guard");
            this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 3 * 20, 0, false, false, true));
            broadcastNearby(getDialogueKey() + "reject", "§b");
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 1.5f, 0.7f);
        }

        // 记录输出，供被动判定
        if (isBattleActive() && source.getEntity() instanceof Player player) {
            damageLogs.computeIfAbsent(player.getUUID(), key -> new DamageLog())
                    .record(this.level().getGameTime(), effective);
        }

        return super.hurt(source, effective);
    }

    @Override
    public void respondToCrossfire(Player target) {
        super.respondToCrossfire(target);
        // 交叉火力里桐香负责限制走位：礼一点名就立刻开一次纪律检查
        if (isBattleActive() && transition <= 0) {
            skills.force(this, inspection);
        }
    }

    @Override
    public void die(DamageSource source) {
        // 她倒下后禁令与记过都要立刻失效，否则玩家还会莫名其妙被罚；
        // 台账清空顺带让礼 / 郁子的记过增伤一起作废（「先杀会长」因此是有效战术）
        DisciplineRules.clearAll();
        DemeritLedger.clear();
        syncAllDemeritTiers();
        skills.reset(this);
        super.die(source);
    }

    @Override
    protected void onLastStandEntered() {
        // 最终执行期间她会被自己的召唤物保护，这里补一层抗性
        this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 20, 0, false, false, true));
    }

    @Override
    protected void castUltimate() {
        // 只剩她一个人之后，大招就是「全校纪律」：优先走技能表（带动画和喊话），
        // 手上正好有别的招式在演时直接生效，别让 20 秒一次的终极技能被吃掉。
        if (!skills.force(this, schoolOrder)) {
            applySchoolOrder();
            broadcastNearby(getDialogueKey() + "order", "§5");
            BossTelegraph.circle(this.level(), this.position(), 6.0, BossTelegraph.VIOLET);
        }
    }

    // ------------------------------------------------------------------ 存档

    private static final String TAG_PHASE2 = "FsTohkaPhase2";
    private static final String TAG_TRANSITION = "FsTohkaTransition";

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(TAG_PHASE2, this.phase2);
        tag.putInt(TAG_TRANSITION, this.transition);
        // 记过台账跟着会长落盘：账是她记的（见 DemeritLedger.save 的说明）
        DemeritLedger.save(tag);
        skills.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.phase2 = tag.getBoolean(TAG_PHASE2);
        this.transition = tag.getInt(TAG_TRANSITION);
        DemeritLedger.load(tag);
        skills.load(tag);

        // 记过的属性惩罚是 transient modifier，读档会丢：按台账层数补回来
        if (isBattleActive()) {
            syncAllDemeritTiers();
        }
    }

    // ================================================================== 技能实现

    /** 给所有参战玩家抽同一条禁令。 */
    private void applyInspection() {
        List<Player> targets = nearbyPlayers(PARTICIPANT_RADIUS);
        if (targets.isEmpty()) {
            return;
        }
        DisciplineRules.Rule rule = randomRule();
        int duration = cdTicks(INSPECTION_DURATION);
        for (Player player : targets) {
            if (player instanceof ServerPlayer sp) {
                DisciplineRules.begin(sp, rule, duration, this::onPlayerViolation);
            }
        }
    }

    private DisciplineRules.Rule randomRule() {
        DisciplineRules.Rule[] rules = DisciplineRules.Rule.values();
        return rules[this.random.nextInt(rules.length)];
    }

    /** 违纪回调：违纪记一条记过（谁放的规则谁领）。 */
    private void onPlayerViolation(ServerPlayer player, DisciplineRules.Rule rule) {
        addDemerit(player, Component.translatable(getDialogueKey() + "reason_rule",
                DisciplineRules.displayName(rule)), 1);
    }

    /** 学生会召集：召唤 FS Guard，数量随参战人数与 P2 提升。 */
    private void summonGuards() {
        int count = getParticipants() + 1 + (phase2 ? 1 : 0);
        for (int i = 0; i < count; i++) {
            FsGuardEntity guard = ModEntities.FS_GUARD.get().create(this.level());
            if (guard == null) {
                continue;
            }
            double angle = this.random.nextDouble() * Math.PI * 2;
            double distance = 1.5 + this.random.nextDouble() * (SUMMON_RADIUS - 1.5);
            double px = this.getX() + Math.cos(angle) * distance;
            double pz = this.getZ() + Math.sin(angle) * distance;
            guard.moveTo(px, this.getY(), pz, this.random.nextFloat() * 360f, 0f);
            guard.setTarget(this.getTarget());
            this.level().addFreshEntity(guard);
        }
    }

    /** 全校纪律：全场减速 + 虚弱 + 失明。 */
    private void applySchoolOrder() {
        for (Player player : nearbyPlayers(PARTICIPANT_RADIUS)) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 4 * 20, 1, false, true, true));
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 8 * 20, 0, false, true, true));
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 2 * 20, 0, false, true, true));
        }
    }

    /** 全校裁决：按记过条数逐人判罚，没记过的人免罚。 */
    private void applyVerdict() {
        int punished = 0;
        for (Player player : nearbyPlayers(PARTICIPANT_RADIUS)) {
            int count = demeritCount(player);
            if (count <= 0) {
                player.displayClientMessage(Component.translatable(getDialogueKey() + "verdict_clean"), true);
                continue;
            }
            punished++;
            player.hurt(this.damageSources().mobAttack(this),
                    VERDICT_BASE_DAMAGE + VERDICT_PER_DEMERIT * count);
            stripOneBeneficial(player);
            if (player instanceof ServerPlayer sp) {
                DisciplineRules.begin(sp, randomRule(), cdTicks(8 * 20), this::onPlayerViolation);
            }
            BossTelegraph.impact(this.level(), player.position().add(0.0, 1.0, 0.0), BossTelegraph.SCARLET, 12);
            player.displayClientMessage(Component.translatable(getDialogueKey() + "verdict_hit", count), true);
        }

        broadcastNearby(punished > 0 ? getDialogueKey() + "verdict_done" : getDialogueKey() + "verdict_none", "§c");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6f, 0.7f);
    }

    /** 判决铃的冲击波：只打「这一环刚扫过的人」。 */
    private void damageRing(double from, double to, float damage) {
        for (Player player : nearbyPlayers(to + 1.0)) {
            double distance = Math.sqrt(player.distanceToSqr(this));
            if (distance < from || distance > to) {
                continue;
            }
            player.hurt(this.damageSources().mobAttack(this), damage);
            Vec3 push = flat(player.position().subtract(this.position()));
            if (push.lengthSqr() > 1.0e-4) {
                push = push.normalize().scale(0.9);
                player.push(push.x, 0.35, push.z);
            }
        }
    }

    /** 铁拳制裁的命中区：身前一个 3x3 的方框（矩形预警也是这个框）。 */
    private AABB fistBox() {
        Vec3 dir = flat(facingDirection());
        // 视线可能带俯仰，压平后长度就不是 1 了；这里补一次归一化，
        // 免得抬头打人时框子中心离她太近
        dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(0.0, 0.0, 1.0) : dir.normalize();
        Vec3 center = this.position().add(dir.scale(FIST_RANGE));
        return new AABB(
                center.x - FIST_HALF_SIZE, this.getY() - 0.2, center.z - FIST_HALF_SIZE,
                center.x + FIST_HALF_SIZE, this.getY() + 2.4, center.z + FIST_HALF_SIZE);
    }

    /** 命中判定比预警框略大一点点，免得贴着边框站的人「看起来在里面却没被打到」。 */
    private AABB fistHitArea(AABB box) {
        return box.inflate(0.25, 0.0, 0.25);
    }

    /** 她正对着的方向（优先锁定目标，退化为视线）。 */
    private Vec3 facingDirection() {
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            Vec3 towards = flat(target.position().subtract(this.position()));
            if (towards.lengthSqr() > 1.0e-4) {
                return towards.normalize();
            }
        }
        return this.getLookAngle();
    }

    private static Vec3 flat(Vec3 v) {
        return new Vec3(v.x, 0.0, v.z);
    }

    /** 禁足令优先锁「最嚣张」的人：记过多者优先，其次近期输出最高。 */
    private Player pickConfinementTarget() {
        List<Player> candidates = nearbyPlayers(PARTICIPANT_RADIUS);
        Player best = null;
        int bestDemerit = -1;
        float bestDamage = -1.0f;
        for (Player player : candidates) {
            int count = demeritCount(player);
            float recent = recentDamage(player.getUUID());
            if (count > bestDemerit || (count == bestDemerit && recent > bestDamage)) {
                best = player;
                bestDemerit = count;
                bestDamage = recent;
            }
        }
        return best;
    }

    private float recentDamage(UUID uuid) {
        DamageLog log = damageLogs.get(uuid);
        if (log == null) {
            return 0.0f;
        }
        float sum = 0.0f;
        for (long[] hit : log.hits) {
            sum += Float.intBitsToFloat((int) hit[1]);
        }
        return sum;
    }

    // ------------------------------------------------------------------ ①②③④⑤⑥⑦

    /**
     * 纪律检查：铃响预警 1 秒，然后给所有参战玩家抽一条禁令。
     *
     * <p>违反由 {@link DisciplineRules} 判定，并通过回调记一条记过 —— 规则系统和
     * 罚则分开，和 Geburah 把「罪」的判定放在 boss 里、接口只留钩子是同一种分工。
     */
    private final class InspectionSkill extends BossSkill<ReizenTohkaEntity> {

        InspectionSkill() {
            super("inspection", 25.0, 3);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive();
        }

        /** 检查只有 1 秒，被打断重开不心疼 —— 所以允许交叉火力把它顶掉。 */
        @Override
        public boolean interruptible() {
            return true;
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            playAnim("cast");
            broadcastNearby(getDialogueKey() + "inspection", "§6");
            BossTelegraph.circle(level(), position(), 2.5, BossTelegraph.GOLD);
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4f, 1.0f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < TELEGRAPH_INSPECTION) {
                if (t % 5 == 0) {
                    BossTelegraph.circle(level(), position(), 2.5 + t * 0.08, BossTelegraph.GOLD);
                }
                return false;
            }
            if (t == TELEGRAPH_INSPECTION) {
                applyInspection();
            }
            return t > TELEGRAPH_INSPECTION + 5;
        }
    }

    /** 铁拳制裁：身前矩形预警，1.2 秒后重击 + 挑飞。 */
    private final class IronFistSkill extends BossSkill<ReizenTohkaEntity> {

        private AABB box;

        IronFistSkill() {
            super("iron_fist", 18.0, 3);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive() && boss.getTarget() != null;
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            this.box = fistBox();
            playAnim("attack");
            broadcastNearby(getDialogueKey() + "fist", "§c");
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.2f, 0.9f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < FIST_TELEGRAPH) {
                if (t % 3 == 0) {
                    BossTelegraph.boxOutline(level(), box, BossTelegraph.SCARLET);
                }
                return false;
            }
            if (t == FIST_TELEGRAPH) {
                hit();
            }
            return t > FIST_TELEGRAPH + 6;
        }

        private void hit() {
            AABB area = fistHitArea(box);
            for (Player player : nearbyPlayers(FIST_RANGE + FIST_HALF_SIZE + 2.0)) {
                if (!area.contains(player.position())) {
                    continue;
                }
                player.hurt(damageSources().mobAttack(ReizenTohkaEntity.this), FIST_DAMAGE);
                Vec3 away = flat(player.position().subtract(position()));
                if (away.lengthSqr() > 1.0e-4) {
                    away = away.normalize().scale(0.6);
                    player.push(away.x, 0.6, away.z);
                }
            }
            BossTelegraph.impact(level(), box.getCenter(), BossTelegraph.SCARLET, 24);
            level().playSound(null, box.getCenter().x, box.getCenter().y, box.getCenter().z,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.3f, 0.9f);
        }
    }

    /** 判决铃：地面金色预警环 → 冲击波向外扩散，扫过谁打谁。 */
    private final class JudgmentBellSkill extends BossSkill<ReizenTohkaEntity> {

        private double wave;

        JudgmentBellSkill() {
            super("judgement_bell", 22.0, 3);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive();
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            this.wave = 0.0;
            playAnim("cast");
            broadcastNearby(getDialogueKey() + "bell", "§6");
            BossTelegraph.circle(level(), position(), BELL_RADIUS, BossTelegraph.GOLD);
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.6f, 0.8f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < BELL_TELEGRAPH) {
                if (t % 4 == 0) {
                    BossTelegraph.circle(level(), position(), BELL_RADIUS, BossTelegraph.GOLD);
                }
                if (t % 10 == 0) {
                    level().playSound(null, getX(), getY(), getZ(),
                            SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.0f, 1.5f);
                }
                return false;
            }

            double previous = wave;
            wave += 0.55;
            BossTelegraph.circle(level(), position(), wave, BossTelegraph.GOLD);
            damageRing(previous, wave, BELL_DAMAGE);
            if (wave > BELL_RADIUS) {
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4f, 0.6f);
                return true;
            }
            return false;
        }
    }

    /** 学生会召集：召唤 FS Guard 撑腰。 */
    private final class ConvocationSkill extends BossSkill<ReizenTohkaEntity> {

        ConvocationSkill() {
            super("convocation", 35.0, 2);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive();
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            playAnim("combo");
            broadcastNearby(getDialogueKey() + "summon", "§e");
            BossTelegraph.convocation(level(), position(), SUMMON_RADIUS);
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.2f, 1.4f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t == 24) {
                summonGuards();
                BossTelegraph.convocation(level(), position(), SUMMON_RADIUS);
            }
            return t > 34;
        }
    }

    /** 禁足令：锁定最嚣张的人，1.2 秒预警圈 → 禁足 3 秒。 */
    private final class ConfinementSkill extends BossSkill<ReizenTohkaEntity> {

        private Vec3 marker = Vec3.ZERO;

        ConfinementSkill() {
            super("confinement", 30.0, 2);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive() && !nearbyPlayers(PARTICIPANT_RADIUS).isEmpty();
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            Player target = pickConfinementTarget();
            this.marker = target == null ? position() : target.position();
            playAnim("shoot");
            broadcastNearby(getDialogueKey() + "confinement", "§9");
            if (target != null) {
                target.displayClientMessage(Component.translatable(getDialogueKey() + "confinement_marked"), true);
            }
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.2f, 0.6f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < CONFINEMENT_TELEGRAPH) {
                BossTelegraph.circle(level(), marker, CONFINEMENT_RADIUS, BossTelegraph.VIOLET);
                return false;
            }
            if (t == CONFINEMENT_TELEGRAPH) {
                apply();
            }
            return t > CONFINEMENT_TELEGRAPH + 5;
        }

        private void apply() {
            for (Player player : nearbyPlayers(CONFINEMENT_RADIUS + 1.0)) {
                if (player.position().distanceTo(marker) > CONFINEMENT_RADIUS) {
                    continue;
                }
                player.hurt(damageSources().mobAttack(ReizenTohkaEntity.this), CONFINEMENT_DAMAGE);
                player.addEffect(new MobEffectInstance(
                        ModEffects.CONFINEMENT.get(), CONFINEMENT_DURATION, 0, false, true, true));
                BossTelegraph.impact(level(), player.position().add(0.0, 1.0, 0.0), BossTelegraph.VIOLET, 12);
            }
            level().playSound(null, marker.x, marker.y, marker.z,
                    SoundEvents.SHULKER_BULLET_HIT, SoundSource.HOSTILE, 1.4f, 0.7f);
        }
    }

    /** 全校纪律（P2）：全场减速 / 虚弱 / 失明。 */
    private final class SchoolOrderSkill extends BossSkill<ReizenTohkaEntity> {

        SchoolOrderSkill() {
            super("school_order", 30.0, 2);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive() && phase2;
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            playAnim("cast");
            broadcastNearby(getDialogueKey() + "order", "§5");
            BossTelegraph.circle(level(), position(), 6.0, BossTelegraph.VIOLET);
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.4f, 0.8f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < ORDER_TELEGRAPH) {
                if (t % 4 == 0) {
                    BossTelegraph.circle(level(), position(), 6.0 - t * 0.2, BossTelegraph.VIOLET);
                }
                return false;
            }
            if (t == ORDER_TELEGRAPH) {
                applySchoolOrder();
            }
            return t > ORDER_TELEGRAPH + 10;
        }
    }

    /** 全校裁决（P2）：按记过条数逐人判罚，没记过的人免罚。 */
    private final class VerdictSkill extends BossSkill<ReizenTohkaEntity> {

        VerdictSkill() {
            super("verdict", 45.0, 2);
        }

        @Override
        public boolean canUse(ReizenTohkaEntity boss) {
            return boss.isBattleActive() && phase2;
        }

        @Override
        public void start(ReizenTohkaEntity boss) {
            playAnim("rage");
            broadcastNearby(getDialogueKey() + "verdict", "§4");
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 2.0f, 0.5f);
        }

        @Override
        public boolean tick(ReizenTohkaEntity boss) {
            int t = elapsed();
            if (t < VERDICT_TELEGRAPH) {
                if (t % 2 == 0) {
                    // 外圈定住「法庭范围」，内圈往里收，表示宣判倒计时
                    BossTelegraph.circle(level(), position(), 10.0, BossTelegraph.SCARLET);
                    BossTelegraph.circle(level(), position(), 10.0 * (1.0 - t / (double) VERDICT_TELEGRAPH),
                            BossTelegraph.GOLD);
                }
                return false;
            }
            if (t == VERDICT_TELEGRAPH) {
                applyVerdict();
            }
            return t > VERDICT_TELEGRAPH + 10;
        }
    }
}
