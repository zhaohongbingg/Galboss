package com.example.galboss.boss;

import com.example.galboss.entity.AbstractFSBossEntity;

/**
 * 一个 boss 技能。
 *
 * <p>结构取自 FDBosses 里「严厉（Geburah）」那套：boss 本体不再散落一堆 {@code int cooldown}
 * 字段、也不用在 {@code tickBoss()} 里写一长串 {@code if (cd <= 0)}，而是把每段招式做成对象，
 * 注册进 {@link SkillScheduler}，由调度器按「就绪 + 条件门 + 权重」挑一个来跑。
 *
 * <p>生命周期：
 * <pre>
 *   canUse() 通过  ->  start()  ->  tick() 每 tick 一次（返回 true 表示结束）  ->  stop()
 * </pre>
 * 预警、命中、收尾都用 {@link #tick()}（本次已过的 tick 数）分段，和 Geburah 的
 * {@code AttackInstance.tick/stage} 是同一个思路。冷却在技能结束时才起算，遵守
 * {@link AbstractFSBossEntity#cd(double)} 的冷却倍率（P2 / 阵亡同伴会缩短）。
 *
 * @param <T> 使用这个技能的 boss 类型
 */
public abstract class BossSkill<T extends AbstractFSBossEntity> {

    private final String id;
    private final double cooldownSeconds;
    private final int weight;

    private int cooldown;
    private int tick;
    private boolean running;

    protected BossSkill(String id, double cooldownSeconds) {
        this(id, cooldownSeconds, 1);
    }

    protected BossSkill(String id, double cooldownSeconds, int weight) {
        this.id = id;
        this.cooldownSeconds = cooldownSeconds;
        this.weight = Math.max(1, weight);
    }

    // ------------------------------------------------------------------ 子类接口

    /** 存档与调试用的标识，同一个 boss 内唯一。 */
    public final String id() {
        return id;
    }

    /**
     * 本次施放已经过的 tick 数（从 0 开始）。
     *
     * <p>刻意不叫 {@code tick()}：那会和外层 boss 的 {@code Entity#tick()} 撞名，
     * 在内部类里一眼看不出是在读谁的进度。
     */
    protected final int elapsed() {
        return tick;
    }

    /** 同时就绪时权重越高越容易被选中。 */
    public int weight() {
        return weight;
    }

    /**
     * 条件门：阶段、血量、场上状态不满足时返回 false，调度器会跳过它。
     *
     * <p>「按阶段分池」这件事就靠这里表达 —— 二阶段专属技能在 P1 直接返回 false。
     */
    public abstract boolean canUse(T boss);

    /** 正在施放时是否允许被别的技能顶掉（默认 false：招式要播完）。 */
    public boolean interruptible() {
        return false;
    }

    /** 开始施放：播动画、发预警、喊话。 */
    public abstract void start(T boss);

    /** 每 tick 推进；返回 true 表示这段技能结束。 */
    public abstract boolean tick(T boss);

    /**
     * 收尾：清理预警粒子、关掉持续视效。
     *
     * @param interrupted true 表示是被打断/阶段切换强行收掉的
     */
    public void stop(T boss, boolean interrupted) {
    }

    // ------------------------------------------------------------ 调度器内部使用

    final boolean ready() {
        return !running && cooldown <= 0;
    }

    final boolean active() {
        return running;
    }

    final int cooldown() {
        return cooldown;
    }

    final void setCooldown(int ticks) {
        this.cooldown = Math.max(0, ticks);
    }

    final void decCooldown() {
        if (!running && cooldown > 0) {
            cooldown--;
        }
    }

    final void begin(T boss) {
        this.tick = 0;
        this.running = true;
        start(boss);
    }

    final boolean advance(T boss) {
        boolean finished = tick(boss);
        this.tick++;
        if (finished) {
            finish(boss, false);
        }
        return finished;
    }

    /** 结束：重置运行位、按冷却倍率上冷却、回调 stop。 */
    final void finish(T boss, boolean interrupted) {
        this.running = false;
        this.cooldown = interrupted ? Math.max(this.cooldown, 20) : boss.cd(cooldownSeconds);
        stop(boss, interrupted);
    }
}
