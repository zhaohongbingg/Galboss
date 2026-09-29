package com.example.galboss.boss;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.AbstractFSBossEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记过台账 —— 三人组共用的「通缉度」。
 *
 * <p>记过由会长（桐香）记，但<b>吃的罚是全队一起给</b>：每一条记过都让礼与郁子对这名玩家的
 * 伤害提高 {@value #DAMAGE_BONUS_PER_DEMERIT_PERCENT}%。这就是 Geburah 那套「罪」的用法反过来用 ——
 * 那里玩家的罪是自己的血条，这里玩家的违纪是<b>敌人越打越疼</b>的理由。
 *
 * <p>台账刻意做成静态的（和 {@link DisciplineRules} 一样）：另外两个 boss 需要随时查某个玩家
 * 有几条记过，把计数器塞在会长实体里它们就够不着了。层数由会长写入（违反禁令、爆发输出超标），
 * 礼 / 郁子只读。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #DECAY_TICKS} 内没有新的违纪就自动消一条 → 玩家有明确的「洗白」路径</li>
 *   <li>会长倒下时 {@link #clear()}：执法者没了，通缉令自然作废（也让「先杀会长」成为有效战术）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DemeritLedger {

    /** 记过上限（会长在这里触发「学生会处分」）。 */
    public static final int MAX_DEMERITS = 3;

    /** 每一条记过让礼 / 郁子的伤害提高的百分点。 */
    private static final int DAMAGE_BONUS_PER_DEMERIT_PERCENT = 15;

    /** 连续这么久没有新的违纪，自动消掉一条记过。 */
    private static final int DECAY_TICKS = 20 * 20;

    private static final String TAG_LEDGER = "FsDemeritLedger";

    private static final class Entry {
        int count;
        int decay;
        Component reason = Component.empty();

        Entry(int count, int decay, Component reason) {
            this.count = count;
            this.decay = decay;
            this.reason = reason;
        }
    }

    private static final Map<UUID, Entry> LEDGER = new ConcurrentHashMap<>();

    private DemeritLedger() {
    }

    // ------------------------------------------------------------------ 查询

    /** 这名玩家现在背着几条记过。 */
    public static int count(Player player) {
        Entry entry = LEDGER.get(player.getUUID());
        return entry == null ? 0 : entry.count;
    }

    public static int maxCount() {
        return MAX_DEMERITS;
    }

    /** 记过带来的伤害倍率（1.0 = 没有加成）。 */
    public static double damageMultiplier(Player player) {
        return 1.0 + DAMAGE_BONUS_PER_DEMERIT_PERCENT / 100.0 * count(player);
    }

    /** 记过带来的加伤百分比，只用于提示文案。 */
    public static int bonusPercent(Player player) {
        return DAMAGE_BONUS_PER_DEMERIT_PERCENT * count(player);
    }

    /** 最后一次被记过的原因，用来拼提示语。 */
    public static Component lastReason(Player player) {
        Entry entry = LEDGER.get(player.getUUID());
        return entry == null || entry.reason == null ? Component.empty() : entry.reason;
    }

    /**
     * 把伤害按记过层数放大。
     *
     * <p>只给<b>没有实体可追溯</b>的伤害源用（典型例子：礼的狙击弹走 {@code damageSources().magic()}，
     * 事件里 {@code getSource().getEntity()} 是 null，认不出是谁打的）。有实体的伤害一律交给
     * {@link #onLivingHurt}，否则会计两次。
     */
    public static float scaleDamage(Player victim, float damage) {
        double multiplier = damageMultiplier(victim);
        return multiplier > 1.0 ? (float) (damage * multiplier) : damage;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 记过 +{@code amount}，返回记完之后的层数。
     *
     * <p>层数只增不重置（到顶就夹住）：它同时是礼 / 郁子的增伤依据，被一次处分清零的话
     * 「先违规再挨罚」反而成了消掉通缉令的手段。要掉层只能靠 {@link #DECAY_TICKS} 熬时间。
     */
    public static int add(Player player, int amount, Component reason) {
        if (!(player.level() instanceof ServerLevel)) {
            return 0;
        }
        Entry entry = LEDGER.computeIfAbsent(player.getUUID(), key -> new Entry(0, DECAY_TICKS, Component.empty()));
        entry.count = Math.min(MAX_DEMERITS, entry.count + amount);
        entry.decay = DECAY_TICKS;
        entry.reason = reason;
        return entry.count;
    }

    /** 战斗结束 / 执法者倒下时清空。 */
    public static void clear() {
        LEDGER.clear();
    }

    // ------------------------------------------------------------------ 每 tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || LEDGER.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Entry>> it = LEDGER.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            if (--entry.decay > 0) {
                continue;
            }
            if (--entry.count <= 0) {
                it.remove();
                continue;
            }
            entry.decay = DECAY_TICKS;
        }
    }

    /**
     * 礼 / 郁子对带记过的玩家伤害更高。
     *
     * <p>挂在这一层的好处是一次覆盖他们所有伤害途径：近战（走 ATTACK_DAMAGE 属性）、
     * 各自的技能（{@code mobAttack}）、带实体的弹道 —— 都不用改他们自己的代码。
     * 会长本人被排除在外：她的惩罚曲线已经由记过 / 处分承担，再叠一道会变成双重计费。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof AbstractFSBossEntity boss)) {
            return;
        }
        if (boss.getRole() == AbstractFSBossEntity.FSRole.TOHKA) {
            return;
        }
        double multiplier = damageMultiplier(player);
        if (multiplier > 1.0 + 1.0e-4) {
            event.setAmount((float) (event.getAmount() * multiplier));
        }
    }

    // ------------------------------------------------------------------ 存档

    /**
     * 台账落在会长实体的 NBT 里 —— 记过是「她记的账」。
     *
     * <p>正常只有一场遭遇、一个会长，所以不存在冲突；如果以后真同场放两个会长，
     * 后面的写入会覆盖前面的（比谁最后落盘）。
     */
    public static void save(CompoundTag tag) {
        ListTag list = new ListTag();
        LEDGER.forEach((uuid, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Uuid", uuid.toString());
            entryTag.putInt("Count", entry.count);
            entryTag.putInt("Decay", entry.decay);
            entryTag.putString("Reason", Component.Serializer.toJson(entry.reason));
            list.add(entryTag);
        });
        tag.put(TAG_LEDGER, list);
    }

    public static void load(CompoundTag tag) {
        LEDGER.clear();
        ListTag list = tag.getList(TAG_LEDGER, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            try {
                Component reason = Component.Serializer.fromJson(entryTag.getString("Reason"));
                LEDGER.put(UUID.fromString(entryTag.getString("Uuid")),
                        new Entry(entryTag.getInt("Count"), Math.max(1, entryTag.getInt("Decay")),
                                reason == null ? Component.empty() : reason));
            } catch (IllegalArgumentException | com.google.gson.JsonParseException ignored) {
                // 一条坏数据不值得让整个存档读挂
            }
        }
    }
}
