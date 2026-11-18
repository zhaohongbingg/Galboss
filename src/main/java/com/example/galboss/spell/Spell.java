package com.example.galboss.spell;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 一个「法术」= 一段有 age 的服务端演出 + 判定。
 *
 * <h2>为什么不绑在 boss 身上</h2>
 * 这些是**魔法本身**，不是某个 boss 的部件：落雷、链雷、贯穿、砸落、护盾都应该能被
 * 「任何会施法的实体」用一行代码放出来（{@code SpellEngine.cast(new GungnirSpell(...))}）。
 * boss 只是调用者之一，调试命令也是调用者；以后做别的施法者（法杖、法阵、宠物）不用改这里。
 *
 * <h2>生命周期</h2>
 * 由 {@link SpellEngine} 每服务端 tick 调一次 {@link #tick()}，{@link #age} 从 1 开始。
 * 自己数 tick 的好处：预警、落点、判定、收尾都在同一个对象里，不需要额外的实体或调度器。
 */
public abstract class Spell {

    protected final Level level;
    /** 施法原点（预警、朝向、大多数特效都以它为基准）。 */
    protected final Vec3 origin;
    /** 施法者，可为 null（目的是让它不依赖具体 boss 类型）。 */
    @Nullable
    protected final LivingEntity caster;
    /** 已经过去多少 tick（从 1 开始）。 */
    protected int age;

    private boolean finished;

    protected Spell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        this.level = level;
        this.origin = origin;
        this.caster = caster;
    }

    /** 由引擎调用：推进一 tick，到时就结束。 */
    public final void tick() {
        if (this.finished) {
            return;
        }
        this.age++;
        this.step();
        if (this.age >= this.durationTicks()) {
            this.finished = true;
        }
    }

    /** 每 tick 的表现与判定。 */
    protected abstract void step();

    /** 总时长（tick）。 */
    public abstract int durationTicks();

    /** 命令 / 日志里用的 id，例如 {@code lightning_strike}。 */
    public abstract String id();

    public boolean isFinished() {
        return this.finished;
    }

    public int getAge() {
        return this.age;
    }

    /** 施法者名字（日志用，可能为 null）。 */
    protected String casterName() {
        return this.caster == null ? "none" : this.caster.getName().getString();
    }
}
