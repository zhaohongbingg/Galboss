package com.example.galboss.boss;

import com.example.galboss.entity.AbstractFSBossEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能调度器：同一时刻只跑一段技能，跑完再按权重抽下一条。
 *
 * <p>对应 Geburah 的 {@code AttackChain}（宏观选招）+ {@code GeburahWeaponAttackController}
 * （当前招式槽）两层。区别是这里不强制连招链，选招就是「就绪 + canUse + 权重随机」，
 * 需要连招时把两条 {@link BossSkill} 写成一条即可。
 *
 * <p>用法：构造时 {@link #add} 所有技能，然后在 boss 的 {@code tickBoss()} 里调 {@link #tick}。
 *
 * @param <T> 使用这个调度器的 boss 类型
 */
public final class SkillScheduler<T extends AbstractFSBossEntity> {

    private static final String TAG_SKILLS = "FsSkills";

    private final List<BossSkill<T>> skills = new ArrayList<>();
    private BossSkill<T> running;

    /** 暂时锁定调度器的剩余 tick（阶段过场这类「她只顾着摆姿态」的时间）。 */
    private int blocked;

    public SkillScheduler<T> add(BossSkill<T> skill) {
        this.skills.add(skill);
        return this;
    }

    /** 锁住调度器一段时间：期间已开始的技能照常推进，但不会再开新的。 */
    public void block(int ticks) {
        this.blocked = Math.max(this.blocked, ticks);
    }

    public boolean isBusy() {
        return running != null;
    }

    public BossSkill<T> running() {
        return running;
    }

    /** 强行收掉当前技能（阶段切换、boss 阵亡）。 */
    public void interrupt(T boss) {
        if (running != null) {
            running.finish(boss, true);
            running = null;
        }
    }

    /** 清空一切：冷却、锁定、当前技能。 */
    public void reset(T boss) {
        interrupt(boss);
        blocked = 0;
        for (BossSkill<T> skill : skills) {
            skill.setCooldown(0);
        }
    }

    public void tick(T boss, RandomSource random) {
        for (BossSkill<T> skill : skills) {
            skill.decCooldown();
        }

        if (blocked > 0) {
            blocked--;
        }
        if (running != null) {
            if (running.advance(boss)) {
                running = null;
            }
            return;
        }
        if (blocked > 0) {
            return;
        }

        BossSkill<T> pick = pickReady(boss, random);
        if (pick != null) {
            running = pick;
            pick.begin(boss);
        }
    }

    /**
     * 立刻施放指定技能，无视冷却。
     *
     * <p>用于「被同伴点名，必须马上响应」这类场合（比如礼的交叉火力）；如果手上正有一段
     * 不可打断的招式，则放弃这次强制施放。
     */
    public boolean force(T boss, BossSkill<T> skill) {
        if (running != null && !running.interruptible()) {
            return false;
        }
        interrupt(boss);
        running = skill;
        skill.begin(boss);
        return true;
    }

    private BossSkill<T> pickReady(T boss, RandomSource random) {
        List<BossSkill<T>> ready = new ArrayList<>();
        int total = 0;
        for (BossSkill<T> skill : skills) {
            if (!skill.ready() || !skill.canUse(boss)) {
                continue;
            }
            ready.add(skill);
            total += skill.weight();
        }
        if (ready.isEmpty()) {
            return null;
        }
        int roll = random.nextInt(total);
        for (BossSkill<T> skill : ready) {
            roll -= skill.weight();
            if (roll < 0) {
                return skill;
            }
        }
        return ready.get(ready.size() - 1);
    }

    // -------------------------------------------------------------------- 存档

    /** 把各技能剩余冷却写进 boss 的 NBT，读档后不会「技能已经就绪」白送一轮爆发。 */
    public void save(CompoundTag tag) {
        CompoundTag out = new CompoundTag();
        for (BossSkill<T> skill : skills) {
            out.putInt(skill.id(), skill.cooldown());
        }
        tag.put(TAG_SKILLS, out);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains(TAG_SKILLS)) {
            return;
        }
        CompoundTag in = tag.getCompound(TAG_SKILLS);
        for (BossSkill<T> skill : skills) {
            if (in.contains(skill.id())) {
                skill.setCooldown(in.getInt(skill.id()));
            }
        }
    }
}
