package com.example.galboss.spell;

import com.example.galboss.GalBoss;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 法术引擎：全局维护一串正在施放的法术，每服务端 tick 推进一次。
 *
 * <p>刻意做成「谁都能往里塞」：boss 的 AI、调试命令、以后的法阵/法杖都只调 {@link #cast(Spell)}。
 * 引擎不关心施法者是谁，也不关心特效长什么样。
 *
 * <p>上限 {@value #MAX_ACTIVE} 个同时施放 —— 正常战斗里这个数字用不到十分之一，
 * 它只用来兜住「命令刷屏 / 逻辑写错每 tick 都 cast」这种事故。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpellEngine {

    /** 同时存在的法术上限。 */
    private static final int MAX_ACTIVE = 32;

    private static final List<Spell> ACTIVE = new ArrayList<>();

    private SpellEngine() {
    }

    /** 登记一个已经构造好的法术。 */
    public static void cast(Spell spell) {
        if (spell == null) {
            return;
        }
        if (ACTIVE.size() >= MAX_ACTIVE) {
            GalBoss.LOGGER.warn("[Spell] 同时施法超过 {} 个，丢弃 {}", MAX_ACTIVE, spell.id());
            return;
        }
        GalBoss.LOGGER.debug("[Spell] cast {} at {} (caster={})", spell.id(), spell.origin, spell.casterName());
        ACTIVE.add(spell);
    }

    /** 当前在施放的法术数量（命令 / 自检用）。 */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 清空（服务器停止 / 换档时避免跨档残留）。 */
    public static void clear() {
        for (Spell spell : List.copyOf(ACTIVE)) {
            spell.finish();
        }
        ACTIVE.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final int activeAtStart = ACTIVE.size();
        if (activeAtStart == 0) {
            return;
        }
        // step() 可追加子法术（雷云 → 落雷）；只推进 tick 开始时已有的法术。
        for (int i = 0; i < activeAtStart; i++) {
            Spell spell = ACTIVE.get(i);
            if (!spell.isFinished()) {
                spell.tick();
            }
        }
        ACTIVE.removeIf(Spell::isFinished);
    }
}
